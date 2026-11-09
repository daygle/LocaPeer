package com.locapeer.supervised

import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.util.backgroundScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Visibility layer for supervised mode (kind 1069): whenever the supervised device
 * changes something that affects HOW its location is shared while under an approved
 * unlock - pausing a peer, precision, roles, SOS contacts, temporary shares, schedules -
 * the change is reported to the supervisor as a compact machine-readable entry. Entries
 * batch up over a short debounce so a burst of edits becomes ONE summary event and ONE
 * notification, and the supervisor sees "what changed" even though the change itself was
 * permitted. Areas are string constants so the supervisor side renders them locally.
 *
 * Entries are persisted the moment they are recorded and cleared only after a summary was
 * published, so a process death inside the debounce window - or a publish that throws -
 * no longer drops a change the supervisor was owed; the next flush or start sends it.
 */
@Singleton
class SupervisionChangeReporter @Inject constructor(
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient,
    private val prefs: AppPreferences
) {
    object Areas {
        /** Paused sharing toward a peer. */          const val PAUSE = "PAUSE"
        /** Resumed sharing toward a peer. */          const val RESUME = "RESUME"
        /** Changed a peer's precision mode. */        const val PRECISION = "PRECISION"
        /** Changed the send role toward a peer. */    const val ROLE = "ROLE"
        /** Started a temporary share with a peer. */  const val TEMP_SHARE_START = "TEMP_SHARE_START"
        /** Ended a temporary share. */                const val TEMP_SHARE_END = "TEMP_SHARE_END"
        /** Changed a peer's SOS-contact status. */    const val SOS_CONTACT = "SOS_CONTACT"
        /** Changed the global sharing schedule. */    const val GLOBAL_SCHEDULE = "GLOBAL_SCHEDULE"
        /** Changed a peer's sharing schedule. */       const val SCHEDULE_PEER = "SCHEDULE_PEER"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = backgroundScope("SupervisionChangeReporter")
    private val mutex = Mutex()
    /** Serialises flush passes: a restored summary and a report can both trigger one. */
    private val flushMutex = Mutex()
    /** The unsent entries; mirrored to DataStore on every mutation. */
    private val buffer = mutableListOf<SettingsChangeEntry>()
    private var flushJob: Job? = null

    // One summary event can carry at most this many entries; the rest are dropped by the
    // receiver-side bound too, and peer names are bound here so the notification can render
    // the list without surprise.
    private val maxPeerNameChars = 32
    private val flushDebounceMs = 10_000L

    // All buffer operations await restoration; otherwise a report can win the mutex before
    // the persisted entries have been read and overwrite them with a one-entry buffer.
    private val initialization = scope.async {
        mutex.withLock { loadPersistedLocked() }
    }

    init {
        // Finish a summary a process death interrupted: its entries were persisted when they
        // were recorded, so only the publish is missing. Run immediately instead of waiting
        // for the debounce or for another settings change.
        scope.launch {
            initialization.await()
            flush()
        }
    }

    /** Record one change. Fire-and-forget: report() must never make the surrounding
     *  settings change fail-or-slow, so everything non-trivial runs in our scope. */
    fun report(area: String, peerName: String? = null) {
        scope.launch {
            // Only relevant while supervision is active; early-out keeps unsupervised
            // devices' reports a no-op (callers also check, belt-and-braces).
            if (!prefs.settings.first().supervisionActive) return@launch
            val entry = SettingsChangeEntry(area = area, peerName = peerName?.take(maxPeerNameChars))
            var shouldScheduleFlush: Boolean
            initialization.await()
            mutex.withLock {
                if (buffer.size < MAX_ENTRIES_PER_SUMMARY) buffer.add(entry)
                // Persist before the debounce even starts: a kill in the next 10 s would
                // otherwise lose a change the supervisor is entitled to know about.
                persistLocked()
                // Full buffer flushes immediately; otherwise the debounce timer coalesces.
                shouldScheduleFlush = buffer.isNotEmpty()
            }
            if (shouldScheduleFlush) scheduleFlush()
        }
    }

    private fun scheduleFlush() {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(flushDebounceMs)
            flush()
        }
    }

    private suspend fun flush() {
        initialization.await()
        flushMutex.withLock {
            val entries = mutex.withLock {
                if (buffer.isEmpty()) return
                buffer.toList()
            }
            val supervisorPubkey = prefs.settings.first().supervisorPubkey
            if (supervisorPubkey.isEmpty()) return
            try {
                val (privHex, pubHex) = keyManager.ensureKeypair()
                val payload = json.encodeToString(SettingsChangeSummaryPayload(entries = entries))
                val encrypted = crypto.nip44Encrypt(crypto.hexToBytes(privHex), supervisorPubkey, payload)
                relayClient.publishEvent(
                    NostrEvent.build(
                        privKeyHex = privHex,
                        pubKeyHex = pubHex,
                        kind = NostrEventKind.SETTINGS_CHANGE_SUMMARY,
                        content = encrypted,
                        tags = listOf(listOf("p", supervisorPubkey)),
                        crypto = crypto
                    )
                )
            } catch (e: Exception) {
                // Keep the entries: they stay persisted and go out on the next flush or start.
                android.util.Log.w(TAG, "Failed to send settings change summary", e)
                return
            }
            mutex.withLock {
                // Drop only what this summary carried; a change reported while it was being
                // published stays queued for the next one.
                buffer.removeAll(entries.toSet())
                persistLocked()
            }
        }
    }

    /** Restore entries a previous process had not sent yet; unreadable state is discarded
     *  rather than retried forever, and the kept set is written back so the blob is bounded. */
    private suspend fun loadPersistedLocked(): List<SettingsChangeEntry> {
        val raw = prefs.getSupervisionChangeBufferJson() ?: return emptyList()
        val restored = runCatching {
            json.decodeFromString<List<SettingsChangeEntry>>(raw)
        }.getOrDefault(emptyList())
        buffer.clear()
        buffer.addAll(restored.take(MAX_PERSISTED_ENTRIES))
        if (buffer.isEmpty() || buffer.size != restored.size) persistLocked()
        return buffer.toList()
    }

    private suspend fun persistLocked() {
        prefs.setSupervisionChangeBufferJson(
            if (buffer.isEmpty()) null else json.encodeToString(buffer.toList())
        )
    }

    private companion object {
        const val TAG = "SupervisionChangeReporter"

        /** Entries a single summary event may carry (the receiver bounds its own list too). */
        const val MAX_ENTRIES_PER_SUMMARY = 8

        /** Backstop for a blob that survived many crashes; a summary never needs this many. */
        const val MAX_PERSISTED_ENTRIES = 64
    }
}
