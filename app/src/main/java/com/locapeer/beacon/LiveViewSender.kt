package com.locapeer.beacon

import android.util.Log
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.PeerEntity
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LiveViewSender"

/** How often, while the map is open, this device re-announces that it is viewing. Shorter
 *  than [LIVE_VIEW_LEASE_MS] so a single dropped announce doesn't lapse the recipient's
 *  live mode mid-view. */
const val LIVE_VIEW_RESEND_MS = 20_000L

/**
 * Viewer half of live tracking: while the map screen is open, periodically tells every
 * contact this device *receives* location from to broadcast at a live cadence (see
 * [LiveViewRegistry] for the recipient half). Reduces to a no-op the moment the screen
 * closes, so nobody is boosted longer than they are actually being watched.
 *
 * The request is a rate hint only. It is sent to peers with a RECEIVE / SEND_RECEIVE role;
 * whether the recipient honours it is still gated entirely by *their* role and pause
 * settings, so this can never coax location out of someone who isn't already sharing it.
 */
@Singleton
class LiveViewSender @Inject constructor(
    private val relayClient: NostrRelayClient,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val peerDao: PeerDao,
    private val prefs: com.locapeer.settings.AppPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    // Non-null while the map is open. Reference count guards against a start/stop race
    // between overlapping screen lifecycles (e.g. a config change) tearing down the loop
    // while another entry still wants it running.
    private var loopJob: Job? = null
    private var viewers = 0
    private val lock = Any()

    /** Call when a screen that shows live contact locations becomes visible. */
    fun startViewing() {
        synchronized(lock) {
            viewers++
            if (loopJob?.isActive == true) return
            loopJob = scope.launch { announceLoop() }
        }
    }

    /** Call when that screen is no longer visible. Balanced against [startViewing]. */
    fun stopViewing() {
        synchronized(lock) {
            if (viewers > 0) viewers--
            if (viewers == 0) {
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

    private suspend fun announceOnce() {
        if (!prefs.settings.first().requestLiveBoost) return
        val (privHex, pubHex) = keyManager.ensureKeypair()
        val recipients = peerDao.getAllPeers().first().filter {
            it.locationRole == PeerEntity.ROLE_RECEIVE || it.locationRole == PeerEntity.ROLE_SEND_RECEIVE
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
                    tags = listOf(listOf("p", recipient.publicKeyHex)),
                    crypto = crypto
                )
                relayClient.publishEvent(event)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send live-view request to ${recipient.deviceId}", e)
            }
        }
        Log.d(TAG, "Live-view request sent to ${recipients.size} contact(s)")
    }
}
