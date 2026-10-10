package com.locapeer.nostr

/**
 * Tracks queueable events handed to one relay's socket until the relay answers with an
 * `OK` (NIP-01). `WebSocket.send` returning true only means OkHttp buffered the frame, so a
 * drop before it reaches the relay would otherwise silently lose the event.
 *
 * An entry is resolved by [acknowledged]; on a socket close, [drainOnClose] hands back the
 * entries still awaiting an answer so the caller can keep (or re-create) their outbox rows.
 * A relay that never sends `OK` must not make the outbox grow forever, so entries older than
 * [ackTimeoutMs] are treated as delivered and returned by [expire] for cleanup.
 *
 * Thread-safe: called from OkHttp callback threads and publishing coroutines.
 */
internal class OutboxAckTracker(private val ackTimeoutMs: Long) {

    /** [pendingRowId] is the outbox row the event was flushed from, or null for a live send. */
    data class InFlight(val eventId: String, val message: String, val pendingRowId: Long?, val sentAtMs: Long)

    private val inFlight = LinkedHashMap<String, InFlight>()

    @Synchronized
    fun sent(eventId: String, message: String, pendingRowId: Long?, nowMs: Long) {
        inFlight[eventId] = InFlight(eventId, message, pendingRowId, nowMs)
    }

    @Synchronized
    fun isRowInFlight(pendingRowId: Long): Boolean = inFlight.values.any { it.pendingRowId == pendingRowId }

    /** The relay answered for [eventId]; returns the entry it resolves, if still tracked. */
    @Synchronized
    fun acknowledged(eventId: String): InFlight? = inFlight.remove(eventId)

    /** Removes and returns entries sent more than [ackTimeoutMs] ago without an answer. */
    @Synchronized
    fun expire(nowMs: Long): List<InFlight> {
        val expired = inFlight.values.filter { nowMs - it.sentAtMs >= ackTimeoutMs }
        expired.forEach { inFlight.remove(it.eventId) }
        return expired
    }

    /** The socket closed: returns every unanswered entry and forgets them. */
    @Synchronized
    fun drainOnClose(): List<InFlight> {
        val all = inFlight.values.toList()
        inFlight.clear()
        return all
    }

    companion object {
        /**
         * Whether an `OK false` [message] means the relay will never take this event, so retrying
         * is pointless. NIP-01 prefixes: `duplicate:` (already stored, i.e. delivered) and
         * `invalid:`/`blocked:`/`pow:`/`restricted:` are final; `rate-limited:`, `error:` and
         * anything unrecognised are treated as transient and retried later.
         */
        fun isFinalRejection(message: String): Boolean {
            val prefix = message.substringBefore(':', missingDelimiterValue = "").trim().lowercase()
            return prefix in setOf("duplicate", "invalid", "blocked", "pow", "restricted")
        }
    }
}
