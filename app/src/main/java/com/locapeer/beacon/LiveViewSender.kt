package com.locapeer.beacon

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.locapeer.util.backgroundScope
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.PeerEntity
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LiveViewSender"

/** How often, while a contact's pin is selected, this device re-announces it. Shorter
 *  than [LIVE_VIEW_LEASE_MS] so a single dropped announce doesn't lapse the recipient's
 *  live mode mid-view. */
const val LIVE_VIEW_RESEND_MS = 20_000L

/**
 * Viewer half of live tracking: while a contact's pin is selected on the map, periodically
 * tells *that contact* to broadcast at a live cadence (see [LiveViewRegistry] for the
 * recipient half). Contacts merely visible on the map are never boosted. Reduces to a no-op
 * the moment the pin is deselected or the screen closes, so nobody is boosted longer than
 * they are actually being watched.
 *
 * The request is a rate hint only. It is sent to peers with a RECEIVE / SEND_RECEIVE role;
 * whether the recipient honours it is still gated entirely by *their* role and pause
 * settings, so this can never coax location out of someone who isn't already sharing it.
 */
@Singleton
class LiveViewSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val relayClient: NostrRelayClient,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val peerDao: PeerDao,
    private val prefs: com.locapeer.settings.AppPreferences
) {
    private val scope = backgroundScope(TAG)
    private val json = Json { ignoreUnknownKeys = true }

    // Non-null while any screen is watching a contact. Targets are keyed by the owning
    // screen so overlapping screen lifecycles (e.g. a second map entry on the back stack)
    // can't tear down the loop while another still wants it, or unbalance each other.
    private var loopJob: Job? = null
    private val targets = HashMap<Any, String>()
    private val lock = Any()

    /**
     * [owner] (a screen) is now watching [targetPubkey]. A new or changed target restarts
     * the loop so the request goes out immediately instead of up to [LIVE_VIEW_RESEND_MS]
     * later, making the selected contact go live right away.
     */
    fun startViewing(owner: Any, targetPubkey: String) {
        synchronized(lock) {
            val changed = targets.put(owner, targetPubkey) != targetPubkey
            if (!changed && loopJob?.isActive == true) return
            loopJob?.cancel()
            loopJob = scope.launch { announceLoop() }
        }
    }

    /** [owner] stopped watching anyone. Balanced against [startViewing]. */
    fun stopViewing(owner: Any) {
        synchronized(lock) {
            targets.remove(owner)
            if (targets.isEmpty()) {
                loopJob?.cancel()
                loopJob = null
            }
        }
    }

    private suspend fun announceLoop() {
        // Cancellation (stopViewing) surfaces as a CancellationException from delay() or a
        // suspend call inside announceOnce; it must propagate to end the loop rather than
        // be swallowed as an ordinary failure.
        while (true) {
            try {
                announceOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Live-view announce failed", e)
            }
            delay(LIVE_VIEW_RESEND_MS)
        }
    }

    /**
     * True only while the app is in the foreground with the screen on. The map screen
     * starts and stops viewing from its own lifecycle, but this is checked again before
     * every announce so that a viewing session left running by mistake (a missed lifecycle
     * callback, an undisposed screen) can never keep a contact in live mode while nobody is
     * actually looking: a backgrounded app or a dark screen is never "viewing".
     */
    private fun isUserActuallyViewing(): Boolean {
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.RESUMED)
        val screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive ?: false
        return foreground && screenOn
    }

    private suspend fun announceOnce() {
        if (!isUserActuallyViewing()) {
            Log.d(TAG, "Live-view announce skipped: app not in foreground or screen off")
            return
        }
        if (!prefs.settings.first().requestLiveBoost) return
        val watched = synchronized(lock) { targets.values.toSet() }
        if (watched.isEmpty()) return
        val (privHex, pubHex) = keyManager.ensureKeypair()
        val recipients = peerDao.getAllPeers().first().filter {
            (it.publicKeyHex in watched || it.deviceId in watched) &&
                (it.locationRole == PeerEntity.ROLE_RECEIVE || it.locationRole == PeerEntity.ROLE_SEND_RECEIVE)
        }
        if (recipients.isEmpty()) return

        val payloadJson = json.encodeToString(
            LiveViewPayload(deviceId = pubHex, sentAtMs = System.currentTimeMillis())
        )
        recipients.forEach { recipient ->
            try {
                val encrypted = crypto.nip44Encrypt(
                    senderPrivKey = crypto.hexToBytes(privHex),
                    recipientXOnlyHex = recipient.publicKeyHex,
                    plaintext = payloadJson
                )
                val event = NostrEvent.build(
                    privKeyHex = privHex,
                    pubKeyHex = pubHex,
                    kind = NostrEventKind.LIVE_VIEW_REQUEST,
                    content = encrypted,
                    tags = listOf(
                        listOf("p", recipient.publicKeyHex),
                        // Stale within a minute; let NIP-40 relays discard it.
                        listOf("expiration", (System.currentTimeMillis() / 1000 + LIVE_EVENT_RELAY_TTL_S).toString())
                    ),
                    crypto = crypto
                )
                // A live-view request expires within a minute; never queue it for later.
                relayClient.publishEvent(event, queueIfUndelivered = false)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send live-view request to ${recipient.deviceId}", e)
            }
        }
        Log.d(TAG, "Live-view request sent to ${recipients.size} contact(s)")
    }
}
