package com.locapeer.nostr

/**
 * Bounded, per-sender cache of recently seen Nostr event ids, used by
 * [NostrRelayClient] to suppress duplicate deliveries (relay overlap, retransmits,
 * catch-up replay).
 *
 * The flat single-set FIFO this replaces had a fairness hole: with one shared
 * 2,000-id queue, a peer flooding signed events evicted *other peers'* entries, so
 * their redelivered events lost their suppression exactly when a flood was under way.
 *
 * Here each sender gets its own FIFO window capped at [senderCap], and the global
 * memory bound ([globalCap]) is enforced by trimming the *largest contributor first*
 * - so a flood from one peer can only evict that peer's own oldest entries, never
 * another peer's window. Ids restored from persistence (which can't be attributed to
 * a sender) live in a shared restored bucket that is trimmed first when the global
 * cap binds, since they are the oldest entries.
 *
 * Not thread-safe by itself: [NostrRelayClient] serializes all access behind its own
 * lock.
 */
internal class EventIdDedupCache(
    private val senderCap: Int = 500,
    private val globalCap: Int = 2000,
) {
    // Per-sender FIFO windows using LinkedHashSet for O(1) lookups and O(1) insertion/removal.
    private val bySender = HashMap<String, MutableSet<String>>()
    // Pre-restart ids restored from persistence; O(1) lookups, trimmed first.
    private val restored = HashSet<String>()

    /** True if [eventId] from [sender] was already recorded, or is a restored pre-restart id. */
    fun isKnown(sender: String, eventId: String): Boolean =
        restored.contains(eventId) || bySender[sender]?.contains(eventId) == true

    /**
     * Record [eventId] as seen from [sender]. Returns true when it is new (the caller
     * should emit/process it), false for a duplicate. A sender at its [senderCap]
     * evicts only its own oldest entry; the global cap trims the largest contributor.
     */
    fun record(sender: String, eventId: String): Boolean {
        if (isKnown(sender, eventId)) return false
        val bucket = bySender.getOrPut(sender) { LinkedHashSet() }
        bucket.add(eventId)
        // Per-sender cap: the flooder's own window pays first.
        while (bucket.size > senderCap) {
            val it = bucket.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        trimToGlobalCap()
        return true
    }

    /** Seed ids restored from persistence (seen before this process started). */
    fun addRestored(eventIds: Collection<String>) {
        if (eventIds.isEmpty()) return
        restored.addAll(eventIds)
        trimToGlobalCap()
    }

    /**
     * Every *live* id as a flat set, for persistence. Restored pre-restart ids are
     * excluded: they were already persisted, and dropping them lets the persisted set
     * converge to recent live ids instead of staying anchored to the pre-restart set
     * forever. The in-memory [restored] bucket still suppresses redeliveries until the
     * global cap trims it.
     */
    fun snapshot(): Set<String> = buildSet {
        bySender.values.forEach { addAll(it) }
    }

    private fun trimToGlobalCap() {
        var total = bySender.values.sumOf { it.size } + restored.size
        while (total > globalCap) {
            // Restored pre-restart ids are the oldest entries: trim them first.
            if (restored.isNotEmpty()) {
                val it = restored.iterator()
                it.next()
                it.remove()
                total--
                continue
            }
            // Otherwise evict from the largest contributor so a flood can only shrink
            // its own window, not another sender's.
            val largest = bySender.maxByOrNull { it.value.size } ?: break
            val bucket = largest.value
            if (bucket.isNotEmpty()) {
                val it = bucket.iterator()
                it.next()
                it.remove()
                total--
            } else {
                bySender.remove(largest.key)
            }
        }
    }
}
