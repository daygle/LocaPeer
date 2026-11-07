package com.locapeer.subscriber

import android.app.NotificationChannel
import com.locapeer.util.backgroundScope
import com.locapeer.util.normalizeHexColor
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.locapeer.MainActivity
import com.locapeer.R
import com.locapeer.beacon.HeartbeatPayload
import com.locapeer.beacon.PurgeRequestPayload
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.CircleDao
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.MessageDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.data.dao.PendingRequestDao
import com.locapeer.data.entity.DeliveryState
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.MessageEntity
import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.PendingRequestEntity
import com.locapeer.data.entity.scheduleRules
import com.locapeer.geofence.GeofenceEngine
import com.locapeer.messaging.DeliveryAckPayload
import com.locapeer.messaging.ReadReceiptPayload
import com.locapeer.proximity.ProximityEngine
import com.locapeer.peer.PeerManager
import com.locapeer.peer.PeerRemovedPayload
import com.locapeer.invite.ACTION_TRACK_DECLINE
import com.locapeer.invite.EXTRA_IS_ROLE_CHANGE
import com.locapeer.invite.EXTRA_REQUESTED_ROLE
import com.locapeer.invite.EXTRA_SENDER_NAME
import com.locapeer.invite.EXTRA_SENDER_PUBKEY
import com.locapeer.invite.EXTRA_SENDER_RELAY
import com.locapeer.invite.TrackRequestPayload
import com.locapeer.invite.TrackRequestReceiver
import com.locapeer.supervised.ACTION_SUPERVISED_INVITE_ACCEPT
import com.locapeer.supervised.ACTION_SUPERVISED_INVITE_DECLINE
import com.locapeer.supervised.ACTION_SUPERVISED_REGISTER_ACCEPT
import com.locapeer.supervised.ACTION_SUPERVISED_REGISTER_DECLINE
import com.locapeer.supervised.EXTRA_REQUESTER_NAME
import com.locapeer.supervised.EXTRA_REQUESTER_PUBKEY
import com.locapeer.supervised.EXTRA_REQUESTER_RELAY
import com.locapeer.supervised.SupervisedModeManager
import com.locapeer.supervised.SupervisedRegisterReceiver
import com.locapeer.supervised.SupervisionApprovalManager
import com.locapeer.supervised.ContactAddApprovalManager
import com.locapeer.supervised.ContactAddRequestPayload
import com.locapeer.supervised.ContactAddResponsePayload
import com.locapeer.supervised.SettingsChangeSummaryPayload
import com.locapeer.supervised.SupervisionChangeReporter
import com.locapeer.supervised.SupervisedInvitePayload
import com.locapeer.supervised.SupervisedInviteReceiver
import com.locapeer.supervised.SupervisedInviteResponsePayload
import com.locapeer.supervised.SupervisedRegisterPayload
import com.locapeer.supervised.SupervisedRegisterResponsePayload
import com.locapeer.supervised.SupervisedSupervisorEndPayload
import com.locapeer.supervised.SupervisedDisableRequestPayload
import com.locapeer.supervised.SupervisedDisableResponsePayload
import com.locapeer.supervised.SupervisedWipePayload
import com.locapeer.supervised.UnlockRequestPayload
import com.locapeer.supervised.UnlockResponsePayload
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrFilter
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "HeartbeatReceiver"
private const val CHANNEL_ID_ALERTS = "locapeer_alerts"
private const val CHANNEL_ID_MESSAGES = "locapeer_messages"
private const val SUB_ID = "lp-hb"
private const val SUB_ID_CONTROL = "lp-ctrl"
private const val SUB_ID_HB_CATCHUP = "lp-hb-catchup"
private const val MAX_CATCHUP_SECONDS = 30L * 24 * 3600  // 30 days
// Heartbeats are far more numerous than control events, so cap their replay window
// tighter; relays also cap response sizes, which the limit below reflects.
private const val MAX_HB_CATCHUP_SECONDS = 7L * 24 * 3600  // 7 days
private const val HB_CATCHUP_LIMIT = 1000
// Catch-up bursts (relays flushing stored events at once) can deliver hundreds of
// heartbeats in a moment. Inserting them one-by-one fires a Room invalidation per row,
// which fans out to every observing UI Flow and freezes the main thread. Instead we
// buffer inserts and flush them as a single transaction every HB_BATCH_MAX_DELAY_MS or
// once HB_BATCH_MAX_SIZE are queued, whichever comes first.
private const val HB_BATCH_MAX_SIZE = 20
private const val HB_BATCH_MAX_DELAY_MS = 500L
// Upper bound on buffered heartbeats while flushes are failing (oldest dropped first).
private const val HB_PENDING_MAX = 500
private const val HB_FLUSH_MAX_ATTEMPTS = 5

/** Backoff before retrying a failed heartbeat flush (500 ms, 1 s, 2 s, 4 s), or null
 *  once [HB_FLUSH_MAX_ATTEMPTS] consecutive flushes have failed and the batch is dropped. */
internal fun hbFlushRetryDelayMs(failures: Int): Long? =
    if (failures !in 1 until HB_FLUSH_MAX_ATTEMPTS) null
    else HB_BATCH_MAX_DELAY_MS shl (failures - 1)
// Side effects (SOS alarm, geofence/proximity alerts) only fire for heartbeats this
// recent; older ones are history backfill and must not raise stale alerts.
private const val FRESH_HEARTBEAT_MS = 10 * 60_000L
// Tolerance for inter-device clock skew when rejecting a peer command timestamped
// before we added the contact (see isStalePeerCommand). Generous enough to absorb
// real skew, far tighter than the days-old replays it is meant to block.
private const val CONTROL_CLOCK_SKEW_MS = 24 * 60 * 60_000L
// Flood caps: a stranger cycling keys can send unlimited signed-but-unsolicited track
// requests (each a notification + a row), and a hostile known contact can materialise
// unlimited circles. Above these totals, further NEW ones are dropped silently until the
// user clears the backlog. Existing-contact role changes and known circles are exempt.
private const val MAX_PENDING_TRACK_REQUESTS = 30
private const val MAX_REMOTE_CIRCLES = 100
// A peer-supplied heartbeat timestamp this far in the future is clock skew at best and
// a poisoned "latest pin" at worst; clamp it to arrival time (see processEvent).
private const val HEARTBEAT_FUTURE_SKEW_MS = 60 * 60_000L
// Ceiling for the sender-reported heartbeat interval (24h). Above this the value is
// meaningless for missed-alert timing and only useful for suppression, so it is capped.
private const val MAX_EXPECTED_INTERVAL_S = 24 * 60 * 60L
// Throttle for persisting the heartbeat sync baseline as live events arrive.
private const val HB_EPOCH_SAVE_INTERVAL_S = 300L
// Clock-skew allowance when matching a supervisor's accept to the request it answers.
private const val SUPERVISION_RESPONSE_SKEW_S = 3600L
// A live-view request older than this (by the sender's own clock, tolerant of skew both
// ways) is a stale replay and must not silently re-arm the fast broadcast cadence.
private const val LIVE_VIEW_FRESHNESS_MS = 60_000L
// Ceiling on a decrypted DM. The largest legitimate payload is a capped image nested in a
// circle envelope (~210 KB of Base64 + JSON); anything far beyond that is hostile. Without a
// bound, a contact could store a multi-megabyte row that overflows Android's 2 MB
// CursorWindow and crashes every screen that loads the conversation.
private const val MAX_DM_PLAINTEXT_CHARS = 512 * 1024
private const val MAX_HEARTBEAT_ACCURACY_M = 100_000f
private const val MAX_HEARTBEAT_SPEED_MPS = 1_000f
private const val MIN_HEARTBEAT_ALTITUDE_M = -1_000.0
private const val MAX_HEARTBEAT_ALTITUDE_M = 100_000.0
private const val MAX_MOTION_STATE_CHARS = 32
// Bounds on the sender-controlled parts of a circle envelope that get persisted.
private const val MAX_CIRCLE_MEMBERS = 256
private const val MAX_CIRCLE_NAME_CHARS = 100
// Cap on the self-reported device name a supervised-register request may carry (the same
// bound the invite scanner applies to display names).
private const val MAX_PEER_DISPLAY_NAME_CHARS = 64

/** [value] when it is finite and within [minimum]..[maximum], else [fallback]. Sensor fields in
 *  a heartbeat are peer-controlled, so implausible or non-finite readings are replaced instead of
 *  being persisted and later fed into distance/speed formatting. */
private fun finiteInRange(value: Float, minimum: Float, maximum: Float, fallback: Float = 0f): Float =
    value.takeIf { it.isFinite() && it in minimum..maximum } ?: fallback

private fun finiteInRange(value: Double, minimum: Double, maximum: Double, fallback: Double = 0.0): Double =
    value.takeIf { it.isFinite() && it in minimum..maximum } ?: fallback

// Cap on a peer-supplied relay URL that gets persisted (pending track requests).
private const val MAX_RELAY_URL_CHARS = 512
// Cap on how many "e" tags of one NIP-09 deletion event are processed. Each tag costs a
// database lookup on the ordered event pipeline, and the tag list is sender-controlled.
private const val MAX_DELETION_TARGETS = 100
// Minimum gap between "unknown device requested supervision" notices (all senders).
private const val UNKNOWN_REGISTER_NOTICE_INTERVAL_S = 3600L
private const val UNKNOWN_REGISTER_NOTIF_TAG = "supervised_register_unknown"

// Notification ids are paired with a per-peer tag (notify(tag, id, ...)) instead of folding the
// peer/pubkey hashCode into the id, since two different peers' hashCodes can collide and silently
// overwrite each other's notification.
private const val NOTIF_ID_SOS = 1
private const val NOTIF_ID_MESSAGE = 10000
const val NOTIF_ID_TRACK_REQUEST = 20000
private const val NOTIF_ID_ACCEPT = 30000
private const val NOTIF_ID_DECLINE = 40000
private const val NOTIF_ID_TRACKING_ALERT = 80000
private const val NOTIF_ID_LIVE_WATCH = 81000
/** Don't re-alert for the same watcher more often than this (re-selecting a pin, or a
 *  lease lapsing and returning, would otherwise buzz the user repeatedly). */
private const val LIVE_WATCH_ALERT_COOLDOWN_MS = 10 * 60_000L
const val NOTIF_ID_SUPERVISED_REGISTER = 70000
const val NOTIF_ID_SUPERVISED_INVITE = 75000
private const val NOTIF_ID_SUPERVISED_CHANGE = 85000

// Cooldown between approval dialogs (unlock / disable requests) per sender. A retrying
// supervised device can re-request every few seconds, and each request pops a dialog that
// must be answered first; 60 s keeps the interruption meaningful without dropping the flow.
private const val SUPERVISION_DIALOG_COOLDOWN_MS = 60_000L
private const val SUPERVISION_EVENT_MAX_AGE_S = 86400L
// Freshness window for supervision state-change kinds (invite, invite decline, supervisor
// end, wipe notice): older than a day is relay catch-up replay, not a fresh decision -
// the same window the register-decline handler uses.

/** Never turn receive-only access into outbound location sharing without consent. */
internal fun shouldReviewTrackRequest(existingRole: String?, isRoleChange: Boolean): Boolean =
    if (isRoleChange) existingRole != null
    else existingRole != PeerEntity.ROLE_SEND && existingRole != PeerEntity.ROLE_SEND_RECEIVE

/**
 * Our location role after a contact's TRACK_ACCEPT granting them [acceptedRole]. The acceptor
 * decides whether they send to us (our RECEIVE), but sending OUR location is only ever enabled
 * locally (scanning an invite, or the per-contact send toggle). So an accept can keep or drop
 * our SEND, never add it: otherwise any existing contact could switch on sharing of our
 * location with an unsolicited accept.
 */
internal fun roleAfterTrackAccept(currentRole: String, acceptedRole: String?): String {
    // Their RECEIVE is our SEND and vice versa; a missing or unknown value means they accepted both.
    val theyReceive = acceptedRole != PeerEntity.ROLE_SEND && acceptedRole != PeerEntity.ROLE_NONE
    val theySend = acceptedRole != PeerEntity.ROLE_RECEIVE && acceptedRole != PeerEntity.ROLE_NONE
    val weSent = currentRole == PeerEntity.ROLE_SEND || currentRole == PeerEntity.ROLE_SEND_RECEIVE
    val send = theyReceive && weSent
    return when {
        send && theySend -> PeerEntity.ROLE_SEND_RECEIVE
        send -> PeerEntity.ROLE_SEND
        theySend -> PeerEntity.ROLE_RECEIVE
        else -> PeerEntity.ROLE_NONE
    }
}

/** SOS priority comes from the signed event kind, not the optional payload flag. */
internal fun isSosEvent(eventKind: Int): Boolean = eventKind == NostrEventKind.SOS_ALERT

private val CONTROL_KINDS = listOf(
    NostrEventKind.TRACK_REQUEST,
    NostrEventKind.TRACK_ACCEPT,
    NostrEventKind.TRACK_DECLINE,
    NostrEventKind.PEER_REMOVED,
    NostrEventKind.DELETE_MY_MESSAGES,
    NostrEventKind.DELETE_MY_LOCATION,
    NostrEventKind.SUPERVISED_UNLOCK_REQUEST,
    NostrEventKind.SUPERVISED_UNLOCK_RESPONSE,
    NostrEventKind.SUPERVISED_REGISTER,
    NostrEventKind.SUPERVISED_REGISTER_ACCEPT,
    NostrEventKind.SUPERVISED_REGISTER_DECLINE,
    NostrEventKind.SUPERVISED_INVITE,
    NostrEventKind.SUPERVISED_INVITE_RESPONSE,
    NostrEventKind.SUPERVISED_SUPERVISOR_END,
    NostrEventKind.SUPERVISED_DISABLE_REQUEST,
    NostrEventKind.SUPERVISED_DISABLE_RESPONSE,
    NostrEventKind.SUPERVISED_WIPE_NOTICE,
    NostrEventKind.CONTACT_ADD_REQUEST,
    NostrEventKind.CONTACT_ADD_RESPONSE,
    NostrEventKind.SETTINGS_CHANGE_SUMMARY,
    NostrEventKind.TRACKING_ALERT,
    NostrEventKind.CIRCLE_LEAVE,
    NostrEventKind.EVENT_DELETION
)

@Singleton
class HeartbeatReceiver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val relayClient: NostrRelayClient,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val heartbeatDao: HeartbeatDao,
    private val messageDao: MessageDao,
    private val peerDao: PeerDao,
    private val circleDao: CircleDao,
    private val sharingConfigDao: PeerSharingConfigDao,
    private val pendingRequestDao: PendingRequestDao,
    private val prefs: AppPreferences,
    private val geofenceEngine: GeofenceEngine,
    private val proximityEngine: ProximityEngine,
    private val notificationManager: NotificationManager,
    private val supervisedModeManager: SupervisedModeManager,
    private val supervisionApprovalManager: SupervisionApprovalManager,
    private val contactAddApprovalManager: ContactAddApprovalManager,
    private val peerManager: PeerManager,
    private val liveViewRegistry: com.locapeer.beacon.LiveViewRegistry
) {
    private val scope = backgroundScope(TAG)
    private val json = Json { ignoreUnknownKeys = true }

    /** Highest heartbeat event epoch persisted as the catch-up baseline (throttled). */
    @Volatile private var lastPersistedHbEpoch = 0L

    /**
     * In-memory mirror of [AppPreferences.leftCircleIds], kept fresh by a collector started in
     * [start]. Read on the per-message hot path in [processDmInBackground] to suppress circles the
     * user left, so that path never touches DataStore.
     */
    @Volatile private var leftCircleIds: Set<String> = emptySet()

    /** Last time an approval dialog (unlock / disable request) was surfaced per sender.
     *  In-memory on purpose: the cooldown guards a burst within one session only, and a
     *  process restart must reset it (the user could legitimately retry immediately).
     *  Concurrent because the supervision handlers run in separately launched coroutines on
     *  the IO pool. Keys are only added for known, supervised contacts, so it stays small. */
    private val dialogCooldowns = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Atomically checks the cooldown for [key] and, if it has elapsed, records now as the
     * last time the dialog was shown. Returns false while the cooldown is still running. A
     * separate check and mark would let two concurrent requests both pass and both show a dialog.
     */
    private fun tryMarkApprovalDialogShown(key: String): Boolean {
        val now = System.currentTimeMillis()
        var marked = false
        dialogCooldowns.compute(key) { _, last ->
            if (last == null || now - last >= SUPERVISION_DIALOG_COOLDOWN_MS) {
                marked = true
                now
            } else last
        }
        return marked
    }

    /** Last time the "unknown device requested supervision" notification was shown (epoch
     *  seconds). One global value rather than one per sender: strangers can cycle keys
     *  freely, so a per-pubkey map would grow without bound and post one notification per key. */
    private val unknownRegisterNotifiedAt = java.util.concurrent.atomic.AtomicLong(0L)

    // Buffer for batched heartbeat inserts (see HB_BATCH_* constants). Guarded by
    // batchMutex. pendingKeys mirrors the buffer's (deviceId, timestamp) pairs so a
    // duplicate arriving from a second relay within the same flush window is dropped
    // before it can become a second row (autoGenerated ids defeat REPLACE-based dedup).
    private val batchMutex = Mutex()
    private val pendingHeartbeats = mutableListOf<HeartbeatEntity>()
    private val pendingKeys = HashSet<String>()
    // Stored rows superseded by a newer live point (deviceId to timestamp), deleted in the
    // same transaction as the next flush. Guarded by batchMutex.
    private val pendingDeletes = mutableListOf<Pair<String, Long>>()
    private var flushJob: Job? = null
    private var flushFailures = 0

    fun start() {
        createAlertChannel()
        createMessageChannel()
        // Keep the left-circle cache warm so the DM hot path can suppress left circles without a
        // DataStore read per message.
        prefs.leftCircleIds.onEach { leftCircleIds = it }.launchIn(scope)
        scope.launch {
            val (_, pubHex) = keyManager.ensureKeypair()
            relayClient.connect()
            val nowEpoch = Instant.now().epochSecond

            // Real-time subscription: all event types from this moment forward
            relayClient.subscribe(
                SUB_ID,
                NostrFilter(
                    kinds = listOf(
                        NostrEventKind.HEARTBEAT,
                        NostrEventKind.SOS_ALERT,
                        NostrEventKind.PURGE_REQUEST,
                        NostrEventKind.MESSAGE_PURGE_REQUEST,
                        NostrEventKind.ENCRYPTED_DM,
                        NostrEventKind.READ_RECEIPT,
                        NostrEventKind.DELIVERY_ACK,
                        NostrEventKind.SUPERVISED_UNLOCK_REQUEST,
                        NostrEventKind.SUPERVISED_UNLOCK_RESPONSE,
                        NostrEventKind.SUPERVISED_REGISTER,
                        NostrEventKind.SUPERVISED_REGISTER_ACCEPT,
                        NostrEventKind.SUPERVISED_REGISTER_DECLINE,
                        NostrEventKind.SUPERVISED_INVITE,
                        NostrEventKind.SUPERVISED_INVITE_RESPONSE,
                        NostrEventKind.SUPERVISED_SUPERVISOR_END,
                        NostrEventKind.SUPERVISED_DISABLE_REQUEST,
                        NostrEventKind.SUPERVISED_DISABLE_RESPONSE,
                        NostrEventKind.SUPERVISED_WIPE_NOTICE,
                        NostrEventKind.CONTACT_ADD_REQUEST,
                        NostrEventKind.CONTACT_ADD_RESPONSE,
                        NostrEventKind.SETTINGS_CHANGE_SUMMARY,
                        NostrEventKind.TRACK_REQUEST,
                        NostrEventKind.TRACK_ACCEPT,
                        NostrEventKind.TRACK_DECLINE,
                        NostrEventKind.PEER_REMOVED,
                        NostrEventKind.DELETE_MY_MESSAGES,
                        NostrEventKind.DELETE_MY_LOCATION,
                        NostrEventKind.TRACKING_ALERT,
                        NostrEventKind.CIRCLE_LEAVE,
                        NostrEventKind.LIVE_VIEW_REQUEST,
                        NostrEventKind.EVENT_DELETION
                    ),
                    pTags = listOf(pubHex),
                    since = nowEpoch
                )
            )

            // Catch-up subscription: contact management events only, from last known sync point.
            // Ensures PEER_REMOVED / TRACK_DECLINE / DELETE events sent while offline are replayed.
            // MAX_CATCHUP_SECONDS caps history so we don't request ancient events.
            val lastControlEpoch = prefs.getLastControlSubEpoch()
            val catchUpSince = maxOf(lastControlEpoch - 5, nowEpoch - MAX_CATCHUP_SECONDS)
            relayClient.subscribe(
                SUB_ID_CONTROL,
                NostrFilter(
                    kinds = CONTROL_KINDS,
                    pTags = listOf(pubHex),
                    since = catchUpSince
                )
            )

            // Advance the baseline so the next session only re-fetches events from now onward
            prefs.setLastControlSubEpoch(nowEpoch)
            Log.d(TAG, "Control catch-up since epoch $catchUpSince (last sync: $lastControlEpoch)")

            // Heartbeat catch-up: replay location/SOS events sent while this device was
            // offline, so contacts' location history has no gaps. Without this, only
            // heartbeats that arrive while the app is running are ever stored.
            val lastHbEpoch = prefs.getLastHeartbeatSubEpoch()
            val hbSince = maxOf(lastHbEpoch - 5, nowEpoch - MAX_HB_CATCHUP_SECONDS)
            relayClient.subscribe(
                SUB_ID_HB_CATCHUP,
                NostrFilter(
                    kinds = listOf(NostrEventKind.HEARTBEAT, NostrEventKind.SOS_ALERT),
                    pTags = listOf(pubHex),
                    since = hbSince,
                    limit = HB_CATCHUP_LIMIT
                )
            )
            lastPersistedHbEpoch = maxOf(lastHbEpoch, hbSince)
            Log.d(TAG, "Heartbeat catch-up since epoch $hbSince (last sync: $lastHbEpoch)")
        }

        relayClient.events
            .onEach { event ->
              // A single malformed/hostile event must never cancel the collector and stop
              // all future event processing - isolate every dispatch.
              try {
                when (event.kind) {
                    NostrEventKind.HEARTBEAT, NostrEventKind.SOS_ALERT -> processEvent(event)
                    NostrEventKind.PURGE_REQUEST -> processPurgeRequest(event)
                    NostrEventKind.MESSAGE_PURGE_REQUEST -> processMsgPurgeRequest(event)
                    NostrEventKind.ENCRYPTED_DM -> processDmInBackground(event)
                    NostrEventKind.READ_RECEIPT -> processReadReceiptInBackground(event)
                    NostrEventKind.DELIVERY_ACK -> processDeliveryAckInBackground(event)
                    NostrEventKind.SUPERVISED_UNLOCK_REQUEST -> scope.launch { processUnlockRequest(event) }
                    NostrEventKind.SUPERVISED_UNLOCK_RESPONSE -> scope.launch { processUnlockResponse(event) }
                    NostrEventKind.SUPERVISED_REGISTER -> scope.launch { processRegisterRequest(event) }
                    NostrEventKind.SUPERVISED_REGISTER_ACCEPT -> scope.launch { processRegisterResponse(event, accepted = true) }
                    NostrEventKind.SUPERVISED_REGISTER_DECLINE -> scope.launch { processRegisterResponse(event, accepted = false) }
                    NostrEventKind.SUPERVISED_INVITE -> scope.launch { processSupervisedInvite(event) }
                    NostrEventKind.SUPERVISED_INVITE_RESPONSE -> scope.launch { processSupervisedInviteResponse(event) }
                    NostrEventKind.SUPERVISED_SUPERVISOR_END -> scope.launch { processSupervisedSupervisorEnd(event) }
                    NostrEventKind.SUPERVISED_DISABLE_REQUEST -> scope.launch { processSupervisedDisableRequest(event) }
                    NostrEventKind.SUPERVISED_DISABLE_RESPONSE -> scope.launch { processSupervisedDisableResponse(event) }
                    NostrEventKind.SUPERVISED_WIPE_NOTICE -> scope.launch { processSupervisedWipeNotice(event) }
                    NostrEventKind.CONTACT_ADD_REQUEST -> scope.launch { processContactAddRequest(event) }
                    NostrEventKind.CONTACT_ADD_RESPONSE -> scope.launch { processContactAddResponse(event) }
                    NostrEventKind.SETTINGS_CHANGE_SUMMARY -> scope.launch { processSettingsChangeSummary(event) }
                    NostrEventKind.TRACK_REQUEST -> processTrackRequest(event)
                    // Handled in order (not launched) so an accept and a later removal or
                    // decline from the same contact can't apply out of order.
                    NostrEventKind.TRACK_ACCEPT -> processTrackAccept(event)
                    NostrEventKind.TRACK_DECLINE -> processTrackDecline(event)
                    NostrEventKind.PEER_REMOVED -> processPeerRemoved(event)
                    NostrEventKind.DELETE_MY_MESSAGES -> processDeleteMyMessages(event)
                    NostrEventKind.DELETE_MY_LOCATION -> processDeleteMyLocation(event)
                    NostrEventKind.TRACKING_ALERT -> scope.launch { processTrackingAlert(event) }
                    NostrEventKind.CIRCLE_LEAVE -> processCircleLeave(event)
                    NostrEventKind.LIVE_VIEW_REQUEST -> scope.launch { processLiveViewRequest(event) }
                    NostrEventKind.EVENT_DELETION -> processDeletionEvent(event)
                }
              } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Failed to handle event ${event.id} (kind ${event.kind})", e)
              }
            }
            .launchIn(scope)
    }

    private suspend fun processMsgPurgeRequest(event: NostrEvent) {
        peerDao.getPeer(event.pubkey) ?: return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try { json.decodeFromString<PurgeRequestPayload>(plaintext) } catch (e: Exception) { return }
        if (payload.deviceId != event.pubkey) return
        messageDao.deleteOlderThanFromSender(payload.deviceId, payload.deleteOlderThanMs)
        com.locapeer.messaging.MediaCache.clearDecryptedMedia(context)
        Log.d(TAG, "Purged messages from ${payload.deviceId} before ${payload.deleteOlderThanMs}ms")
    }

    private suspend fun processPurgeRequest(event: NostrEvent) {
        peerDao.getPeer(event.pubkey) ?: return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try { json.decodeFromString<PurgeRequestPayload>(plaintext) } catch (e: Exception) { return }
        if (payload.deviceId != event.pubkey) return
        discardPendingHeartbeats(payload.deviceId, payload.deleteOlderThanMs)
        heartbeatDao.deleteOlderThanForDevice(payload.deviceId, payload.deleteOlderThanMs)
        Log.d(TAG, "Purged ${payload.deviceId} heartbeats before ${payload.deleteOlderThanMs}ms")
    }

    /**
     * A contact has our pin selected and wants our location live. Grant a short lease (see
     * [com.locapeer.beacon.LiveViewRegistry]) so the heartbeat loop speeds up while they
     * watch. This is only a cadence hint: it is honoured strictly within the same
     * role/pause gates the normal heartbeat sender applies, so it can never leak location
     * to a contact we are not already sharing with.
     */
    private suspend fun processLiveViewRequest(event: NostrEvent) {
        val settings = prefs.settings.first()
        if (!settings.allowLiveBoost) return
        // Not sharing at all right now (sharing off, or outside the global schedule): no
        // live updates can flow, so there is no lease to grant - and nothing to tell the
        // user ("watched live") that wouldn't be false.
        if (!settings.heartbeatEnabled) return
        if (!com.locapeer.sharing.SharingSchedule.isActive(settings.globalScheduleRules)) return
        val peer = peerDao.getPeer(event.pubkey) ?: return
        // Only meaningful for a contact we send location to; boosting for anyone else
        // would change nothing they can see.
        if (peer.locationRole != PeerEntity.ROLE_SEND &&
            peer.locationRole != PeerEntity.ROLE_SEND_RECEIVE
        ) return
        // A per-contact pause or inactive per-contact schedule overrides everything, exactly
        // as it does for heartbeats (which would skip this contact anyway).
        val cfg = sharingConfigDao.getForPeer(peer.deviceId)
        if (cfg?.sharingEnabled == false) return
        if (cfg != null) {
            val (dayIndex, minute) = com.locapeer.sharing.SharingSchedule.nowDayMinute()
            val active = com.locapeer.sharing.SharingSchedule.isPeerSharingActive(
                cfg.scheduleRules(), dayIndex, minute,
                cfg.temporaryShareEndsAtEpochSeconds, System.currentTimeMillis() / 1000
            )
            if (!active) return
        }

        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try {
            json.decodeFromString<com.locapeer.beacon.LiveViewPayload>(plaintext)
        } catch (e: Exception) { return }
        // Anti-spoof: the payload identity must match the signer.
        if (payload.deviceId != event.pubkey) return
        // Freshness guard against replays of an old request.
        val now = System.currentTimeMillis()
        if (payload.sentAtMs !in (now - LIVE_VIEW_FRESHNESS_MS)..(now + LIVE_VIEW_FRESHNESS_MS)) return

        val newWatcher = !liveViewRegistry.isViewing(event.pubkey)
        // grant() reports the inactive -> active transition; only then must we wake the
        // service, since while already live it re-reads the registry every 5s on its own.
        if (liveViewRegistry.grant(event.pubkey)) nudgeLiveView()
        if (newWatcher) alertNewWatcher(peer)
        Log.d(TAG, "Live-view request granted for ${event.pubkey}")
    }

    // viewer pubkey -> elapsedRealtime of the last "watching you" alert, for the cooldown.
    private val lastWatchAlertAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Tell the user a contact has just started watching them live (selected their pin), if
     * they opted in. Shares the gates of the other alerts (global alert switch, quiet hours)
     * and is rate-limited per contact by [LIVE_WATCH_ALERT_COOLDOWN_MS].
     */
    private suspend fun alertNewWatcher(peer: PeerEntity) {
        val settings = prefs.settings.first()
        if (!settings.notifyWhenWatched || !settings.alertNotificationsEnabled) return
        if (com.locapeer.util.QuietHours.isQuietNow(settings.quietStartMinute, settings.quietEndMinute)) return
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastWatchAlertAt[peer.publicKeyHex]
        if (last != null && now - last < LIVE_WATCH_ALERT_COOLDOWN_MS) return
        lastWatchAlertAt[peer.publicKeyHex] = now

        val name = peer.displayName.take(MAX_PEER_DISPLAY_NAME_CHARS)
            .ifBlank { context.getString(R.string.geo_unknown) }
        val intent = Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pi = PendingIntent.getActivity(context, NOTIF_ID_LIVE_WATCH, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notif_location)
            .setContentTitle(context.getString(R.string.notif_live_watch_title, name))
            // Below 20% battery live mode is suppressed (battery protection wins), so don't
            // claim a real-time stream that isn't happening.
            .setContentText(
                context.getString(
                    if (isBatteryLow()) R.string.notif_live_watch_text_low_battery
                    else R.string.notif_live_watch_text
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            // The watcher's name is hidden on a locked screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        notificationManager.notify(peer.publicKeyHex, NOTIF_ID_LIVE_WATCH, notification)
    }

    /** Same threshold as [com.locapeer.beacon.AdaptiveIntervalManager.isLowBattery]. */
    private fun isBatteryLow(): Boolean {
        val level = context.getSystemService(android.os.BatteryManager::class.java)
            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return false
        return level in 0 until 20
    }

    /**
     * Poke the running [com.locapeer.beacon.HeartbeatService] so it breaks out of any long
     * sleep and pulses at the live cadence immediately. If the service is not running (or a
     * background start is disallowed), the lease still stands and the service adopts live
     * mode on its next pulse whenever it is next sharing - so a failure here is harmless.
     */
    private fun nudgeLiveView() {
        try {
            context.startService(
                Intent(context, com.locapeer.beacon.HeartbeatService::class.java)
                    .setAction(com.locapeer.beacon.ACTION_LIVE_VIEW)
            )
        } catch (e: Exception) {
            Log.d(TAG, "Live-view service nudge skipped: ${e.message}")
        }
    }

    private suspend fun processEvent(event: NostrEvent) {
        if (event.kind != NostrEventKind.HEARTBEAT && event.kind != NostrEventKind.SOS_ALERT) return
        val broadcaster = peerDao.getPeer(event.pubkey) ?: return

        // Drop normal heartbeats from peers we are not configured to receive from.
        // SOS alerts bypass role checks - emergencies override access control.
        if (event.kind == NostrEventKind.HEARTBEAT &&
            broadcaster.locationRole != PeerEntity.ROLE_RECEIVE &&
            broadcaster.locationRole != PeerEntity.ROLE_SEND_RECEIVE) {
            Log.d(TAG, "Ignoring heartbeat from ${event.pubkey}: role is ${broadcaster.locationRole}")
            return
        }
        try {
            val privHex = keyManager.getPrivateKeyHex() ?: return
            val privBytes = crypto.hexToBytes(privHex)
            val plaintext = crypto.nip44Decrypt(privBytes, event.pubkey, event.content)
            val payload = json.decodeFromString<HeartbeatPayload>(plaintext)
            // The signed event kind is authoritative: a payload flag cannot elevate a normal
            // heartbeat to SOS, while older SOS senders may omit the optional flag.
            val isSos = isSosEvent(event.kind)
            if (isSos) {
                // Keep contacts who raise a verified SOS visible in the contacts UI.
                peerDao.unarchive(event.pubkey)
            }
            // Reject out-of-range coordinates outright: the payload is peer-controlled,
            // and a lat/lng outside the valid range feeds NaN-free but nonsensical values
            // into the geofence/proximity distance math and the map. (kotlinx blocks
            // NaN/Infinity already; this covers merely-implausible finite values.)
            if (payload.lat !in -90.0..90.0 || payload.lng !in -180.0..180.0) {
                Log.w(TAG, "Dropping heartbeat from ${event.pubkey}: out-of-range coords")
                return
            }
            // Use event.pubkey (canonical 64-char Nostr pubkey) so the heartbeat always
            // matches the peer row regardless of what the payload.deviceId contains.
            val canonicalDeviceId = event.pubkey
            // Clamp a future-dated timestamp to arrival time. Queries order by the
            // sender-controlled timestamp, so a far-future value would pin as the
            // permanent "latest" position and re-pass the isFresh gate on every relay
            // redelivery, re-firing SOS/geofence alerts. Past timestamps are fine -
            // they are exactly how catch-up backfill lands as history.
            val now = System.currentTimeMillis()
            val rawTimestampMs = Instant.parse(payload.timestamp).toEpochMilli()
            val timestampMs = rawTimestampMs.coerceAtMost(now + HEARTBEAT_FUTURE_SKEW_MS)
            // Catch-up subscriptions can replay events already stored (relay overlap,
            // dedupe-cache eviction across restarts) - never insert the same ping twice.
            // Also drops duplicates still sitting in the un-flushed batch buffer.
            if (isDuplicateHeartbeat(canonicalDeviceId, timestampMs)) {
                advanceHeartbeatBaseline(event.createdAt)
                return
            }
            val entity = HeartbeatEntity(
                deviceId = canonicalDeviceId,
                displayName = payload.displayName.take(MAX_PEER_DISPLAY_NAME_CHARS),
                timestamp = timestampMs,
                lat = payload.lat,
                lng = payload.lng,
                accuracy = finiteInRange(payload.accuracy, 0f, MAX_HEARTBEAT_ACCURACY_M),
                battery = payload.battery.coerceIn(0, 100),
                motionState = payload.motionState.take(MAX_MOTION_STATE_CHARS).ifBlank { "UNKNOWN" },
                isSos = isSos,
                // Peer-supplied: a malformed colour would crash toColorInt() on the map.
                pinColor = normalizeHexColor(payload.pinColor),
                speed = finiteInRange(payload.speed, 0f, MAX_HEARTBEAT_SPEED_MPS),
                bearing = finiteInRange(payload.bearing, 0f, 360f),
                altitude = finiteInRange(payload.altitude, MIN_HEARTBEAT_ALTITUDE_M, MAX_HEARTBEAT_ALTITUDE_M),
                // Bound the sender-reported interval to a sane range. The missed-heartbeat
                // watchdog multiplies this by 1000 then ×2; an unbounded value would either
                // overflow or push the alert threshold so far out that a peer could silently
                // suppress their own missed-location alerts by claiming a huge interval.
                expectedIntervalSeconds = payload.expectedIntervalSeconds.coerceIn(0L, MAX_EXPECTED_INTERVAL_S)
            )
            // Replayed (old) heartbeats are history backfill: recording them is correct,
            // but alerting on them is not - the situation they describe is long over.
            // The bulk of a catch-up burst is stale, so this side-effect path (and its
            // extra DB read for the previous position) is skipped for the common case.
            val isFresh = System.currentTimeMillis() - timestampMs < FRESH_HEARTBEAT_MS
            // A live-only pulse replayed later (catch-up from a relay that ignored its
            // NIP-40 expiry) has no use: the live moment is over and the sender's cadence
            // heartbeats already cover the history. Drop it.
            if (payload.live && !isFresh && !isSos) {
                advanceHeartbeatBaseline(event.createdAt)
                return
            }
            // Read the previous position BEFORE buffering this one: a size-triggered flush
            // inside enqueueHeartbeat would otherwise make this very ping the "previous"
            // one, and the geofence engine would then never see the transition.
            val prevHeartbeat = if (isFresh) {
                val buffered = batchMutex.withLock {
                    pendingHeartbeats.filter { it.deviceId == canonicalDeviceId }.maxByOrNull { it.timestamp }
                }
                val stored = heartbeatDao.getLatestHeartbeat(canonicalDeviceId)
                listOfNotNull(buffered, stored).maxByOrNull { it.timestamp }
            } else null
            // Keep at most one live-only point per contact: each new live pulse (and the
            // next cadence heartbeat) replaces the previous live one, so while we watch
            // someone their pin moves live but our history of them keeps their cadence.
            replacePreviousLivePoint(canonicalDeviceId, keepAs = if (payload.live && !isSos) timestampMs else null)
            // Buffer the insert; the batch flusher persists it transactionally so a
            // catch-up burst produces a handful of Room invalidations, not hundreds.
            enqueueHeartbeat(entity)
            if (isFresh) {
                if (isSos) sendSosNotification(canonicalDeviceId, broadcaster.displayName, payload)
                // Alerts name the contact as saved locally, not by the payload's
                // self-reported (peer-controlled) display name.
                val alertEntity = entity.copy(displayName = broadcaster.displayName.ifBlank { entity.displayName })
                geofenceEngine.evaluate(alertEntity, prevHeartbeat)
                // Proximity may wait up to 15s for an own-location fix; run it beside the
                // event pipeline so it can't stall every other incoming event meanwhile.
                scope.launch {
                    try {
                        proximityEngine.evaluate(alertEntity)
                    } catch (e: Exception) {
                        Log.w(TAG, "Proximity evaluation failed", e)
                    }
                }
            }
            advanceHeartbeatBaseline(event.createdAt)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process heartbeat", e)
        }
    }

    /**
     * Persist the heartbeat sync baseline (throttled) so the next session's catch-up
     * starts near where this one left off instead of replaying the whole window.
     * We advance the sync baseline whenever we see a newer event, even during
     * catch-up: if the app is killed mid-replay (or a relay flaps), the next start
     * picks up where we left off rather than re-requesting the same 7 days of history.
     */
    private suspend fun advanceHeartbeatBaseline(eventEpoch: Long) {
        val safeEpoch = eventEpoch.coerceAtMost(Instant.now().epochSecond)
        if (safeEpoch > lastPersistedHbEpoch + HB_EPOCH_SAVE_INTERVAL_S) {
            lastPersistedHbEpoch = safeEpoch
            prefs.setLastHeartbeatSubEpoch(safeEpoch)
        }
    }

    // deviceId -> timestamp of the latest live-only point stored for that contact. Touched
    // only from processEvent, which handles heartbeats sequentially.
    private val lastLivePoint = HashMap<String, Long>()

    /**
     * Remove the previously stored live-only point for [deviceId], if any - from the
     * pending batch, or from the database on the next flush - and remember [keepAs] (the incoming point's
     * timestamp when it is itself live-only, else null) as the one to replace next time.
     */
    private suspend fun replacePreviousLivePoint(deviceId: String, keepAs: Long?) {
        val previous = if (keepAs != null) lastLivePoint.put(deviceId, keepAs) else lastLivePoint.remove(deviceId)
        if (previous == null) return
        batchMutex.withLock {
            val removed = pendingHeartbeats.removeAll { it.deviceId == deviceId && it.timestamp == previous }
            if (removed) {
                pendingKeys.remove(hbKey(deviceId, previous))
            } else {
                // Already stored: delete it in the same transaction that stores its
                // replacement (enqueued right after this), so the latest pin never steps back.
                pendingDeletes += deviceId to previous
            }
        }
    }

    /** Coordinate deletions with an in-flight commit so buffered history cannot reappear. */
    private suspend fun discardPendingHeartbeats(deviceId: String, before: Long? = null) {
        batchMutex.withLock {
            pendingHeartbeats.removeAll { it.deviceId == deviceId && (before == null || it.timestamp < before) }
            pendingKeys.clear()
            pendingHeartbeats.forEach { pendingKeys.add(hbKey(it.deviceId, it.timestamp)) }
        }
    }

    private fun hbKey(deviceId: String, timestamp: Long) = "$deviceId@$timestamp"

    /** True if this (deviceId, timestamp) is already persisted or already buffered for insert. */
    private suspend fun isDuplicateHeartbeat(deviceId: String, timestamp: Long): Boolean {
        batchMutex.withLock {
            if (pendingKeys.contains(hbKey(deviceId, timestamp))) return true
        }
        return heartbeatDao.countByDeviceAndTimestamp(deviceId, timestamp) > 0
    }

    /**
     * Add a heartbeat to the pending batch. Flushes immediately once HB_BATCH_MAX_SIZE
     * are queued; otherwise arms a timer so a trickle of live heartbeats still lands
     * within HB_BATCH_MAX_DELAY_MS. Callers must have de-duplicated via
     * [isDuplicateHeartbeat] first.
     */
    private suspend fun enqueueHeartbeat(entity: HeartbeatEntity) {
        val flushNow = batchMutex.withLock {
            // The buffer only grows past HB_BATCH_MAX_SIZE while flushes are failing;
            // bound it so a stuck database can't turn a catch-up burst into a memory leak.
            if (pendingHeartbeats.size >= HB_PENDING_MAX) {
                val oldest = pendingHeartbeats.removeAt(0)
                pendingKeys.remove(hbKey(oldest.deviceId, oldest.timestamp))
            }
            pendingHeartbeats.add(entity)
            pendingKeys.add(hbKey(entity.deviceId, entity.timestamp))
            if (flushJob == null) {
                flushJob = scope.launch {
                    delay(HB_BATCH_MAX_DELAY_MS)
                    flushBatch(fromTimer = true)
                }
            }
            pendingHeartbeats.size >= HB_BATCH_MAX_SIZE
        }
        if (flushNow) flushBatch(fromTimer = false)
    }

    /**
     * Drain the pending buffer and persist it as one transaction. [fromTimer] tells us
     * not to cancel the arming coroutine (it is us); the size-triggered path cancels the
     * still-sleeping timer so it can't double-flush an empty buffer.
     */
    private suspend fun flushBatch(fromTimer: Boolean) {
        batchMutex.withLock {
            if (!fromTimer) flushJob?.cancel()
            flushJob = null
            if (pendingHeartbeats.isEmpty()) return
            try {
                // Keep dedup keys visible until commit; a timer flush must not open a gap
                // where the event collector queues an already-in-flight heartbeat again.
                heartbeatDao.insertAllForExistingPeersReplacing(pendingHeartbeats.toList(), pendingDeletes.toList())
                pendingHeartbeats.clear()
                pendingKeys.clear()
                pendingDeletes.clear()
                flushFailures = 0
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                flushFailures++
                val retryMs = hbFlushRetryDelayMs(flushFailures)
                if (retryMs == null) {
                    // A write that keeps failing (e.g. disk full) must not retry forever.
                    Log.e(TAG, "Dropping heartbeat batch (${pendingHeartbeats.size}) after $flushFailures failed flushes", e)
                    pendingHeartbeats.clear()
                    pendingKeys.clear()
                    pendingDeletes.clear()
                    flushFailures = 0
                    return
                }
                Log.e(TAG, "Failed to flush heartbeat batch (${pendingHeartbeats.size}), retrying in ${retryMs}ms", e)
                // Retain the data on failure, and retry without requiring another arrival.
                flushJob = scope.launch {
                    delay(retryMs)
                    flushBatch(fromTimer = true)
                }
            }
        }
    }

    private fun sendSosNotification(senderPubkey: String, name: String, payload: HeartbeatPayload) {
        val intent = Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
        }
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        // Public (device-lock-screen) version: keep the urgency - who is in trouble - but
        // drop the raw coordinates from the private text, which the full notification below
        // still carries once the phone is unlocked.
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notif_alert)
            .setContentTitle(context.getString(R.string.notif_sos_title, name))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notif_alert)
            .setContentTitle(context.getString(R.string.notif_sos_title, name))
            .setContentText(context.getString(R.string.notif_sos_text, name, payload.lat.toString(), payload.lng.toString()))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pi)
            .setAutoCancel(true)
            // Redact the coordinate-bearing text on a locked screen; show publicVersion instead.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        // Tag by the signer, never the payload's self-declared deviceId: a contact could
        // otherwise name another contact's id and replace that contact's live SOS alert.
        notificationManager.notify(senderPubkey, NOTIF_ID_SOS, notification)
    }

    /** Maps a [com.locapeer.messaging.MediaKind] to its stored [MessageType], or null for an
     *  unrecognised kind so the caller can fall through to the plain-text path instead of
     *  synthesising a bogus media bubble. */
    private fun mediaContentType(kind: String): String? = when (kind) {
        com.locapeer.messaging.MediaKind.IMAGE -> com.locapeer.data.entity.MessageType.IMAGE
        com.locapeer.messaging.MediaKind.AUDIO -> com.locapeer.data.entity.MessageType.AUDIO
        com.locapeer.messaging.MediaKind.FILE -> com.locapeer.data.entity.MessageType.FILE
        else -> null
    }

    /** Localized conversation-list / notification preview for an inline media message. */
    private fun mediaPreview(media: com.locapeer.messaging.MediaMessage): String = when (media.kind) {
        com.locapeer.messaging.MediaKind.IMAGE -> context.getString(R.string.chat_preview_photo)
        com.locapeer.messaging.MediaKind.AUDIO -> context.getString(R.string.chat_preview_voice)
        else -> if (media.filename.isNullOrBlank()) context.getString(R.string.chat_preview_file)
            else context.getString(R.string.chat_preview_file_named, media.filename)
    }

    private suspend fun processDmInBackground(event: NostrEvent) {
        // Received rows are keyed by the event id itself, so this is an indexed PK lookup.
        if (messageDao.existsById(event.id)) return
        val sender = peerDao.getPeer(event.pubkey) ?: return
        val isBlocked = !sender.messagingEnabled
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        if (plaintext.length > MAX_DM_PLAINTEXT_CHARS) {
            Log.w(TAG, "Dropping oversized DM ${event.id.take(16)} from ${event.pubkey}: ${plaintext.length} chars")
            return
        }
        // The signed created_at is the sender's clock. Clamp it to arrival time so a contact with
        // a fast (or deliberately future) clock can't pin their conversation to the top of the
        // list or slip a message past an archive, mirroring the heartbeat future-skew clamp.
        val messageTimestampMs = minOf(event.createdAt * 1000L, System.currentTimeMillis())

        // Circle (group) message: materialise the circle locally and thread by circle id. Only
        // accepted from known, non-blocked contacts (same trust gate as 1:1), so a stranger can't
        // spam you by inventing a "group". No delivery ack: group has no per-sender delivery state.
        //
        // The text payload may also be a nested media-in-group envelope (senders wrap
        // `MediaWire.encode(...)` inside `GroupMessage.text` for image/voice sharing). Detect
        // that here so the DB row carries `contentType=IMAGE/AUDIO` plus the media bytes, and
        // the conversation-list preview shows "📷 Photo" / "🎤 Voice" rather than the raw
        // Base64 magic blob. See `MessagingViewModel.sendGroupMedia` for the matching sender.
        val group = com.locapeer.messaging.GroupWire.decode(plaintext)
        if (group != null) {
            if (isBlocked) return
            val (_, myPubHex) = keyManager.ensureKeypair()
            // Drop our own fan-out copy echoed back by a relay, and ignore a group we're not in.
            if (event.pubkey == myPubHex || !group.members.contains(myPubHex)) return
            // Creator-signed circle state: a bad signature is a forgery or corruption, so drop the
            // message rather than deliver it into a circle whose creator it misrepresents.
            val signedVersion = if (group.csig.isEmpty()) 0L else {
                if (!com.locapeer.messaging.GroupControl.verify(
                        crypto, group.csig, group.gid, group.creator, group.cver, group.members, group.gname
                    )
                ) {
                    Log.w(TAG, "Dropping circle message for ${group.gid} from ${event.pubkey}: invalid creator signature")
                    return
                }
                group.cver
            }
            // If the user explicitly left this circle, don't let a straggler re-create it. A message
            // from the circle's creator that still lists us is treated as a re-invite: clear the
            // "left" flag and rejoin. Any other sender (a member still fanning to us before the
            // owner's removal has propagated) is dropped so the circle stays gone.
            if (leftCircleIds.contains(group.gid)) {
                if (group.creator.isNotBlank() && event.pubkey == group.creator) {
                    prefs.removeLeftCircleId(group.gid)
                } else {
                    return
                }
            }
            // For a circle we already know, only the recorded creator or a locally-known member
            // may post into the thread. The envelope's member list is sender-controlled, so
            // without this gate a member the creator has since removed (who still knows the gid)
            // could keep injecting messages into a thread the user believes is circle-private.
            // Circles that predate the creator field (blank creator) keep the old open behaviour.
            val knownCircle = circleDao.getCircle(group.gid)
            // The signer of a valid creator signature is the proven creator. If the recorded
            // creator is only another member's unconfirmed claim, let them through so their signed
            // state can replace it (see GroupControl.decide).
            val provenCreatorOverridingClaim = knownCircle != null && signedVersion > 0 &&
                event.pubkey == group.creator && knownCircle.controlVersion == 0L &&
                knownCircle.membershipUpdatedAt == 0L && knownCircle.creatorPubkey != myPubHex
            if (knownCircle != null && knownCircle.creatorPubkey.isNotBlank() &&
                event.pubkey != knownCircle.creatorPubkey && !provenCreatorOverridingClaim &&
                !circleDao.getMemberPubkeys(group.gid).contains(event.pubkey)
            ) {
                Log.w(TAG, "Dropping circle message for ${group.gid} from non-member ${event.pubkey}")
                return
            }
            // Flood cap: only a brand-new circle grows the table, so a hostile known contact
            // inventing endless gids can't balloon it. Messages into already-known circles
            // are unaffected.
            if (knownCircle == null && circleDao.countCircles() >= MAX_REMOTE_CIRCLES) {
                Log.w(TAG, "Dropping new circle ${group.gid}: circle cap reached")
                return
            }
            if (group.members.size > MAX_CIRCLE_MEMBERS) {
                Log.w(TAG, "Dropping circle message for ${group.gid}: ${group.members.size} members")
                return
            }
            circleDao.materialiseFromRemote(
                circleId = group.gid,
                name = group.gname.take(MAX_CIRCLE_NAME_CHARS),
                creatorPubkey = group.creator,
                senderPubkey = event.pubkey,
                // Never store our own pubkey as a member row: locally created circles keep only
                // the OTHER participants, so counts and the edit screen stay consistent across
                // created and materialised circles. Send-side fan-out re-appends self on the wire.
                memberPubkeys = group.members.filter { it != myPubHex },
                // Clamped to now: a creator with a fast clock must not freeze later updates.
                eventCreatedAt = minOf(event.createdAt, Instant.now().epochSecond),
                signedVersion = signedVersion,
                signedSig = group.csig,
                selfPubkey = myPubHex
            )
            // Auto-unarchive on receive, mirroring the 1:1 peer path: a fresh message pulls the
            // circle back onto the Circles tab, but a late-arriving/queued message that predates
            // an explicit archive action doesn't silently undo it.
            circleDao.getCircle(group.gid)?.let { circleRow ->
                if (!circleRow.isArchived || messageTimestampMs > circleRow.archivedAt) {
                    circleDao.unarchive(group.gid)
                }
            }
            val innerMedia = com.locapeer.messaging.MediaWire.decode(group.text)
            // Validate `kind` against the recognised union before consuming the media branch.
            // An unknown kind (a future type, hand-crafted garbage, etc.) returns null here and
            // falls through to the text-render path, so the user sees a real media bubble only for
            // kinds this app actually understands (image / voice / file) - never a fake voice note
            // synthesised from an unknown payload.
            val innerType = innerMedia?.let { mediaContentType(it.kind) }
            if (innerMedia != null && innerType != null) {
                val preview = mediaPreview(innerMedia)
                messageDao.insert(
                    MessageEntity(
                        id = event.id,
                        peerId = group.gid,
                        senderPublicKeyHex = event.pubkey,
                        content = preview,
                        timestamp = messageTimestampMs,
                        isMine = false,
                        deliveryState = DeliveryState.DELIVERED.name,
                        nostrEventId = event.id,
                        groupId = group.gid,
                        contentType = innerType,
                        mediaBase64 = innerMedia.data,
                        mediaDurationMs = innerMedia.durationMs,
                        mediaFilename = innerMedia.filename,
                        mediaMimeType = innerMedia.mimeType,
                    )
                )
                sendGroupMessageNotification(group.gname, sender.displayName, preview, group.gid)
                return
            }
            // Safety net: never persist raw magic-prefixed bytes (corrupted or unsupported media
            // envelope that decode() rejected) as plain text - the conversation list would render
            // a garbled LPM1{...} JSON blob as the message body. Render a photo-preview
            // placeholder instead, which matches the most common case.
            val content = if (com.locapeer.messaging.MediaWire.isEnvelope(group.text))
                context.getString(R.string.chat_preview_photo)
            else group.text
            messageDao.insert(
                MessageEntity(
                    id = event.id,
                    peerId = group.gid,
                    senderPublicKeyHex = event.pubkey,
                    content = content,
                    timestamp = messageTimestampMs,
                    isMine = false,
                    deliveryState = DeliveryState.DELIVERED.name,
                    nostrEventId = event.id,
                    groupId = group.gid
                )
            )
            sendGroupMessageNotification(group.gname, sender.displayName, content, group.gid)
            return
        }

        // Inline media (image / voice): store the Base64 payload on the row so retention/purge/
        // backup handle it unchanged, mirroring the 1:1 text path (blocked rows stored hidden).
        val media = com.locapeer.messaging.MediaWire.decode(plaintext)
        val mediaType = media?.let { mediaContentType(it.kind) }
        if (media != null && mediaType != null) {
            val preview = mediaPreview(media)
            val mediaMsg = MessageEntity(
                id = event.id,
                peerId = event.pubkey,
                senderPublicKeyHex = event.pubkey,
                content = preview,
                timestamp = messageTimestampMs,
                isMine = false,
                deliveryState = if (isBlocked) DeliveryState.SENT.name else DeliveryState.DELIVERED.name,
                nostrEventId = event.id,
                isBlocked = isBlocked,
                contentType = mediaType,
                mediaBase64 = media.data,
                mediaDurationMs = media.durationMs,
                mediaFilename = media.filename,
                mediaMimeType = media.mimeType
            )
            if (!sender.isArchived || mediaMsg.timestamp > sender.archivedAt) {
                peerDao.unarchive(event.pubkey)
            }
            messageDao.insert(mediaMsg)
            if (!isBlocked) {
                sendBackgroundMessageNotification(sender.displayName, preview, event.pubkey)
                sendDeliveryAck(event.pubkey, event.id)
            }
            return
        }

        val msg = MessageEntity(
            id = event.id,
            peerId = event.pubkey,
            senderPublicKeyHex = event.pubkey,
            content = plaintext,
            timestamp = messageTimestampMs,
            isMine = false,
            deliveryState = if (isBlocked) DeliveryState.SENT.name else DeliveryState.DELIVERED.name,
            nostrEventId = event.id,
            isBlocked = isBlocked
        )
        // Auto-unarchive on receive, but don't let a late-arriving/queued message that predates
        // an explicit archive action silently undo it.
        if (!sender.isArchived || msg.timestamp > sender.archivedAt) {
            peerDao.unarchive(event.pubkey)
        }
        messageDao.insert(msg)
        if (!isBlocked) {
            sendBackgroundMessageNotification(sender.displayName, plaintext, event.pubkey)
            sendDeliveryAck(event.pubkey, event.id)
        }
    }

    /**
     * NIP-09 deletion: remove each referenced message, but only one the signer sent us. The
     * signature was verified by the relay client, and the sender match means nobody can delete
     * messages they didn't write.
     */
    private suspend fun processDeletionEvent(event: NostrEvent) {
        // Only contacts can have sent us anything to delete. This check also stops a stranger
        // from stalling the ordered event pipeline with a deletion that lists thousands of ids.
        peerDao.getPeer(event.pubkey) ?: return
        val targetEventIds = event.tags.asSequence()
            .filter { it.firstOrNull() == "e" }
            .mapNotNull { it.getOrNull(1) }
            .distinct()
            .take(MAX_DELETION_TARGETS)
            .toList()
        var deleted = false
        targetEventIds.forEach { eventId ->
            val msg = messageDao.getByNostrEventId(eventId)
            if (msg != null && msg.senderPublicKeyHex == event.pubkey) {
                messageDao.delete(msg)
                deleted = true
            }
        }
        if (deleted) com.locapeer.messaging.MediaCache.clearDecryptedMedia(context)
    }

    private suspend fun processReadReceiptInBackground(event: NostrEvent) {
        peerDao.getPeer(event.pubkey) ?: return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val receipt = try { json.decodeFromString<ReadReceiptPayload>(plaintext) } catch (e: Exception) { return }
        // Bounded: each id is a database update, and the list is contact-controlled.
        receipt.eventIds.asSequence().distinct().take(ReadReceiptPayload.MAX_IDS).forEach { eventId ->
            messageDao.markReadForPeer(eventId, event.pubkey)
        }
    }

    private suspend fun processDeliveryAckInBackground(event: NostrEvent) {
        peerDao.getPeer(event.pubkey) ?: return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try { json.decodeFromString<DeliveryAckPayload>(plaintext) } catch (e: Exception) { return }
        messageDao.markDeliveredForPeer(payload.eventId, event.pubkey)
    }

    private suspend fun processUnlockRequest(event: NostrEvent) {
        // Ignore requests older than 5 minutes to avoid annoying the supervisor with stale popups
        if (event.createdAt < Instant.now().epochSecond - 300) return

        peerDao.getPeer(event.pubkey) ?: return
        // Only show the approval dialog if this contact is explicitly marked as a supervised device;
        // any known contact can send a SUPERVISED_UNLOCK_REQUEST, so the isMySupervised flag prevents
        // social-engineering attacks from regular contacts.
        if (sharingConfigDao.getForPeer(event.pubkey)?.isMySupervised != true) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try { json.decodeFromString<UnlockRequestPayload>(plaintext) } catch (e: Exception) { return }
        // A supervised user can re-request as fast as they can tap; without a cooldown each
        // request would machine-gun the approval dialog while the previous one is pending.
        if (!tryMarkApprovalDialogShown("unlock:${event.pubkey}")) return
        supervisionApprovalManager.setPending(
            SupervisionApprovalManager.PendingRequest(
                fromPubkey = event.pubkey,
                // Peer-supplied name; bound so a multi-kilobyte "device name" can't render an
                // oversized approval dialog (notification text is truncated by the system, but
                // the in-app AlertDialog is not). Notification and dialog both show this value.
                deviceName = payload.deviceName.take(MAX_PEER_DISPLAY_NAME_CHARS),
                requestId = payload.requestId
            )
        )
    }

    private suspend fun processUnlockResponse(event: NostrEvent) {
        if (event.createdAt < Instant.now().epochSecond - 300) return
        val settings = prefs.settings.first()
        if (settings.supervisorPubkey.isEmpty() || event.pubkey != settings.supervisorPubkey) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try { json.decodeFromString<UnlockResponsePayload>(plaintext) } catch (e: Exception) { return }
        supervisedModeManager.handleResponse(payload.requestId, payload.approved)
    }

    private suspend fun processRegisterRequest(event: NostrEvent) {
        // Ignore requests older than 24 hours - supervisor must be reachable within a day
        if (event.createdAt < Instant.now().epochSecond - 86400) return
        val peer = peerDao.getPeer(event.pubkey)
        if (peer == null) {
            notifyUnknownRegisterRequest(event)
            return
        }
        if (sharingConfigDao.getForPeer(event.pubkey)?.isMySupervised == true) {
            // Already supervising this device: answer the (re)registration in-band with an
            // accept instead of another dialog. This closes the supervisor-initiated invite
            // loop (its 1062 accept step triggers this register request) and repairs the
            // handshake after our own process death without needing the supervisor again.
            autoAcceptRegistration(peer)
            return
        }
        // Suppress relay retransmissions if the notification is still active
        if (notificationManager.activeNotifications.any {
                it.id == NOTIF_ID_SUPERVISED_REGISTER && it.tag == event.pubkey }) return

        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try {
            json.decodeFromString<SupervisedRegisterPayload>(plaintext)
        } catch (e: Exception) { return }

        val notifId = event.pubkey.hashCode() + 70000
        val acceptIntent = Intent(context, SupervisedRegisterReceiver::class.java).apply {
            setPackage(context.packageName)
            action = ACTION_SUPERVISED_REGISTER_ACCEPT
            putExtra(EXTRA_REQUESTER_PUBKEY, event.pubkey)
            putExtra(EXTRA_REQUESTER_NAME, payload.deviceName.take(MAX_PEER_DISPLAY_NAME_CHARS))
            putExtra(EXTRA_REQUESTER_RELAY, payload.deviceRelayUrl)
        }
        val declineIntent = Intent(context, SupervisedRegisterReceiver::class.java).apply {
            setPackage(context.packageName)
            action = ACTION_SUPERVISED_REGISTER_DECLINE
            putExtra(EXTRA_REQUESTER_PUBKEY, event.pubkey)
            putExtra(EXTRA_REQUESTER_NAME, payload.deviceName.take(MAX_PEER_DISPLAY_NAME_CHARS))
            putExtra(EXTRA_REQUESTER_RELAY, payload.deviceRelayUrl)
        }
        val acceptPi = PendingIntent.getBroadcast(
            context, notifId, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val declinePi = PendingIntent.getBroadcast(
            context, notifId + 1, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigateTo", "contacts")
            putExtra(com.locapeer.EXTRA_CANCEL_NOTIF_TAG, event.pubkey)
            putExtra(com.locapeer.EXTRA_CANCEL_NOTIF_ID, NOTIF_ID_SUPERVISED_REGISTER)
            putExtra(com.locapeer.EXTRA_CANCEL_NOTIF_TOKEN, com.locapeer.util.NotificationCancelToken.get(context))
        }
        val openAppPi = PendingIntent.getActivity(
            context, notifId + 2, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notif_alert)
            .setContentTitle(context.getString(R.string.notif_supervisor_request_title, payload.deviceName.take(MAX_PEER_DISPLAY_NAME_CHARS)))
            .setContentText(context.getString(R.string.notif_supervisor_request_text))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.notif_supervisor_request_big, payload.deviceName.take(MAX_PEER_DISPLAY_NAME_CHARS))))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppPi)
            .addAction(0, context.getString(R.string.notif_action_accept), acceptPi)
            .addAction(0, context.getString(R.string.common_decline), declinePi)
            .setAutoCancel(false)
            .build()
        notificationManager.notify(event.pubkey, NOTIF_ID_SUPERVISED_REGISTER, notification)
    }

    /**
     * A device we have not added as a contact asked us to supervise it. A silent drop would
     * leave that device waiting forever, so show one low-priority notice telling the user to
     * add it as a contact first. The request goes no further until they do.
     *
     * The event must decrypt to a valid register payload, so random kind-1055 noise is not
     * shown. Throttled globally to one notice per [UNKNOWN_REGISTER_NOTICE_INTERVAL_S] under
     * one fixed tag, so a stranger cycling keys can neither flood the shade nor grow memory.
     */
    private suspend fun notifyUnknownRegisterRequest(event: NostrEvent) {
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        try {
            json.decodeFromString<SupervisedRegisterPayload>(plaintext)
        } catch (e: Exception) { return }
        val nowS = Instant.now().epochSecond
        val last = unknownRegisterNotifiedAt.get()
        if (nowS - last < UNKNOWN_REGISTER_NOTICE_INTERVAL_S) return
        if (!unknownRegisterNotifiedAt.compareAndSet(last, nowS)) return
        notificationManager.notify(
            UNKNOWN_REGISTER_NOTIF_TAG,
            NOTIF_ID_SUPERVISED_REGISTER,
            NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
                .setSmallIcon(R.drawable.ic_notif_alert)
                .setContentTitle(context.getString(R.string.notif_supervisor_unknown_request_title))
                .setContentText(context.getString(R.string.notif_supervisor_unknown_request_text))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()
        )
    }

    private suspend fun processRegisterResponse(event: NostrEvent, accepted: Boolean) {
        val settings = prefs.settings.first()
        if (settings.supervisorPubkey.isEmpty() || event.pubkey != settings.supervisorPubkey) return
        val now = Instant.now().epochSecond
        if (accepted) {
            // An accept only matters while our request is pending. Honour it across the whole
            // catch-up window (this device may have been offline when it arrived), but never one
            // answering an earlier request, which would activate supervision nobody accepted.
            if (!settings.supervisionPending) return
            if (event.createdAt < settings.supervisionRequestedAt - SUPERVISION_RESPONSE_SKEW_S) return
            if (event.createdAt < now - MAX_CATCHUP_SECONDS) return
        } else if (event.createdAt < now - 86400) {
            // A replayed old decline would end supervision, so it keeps the tight window.
            return
        }
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try {
            json.decodeFromString<SupervisedRegisterResponsePayload>(plaintext)
        } catch (e: Exception) { return }
        val (_, pubHex) = keyManager.ensureKeypair()
        if (payload.devicePubkeyHex != pubHex) return

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigateTo", "settings")
        }
        val pi = PendingIntent.getActivity(
            context, "supervised_register_response".hashCode(), openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (accepted) {
            prefs.setSupervisionAccepted()
            val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
                .setSmallIcon(R.drawable.ic_notif_alert)
                .setContentTitle(context.getString(R.string.notif_supervised_active_title))
                .setContentText(context.getString(R.string.notif_supervised_active_text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            notificationManager.notify("supervised_register", NOTIF_ID_SUPERVISED_REGISTER, notification)
        } else {
            prefs.clearSupervisedMode()
            supervisedModeManager.reset()
            val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
                .setSmallIcon(R.drawable.ic_notif_alert)
                .setContentTitle(context.getString(R.string.notif_supervised_declined_title))
                .setContentText(context.getString(R.string.notif_supervised_declined_text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            notificationManager.notify("supervised_register", NOTIF_ID_SUPERVISED_REGISTER, notification)
        }
    }

    // ── Supervised-mode state changes (kinds 1061-1066) ─────────────────────────────

    /** Sign, encrypt and publish a supervised-mode control event to [toPubkey]. */
    private suspend fun sendSupervisedEvent(toPubkey: String, kind: Int, payload: String) {
        try {
            val (privHex, pubHex) = keyManager.ensureKeypair()
            val encrypted = crypto.nip44Encrypt(crypto.hexToBytes(privHex), toPubkey, payload)
            relayClient.publishEvent(
                NostrEvent.build(
                    privKeyHex = privHex,
                    pubKeyHex = pubHex,
                    kind = kind,
                    content = encrypted,
                    tags = listOf(listOf("p", toPubkey)),
                    crypto = crypto
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send supervised event kind=$kind to $toPubkey", e)
        }
    }

    /** Answer a supervised registration we already agreed to (isMySupervised): promote
     *  received location capability + missed-heartbeat alerts and reply in-band. Closes
     *  the supervisor-initiated invite loop (its 1062 accept step triggers the 1055
     *  register request that lands here) and re-answers after our process death. */
    private suspend fun autoAcceptRegistration(peer: PeerEntity) {
        val deviceId = peer.deviceId
        val cfg = sharingConfigDao.getForPeer(deviceId)
        if (cfg == null) {
            sharingConfigDao.upsert(
                com.locapeer.data.entity.PeerSharingConfig(
                    peerDeviceId = deviceId,
                    isMySupervised = true,
                    notifyOnMissedHeartbeat = true
                )
            )
        } else {
            sharingConfigDao.setIsMySupervised(deviceId, true)
            sharingConfigDao.setNotifyOnMissedHeartbeat(deviceId, true)
        }
        // Supervising a device implies receiving its location (promote role, keep SEND).
        val promotedRole = PeerEntity.roleWithReceive(peer.locationRole)
        if (promotedRole != peer.locationRole) {
            peerDao.upsertPeer(peer.copy(locationRole = promotedRole))
        }
        val payload = json.encodeToString(
            SupervisedRegisterResponsePayload(devicePubkeyHex = deviceId, accepted = true)
        )
        if (peer.relayUrl.isNotBlank()) relayClient.connect(peer.relayUrl)
        sendSupervisedEvent(deviceId, NostrEventKind.SUPERVISED_REGISTER_ACCEPT, payload)
        Log.d(TAG, "Auto-answered supervised registration from $deviceId")
    }

    /** Device side (kind 1061): a supervisor has asked to supervise this device. */
    private suspend fun processSupervisedInvite(event: NostrEvent) {
        if (event.createdAt < Instant.now().epochSecond - SUPERVISION_EVENT_MAX_AGE_S) return
        // Only a peer this device already knows can ask; invites from strangers are
        // dropped (registration as a stranger was never surfaced either).
        peerDao.getPeer(event.pubkey) ?: return
        // Supervision is one-to-one and cannot be changed while active or pending.
        if (prefs.settings.first().supervisedModeEnabled) return
        // Suppress relay retransmissions while the invite notification is still active.
        if (notificationManager.activeNotifications.any {
                it.id == NOTIF_ID_SUPERVISED_INVITE && it.tag == event.pubkey }) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try {
            json.decodeFromString<SupervisedInvitePayload>(plaintext)
        } catch (e: Exception) { return }
        val supervisorName = payload.supervisorName.take(MAX_PEER_DISPLAY_NAME_CHARS)
        val acceptIntent = Intent(context, SupervisedInviteReceiver::class.java).apply {
            setPackage(context.packageName)
            action = ACTION_SUPERVISED_INVITE_ACCEPT
            putExtra(EXTRA_REQUESTER_PUBKEY, event.pubkey)
            putExtra(EXTRA_REQUESTER_NAME, supervisorName)
        }
        val declineIntent = Intent(context, SupervisedInviteReceiver::class.java).apply {
            setPackage(context.packageName)
            action = ACTION_SUPERVISED_INVITE_DECLINE
            putExtra(EXTRA_REQUESTER_PUBKEY, event.pubkey)
            putExtra(EXTRA_REQUESTER_NAME, supervisorName)
        }
        val baseId = event.pubkey.hashCode() + NOTIF_ID_SUPERVISED_INVITE
        val acceptPi = PendingIntent.getBroadcast(
            context, baseId, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val declinePi = PendingIntent.getBroadcast(
            context, baseId + 1, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notificationManager.notify(
            event.pubkey,
            NOTIF_ID_SUPERVISED_INVITE,
            NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
                .setSmallIcon(R.drawable.ic_notif_alert)
                .setContentTitle(context.getString(R.string.notif_supervised_invite_title, supervisorName))
                .setContentText(context.getString(R.string.notif_supervised_invite_text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(false)
                .addAction(0, context.getString(R.string.notif_action_accept), acceptPi)
                .addAction(0, context.getString(R.string.common_decline), declinePi)
                .build()
        )
    }

    /** Supervisor side (kind 1062): answer to an invite we sent. */
    private suspend fun processSupervisedInviteResponse(event: NostrEvent) {
        if (event.createdAt < Instant.now().epochSecond - SUPERVISION_EVENT_MAX_AGE_S) return
        // Invite answers only exist in our supervisor role (device-side 1057 decline is
        // handled by processRegisterResponse, which requires OUR supervisor pubkey).
        if (prefs.settings.first().supervisorPubkey.isNotEmpty()) return
        peerDao.getPeer(event.pubkey) ?: return
        if (sharingConfigDao.getForPeer(event.pubkey)?.isMySupervised != true) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try {
            json.decodeFromString<SupervisedInviteResponsePayload>(plaintext)
        } catch (e: Exception) { return }
        if (!payload.devicePubkeyHex.equals(event.pubkey, ignoreCase = true)) return
        // Acceptance arrives through the 1055/1056 register handshake (auto-answered
        // above); a decline is the real state change here and releases the claim the
        // "Supervise" toggle made when it sent the invite.
        if (!payload.accepted) {
            sharingConfigDao.setIsMySupervised(event.pubkey, false)
            sharingConfigDao.setNotifyOnMissedHeartbeat(event.pubkey, false)
            Log.i(TAG, "Supervision invite declined by ${event.pubkey}")
        }
    }

    /** Device side (kind 1063): the supervisor released supervision without deleting us. */
    private suspend fun processSupervisedSupervisorEnd(event: NostrEvent) {
        if (event.createdAt < Instant.now().epochSecond - SUPERVISION_EVENT_MAX_AGE_S) return
        val settings = prefs.settings.first()
        if (settings.supervisorPubkey.isEmpty()) return
        if (!settings.supervisorPubkey.equals(event.pubkey, ignoreCase = true)) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), settings.supervisorPubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try {
            json.decodeFromString<SupervisedSupervisorEndPayload>(plaintext)
        } catch (e: Exception) { return }
        if (!payload.supervisorPubkeyHex.equals(event.pubkey, ignoreCase = true)) return
        // The other side decided; clear whatever state we had (pending or active) and
        // tell the user explicitly instead of leaving them to discover it in Settings.
        prefs.clearSupervisedMode()
        supervisedModeManager.reset()
        Log.i(TAG, "Supervised mode ended by the supervisor")
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, "supervised_ended".hashCode(), openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notificationManager.notify(
            "supervised_ended",
            NOTIF_ID_SUPERVISED_REGISTER,
            NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
                .setSmallIcon(R.drawable.ic_notif_alert)
                .setContentTitle(context.getString(R.string.notif_supervision_ended_title))
                .setContentText(context.getString(R.string.notif_supervision_ended_text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }

    /** Supervisor side (kind 1064): the supervised device asked to release supervision. */
    private suspend fun processSupervisedDisableRequest(event: NostrEvent) {
        // Same gating as unlock requests: fresh, known contact, explicitly supervised;
        // any other peer can send the kind but it never reaches the dialog.
        if (event.createdAt < Instant.now().epochSecond - 300) return
        peerDao.getPeer(event.pubkey) ?: return
        if (sharingConfigDao.getForPeer(event.pubkey)?.isMySupervised != true) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try {
            json.decodeFromString<SupervisedDisableRequestPayload>(plaintext)
        } catch (e: Exception) { return }
        if (!tryMarkApprovalDialogShown("disable:${event.pubkey}")) return
        supervisionApprovalManager.setPending(
            SupervisionApprovalManager.PendingRequest(
                fromPubkey = event.pubkey,
                deviceName = payload.deviceName.take(MAX_PEER_DISPLAY_NAME_CHARS),
                requestId = payload.requestId,
                type = SupervisionApprovalManager.RequestType.DISABLE
            )
        )
    }

    /** Device side (kind 1065): the supervisor answered our disable request. */
    private suspend fun processSupervisedDisableResponse(event: NostrEvent) {
        if (event.createdAt < Instant.now().epochSecond - 300) return
        val settings = prefs.settings.first()
        if (settings.supervisorPubkey.isEmpty() ||
            !settings.supervisorPubkey.equals(event.pubkey, ignoreCase = true)) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), settings.supervisorPubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try {
            json.decodeFromString<SupervisedDisableResponsePayload>(plaintext)
        } catch (e: Exception) { return }
        supervisedModeManager.handleDisableResponse(payload.requestId, payload.approved)
    }

    /** Supervisor side (kind 1066): this supervised device erased its own identity. */
    private suspend fun processSupervisedWipeNotice(event: NostrEvent) {
        if (event.createdAt < Instant.now().epochSecond - SUPERVISION_EVENT_MAX_AGE_S) return
        peerDao.getPeer(event.pubkey) ?: return
        if (sharingConfigDao.getForPeer(event.pubkey)?.isMySupervised != true) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try {
            json.decodeFromString<SupervisedWipePayload>(plaintext)
        } catch (e: Exception) { return }
        if (!payload.devicePubkeyHex.equals(event.pubkey, ignoreCase = true)) return
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, "supervised_wipe".hashCode(), openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notificationManager.notify(
            "supervised_wipe:${event.pubkey}",
            NOTIF_ID_SUPERVISED_REGISTER,
            NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
                .setSmallIcon(R.drawable.ic_notif_alert)
                .setContentTitle(context.getString(R.string.notif_supervised_wipe_title))
                .setContentText(context.getString(R.string.notif_supervised_wipe_text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }

    /** Supervisor side (kind 1067): the supervised device asked to add a new sharing
     *  destination. Same gating as unlock/disable requests - the requester must be an
     *  explicitly supervised contact; the dialog shows the PROPOSED CONTACT's name. */
    private suspend fun processContactAddRequest(event: NostrEvent) {
        if (event.createdAt < Instant.now().epochSecond - 300) return
        peerDao.getPeer(event.pubkey) ?: return
        if (sharingConfigDao.getForPeer(event.pubkey)?.isMySupervised != true) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try {
            json.decodeFromString<ContactAddRequestPayload>(plaintext)
        } catch (e: Exception) { return }
        if (!tryMarkApprovalDialogShown("contact:${event.pubkey}")) return
        supervisionApprovalManager.setPending(
            SupervisionApprovalManager.PendingRequest(
                fromPubkey = event.pubkey,
                // The dialog "deviceName" slot carries the proposed contact's bound name.
                deviceName = payload.contactName.take(MAX_PEER_DISPLAY_NAME_CHARS),
                requestId = payload.requestId,
                type = SupervisionApprovalManager.RequestType.CONTACT_ADD
            )
        )
    }

    /** Child side (kind 1068): the supervisor answered our contact-add request. */
    private suspend fun processContactAddResponse(event: NostrEvent) {
        if (event.createdAt < Instant.now().epochSecond - 300) return
        val settings = prefs.settings.first()
        if (settings.supervisorPubkey.isEmpty() ||
            !settings.supervisorPubkey.equals(event.pubkey, ignoreCase = true)) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), settings.supervisorPubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try {
            json.decodeFromString<ContactAddResponsePayload>(plaintext)
        } catch (e: Exception) { return }
        contactAddApprovalManager.handleResponse(payload.requestId, payload.approved)
    }

    /** Supervisor side (kind 1069): batched summary of settings changes the supervised
     *  device made while under an approved unlock, rendered as one notification. */
    private suspend fun processSettingsChangeSummary(event: NostrEvent) {
        if (event.createdAt < Instant.now().epochSecond - 300) return
        peerDao.getPeer(event.pubkey) ?: return
        if (sharingConfigDao.getForPeer(event.pubkey)?.isMySupervised != true) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try {
            json.decodeFromString<SettingsChangeSummaryPayload>(plaintext)
        } catch (e: Exception) { return }
        if (payload.entries.isEmpty()) return
        val lines = payload.entries.take(8).joinToString("\n") { entry ->
            val peer = entry.peerName
            val resId = when (entry.area) {
                SupervisionChangeReporter.Areas.PAUSE -> R.string.change_pause
                SupervisionChangeReporter.Areas.RESUME -> R.string.change_resume
                SupervisionChangeReporter.Areas.PRECISION -> R.string.change_precision
                SupervisionChangeReporter.Areas.ROLE -> R.string.change_role
                SupervisionChangeReporter.Areas.TEMP_SHARE_START -> R.string.change_temp_start
                SupervisionChangeReporter.Areas.TEMP_SHARE_END -> R.string.change_temp_end
                SupervisionChangeReporter.Areas.SOS_CONTACT -> R.string.change_sos
                SupervisionChangeReporter.Areas.GLOBAL_SCHEDULE -> R.string.change_schedule
                SupervisionChangeReporter.Areas.SCHEDULE_PEER -> R.string.change_schedule_peer
                else -> R.string.change_generic
            }
            if (peer != null) context.getString(resId, peer) else context.getString(R.string.change_generic)
        }
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, "supervised_changes".hashCode(), openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notificationManager.notify(
            event.pubkey,
            NOTIF_ID_SUPERVISED_CHANGE,
            NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
                .setSmallIcon(R.drawable.ic_notif_alert)
                .setContentTitle(context.getString(R.string.change_notif_title))
                .setContentText(lines)
                .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }

    private suspend fun sendDeliveryAck(toPubkey: String, deliveredEventId: String) {
        val (privHex, pubHex) = keyManager.ensureKeypair()
        val privBytes = crypto.hexToBytes(privHex)
        val payload = json.encodeToString(DeliveryAckPayload(deliveredEventId))
        val encrypted = crypto.nip44Encrypt(privBytes, toPubkey, payload)
        val event = NostrEvent.build(
            privKeyHex = privHex,
            pubKeyHex = pubHex,
            kind = NostrEventKind.DELIVERY_ACK,
            content = encrypted,
            tags = listOf(listOf("p", toPubkey)),
            crypto = crypto
        )
        relayClient.publishEvent(event)
    }

    /**
     * The mute/appearance gates for message notifications, shared by the circle and 1:1
     * paths. Messages even from muted conversations are always stored and read on screen;
     * only the system notification is suppressed. SOS never routes through here.
     */
    private suspend fun shouldSendAnyMessageNotification(): Boolean {
        val settings = prefs.settings.first()
        if (!settings.messageNotificationsEnabled) return false
        if (com.locapeer.util.QuietHours.isQuietNow(settings.quietStartMinute, settings.quietEndMinute)) return false
        return true
    }

    private suspend fun sendGroupMessageNotification(circleName: String, senderName: String, preview: String, circleId: String) {
        if (!shouldSendAnyMessageNotification()) return
        if (prefs.mutedConversationIds.first().contains(AppPreferences.circleMuteKey(circleId))) return
        // Deep-link straight into the circle chat (not the Messages list) so tapping a circle
        // message opens the group thread - where a reply routes through the circle fan-out to
        // every member - and never the 1:1 chat with the sender. "openCircle" carries the circle
        // id the same way "openChat" carries a peer id for 1:1 notifications.
        val intent = Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
            putExtra("navigateTo", "groupchat")
            putExtra("openCircle", circleId)
            putExtra("circleName", circleName)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context, circleId.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif_message)
            .setContentTitle(context.getString(R.string.group_notif_title, circleName))
            .setContentText(context.getString(R.string.group_notif_body, senderName, preview.take(80)))
            // Tag the notification as a circle so it reads as a group message, not a 1:1 from
            // someone named after the circle (whose title would just be the sender's name).
            .setSubText(context.getString(R.string.circles_title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            // Hide the sender/preview on a locked screen; the system shows a redacted form.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        notificationManager.notify(circleId, NOTIF_ID_MESSAGE, notification)
    }

    private suspend fun sendBackgroundMessageNotification(senderName: String, preview: String, peerId: String) {
        if (!shouldSendAnyMessageNotification()) return
        if (prefs.mutedConversationIds.first().contains(AppPreferences.peerMuteKey(peerId))) return
        val intent = Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
            putExtra("navigateTo", "chat")
            putExtra("openChat", peerId)
            putExtra("peerName", senderName)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(context, peerId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif_message)
            .setContentTitle(senderName)
            .setContentText(preview.take(80))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            // Hide the sender name/preview on a locked screen; the system shows a redacted form.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        notificationManager.notify(peerId, NOTIF_ID_MESSAGE, notification)
    }

    /**
     * Freshness gate for the irreversible peer commands (PEER_REMOVED, DELETE_MY_MESSAGES,
     * DELETE_MY_LOCATION), which - unlike the supervision handlers - carry out a
     * destructive local action with no undo. The signature proves the sender, but a
     * relay can redeliver an old signed event once it falls out of the 2,000-id dedup
     * cache (or after a reinstall), which would silently wipe a since-re-added contact
     * and their data. A command timestamped before we added this contact cannot be
     * legitimate; [CONTROL_CLOCK_SKEW_MS] absorbs honest inter-device clock skew so a
     * genuine command sent moments after the relationship formed still applies.
     */
    private fun isStalePeerCommand(event: NostrEvent, peer: PeerEntity): Boolean {
        val eventMs = event.createdAt * 1000L
        if (eventMs < peer.addedAt - CONTROL_CLOCK_SKEW_MS) {
            Log.w(TAG, "Ignoring stale peer command kind=${event.kind} from ${event.pubkey}: " +
                "event=$eventMs addedAt=${peer.addedAt}")
            return true
        }
        return false
    }

    private suspend fun processPeerRemoved(event: NostrEvent) {
        // Only act if we know this peer - ignore unknown senders
        val peer = peerDao.getPeer(event.pubkey) ?: return
        if (isStalePeerCommand(event, peer)) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        try {
            json.decodeFromString<PeerRemovedPayload>(plaintext)
        } catch (e: Exception) { return }
        discardPendingHeartbeats(event.pubkey)
        peerManager.handleRemovalByPeer(event.pubkey)
    }

    private suspend fun processDeleteMyMessages(event: NostrEvent) {
        val peer = peerDao.getPeer(event.pubkey) ?: return
        if (isStalePeerCommand(event, peer)) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        peerManager.handleDeleteMyMessages(event.pubkey)
    }

    private suspend fun processDeleteMyLocation(event: NostrEvent) {
        val peer = peerDao.getPeer(event.pubkey) ?: return
        if (isStalePeerCommand(event, peer)) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        discardPendingHeartbeats(event.pubkey)
        peerManager.handleDeleteMyLocation(event.pubkey)
    }

    /**
     * A member left one of our circles: drop them from its membership so we stop fanning messages
     * to them (the reduced list propagates to the rest of the circle on our next message). Only
     * acts for a circle WE created, and only removes the signed sender, so a contact can neither
     * evict a third party nor alter a circle they don't own.
     */
    private suspend fun processCircleLeave(event: NostrEvent) {
        peerDao.getPeer(event.pubkey) ?: return  // known contacts only
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try {
            json.decodeFromString<com.locapeer.circles.CircleLeavePayload>(plaintext)
        } catch (e: Exception) { return }
        val circle = circleDao.getCircle(payload.gid) ?: return
        val (_, myPub) = keyManager.ensureKeypair()
        if (circle.creatorPubkey != myPub) return
        circleDao.removeMember(payload.gid, event.pubkey)
        Log.d(TAG, "Removed ${event.pubkey} from circle ${payload.gid} after they left")
    }

    private suspend fun processTrackingAlert(event: NostrEvent) {
        val settings = prefs.settings.first()
        if (!settings.notifyOnTrackingAlerts) return
        // Same gates as the local geofence/proximity/missed-location alerts: the global
        // alert switch and quiet hours (Settings -> Privacy).
        if (!settings.alertNotificationsEnabled) return
        if (com.locapeer.util.QuietHours.isQuietNow(settings.quietStartMinute, settings.quietEndMinute)) return

        peerDao.getPeer(event.pubkey) ?: return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try { json.decodeFromString<TrackingAlertPayload>(plaintext) } catch (e: Exception) { return }

        val title = context.getString(R.string.notif_tracking_alert_title)
        val message = when (payload.type) {
            "PROXIMITY" -> context.getString(R.string.notif_tracking_proximity, payload.triggerName.take(MAX_PEER_DISPLAY_NAME_CHARS))
            "GEOFENCE" -> context.getString(
                R.string.notif_tracking_geofence,
                payload.triggerName.take(MAX_PEER_DISPLAY_NAME_CHARS),
                payload.alertName?.take(MAX_CIRCLE_NAME_CHARS)
            )
            else -> context.getString(R.string.notif_tracking_generic, payload.triggerName.take(MAX_PEER_DISPLAY_NAME_CHARS))
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pi = PendingIntent.getActivity(context, NOTIF_ID_TRACKING_ALERT, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notif_location)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            // Names in the body are hidden on a locked screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        notificationManager.notify(event.pubkey, NOTIF_ID_TRACKING_ALERT, notification)
    }

    private suspend fun processTrackRequest(event: NostrEvent) {
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt track request from ${event.pubkey}", e)
            return
        }
        val payload = try {
            json.decodeFromString<TrackRequestPayload>(plaintext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode track request payload", e)
            return
        }

        // Bind the payload's claimed identity to the signing key, mirroring processTrackAccept.
        // The pending-request row, the review screen and the eventual accept are all keyed on
        // senderPublicKeyHex, so an unchecked mismatch would let the signer forge a request
        // "from" any other pubkey - and attach their own relay URL to that identity.
        if (payload.senderPublicKeyHex != event.pubkey) {
            Log.w(TAG, "Track request identity mismatch: payload=${payload.senderPublicKeyHex.take(16)} signer=${event.pubkey}")
            return
        }

        // Bound the sender-controlled strings before they are persisted or shown. Without a
        // cap a stranger could store a near-1 MB "name" per request, inflating the table and
        // overflowing the 2 MB CursorWindow when the pending-requests list loads.
        val senderName = payload.senderDisplayName.take(MAX_PEER_DISPLAY_NAME_CHARS)
        val senderRelay = payload.senderRelayUrl.take(MAX_RELAY_URL_CHARS)

        Log.i(TAG, "Received track request from $senderName (${event.pubkey})")

        val existing = peerDao.getPeer(event.pubkey)

        // A receive-only relationship is not consent to send our location back.
        // All new sharing grants must go through the existing review/accept screen.
        // A stranger cannot bypass the flood cap by claiming a role change either.
        if (!shouldReviewTrackRequest(existing?.locationRole, payload.isRoleChange)) return

        // Flood cap: a stranger cycling keys sends signed-but-unsolicited new requests from
        // ever-changing pubkeys. Once the backlog is full, drop further NEW ones (a repeat
        // from an already-queued pubkey just REPLACEs its row, so it's exempt; role changes
        // come from existing contacts and are exempt too). The user clears the queue to
        // resume receiving new requests; the 30-day expiry drains it on its own.
        if (!payload.isRoleChange &&
            pendingRequestDao.getByPubkey(event.pubkey) == null &&
            pendingRequestDao.count() >= MAX_PENDING_TRACK_REQUESTS
        ) {
            Log.w(TAG, "Dropping track request from ${event.pubkey}: pending-request cap reached")
            return
        }

        val notifId = event.pubkey.hashCode() + 20000
        // Suppress relay retransmissions for new requests only - role changes are always shown
        if (!payload.isRoleChange &&
            notificationManager.activeNotifications.any { it.id == NOTIF_ID_TRACK_REQUEST && it.tag == event.pubkey }
        ) return

        pendingRequestDao.upsert(
            PendingRequestEntity(
                senderPubkey = payload.senderPublicKeyHex,
                senderName = senderName,
                senderRelayUrl = senderRelay,
                isRoleChange = payload.isRoleChange,
                requestedRole = payload.requestedRole
            )
        )
        val reviewIntent = Intent(context, com.locapeer.MainActivity::class.java).apply {
            setPackage(context.packageName)
            // Unique data URI: extras don't take part in PendingIntent matching
            // (Intent.filterEquals), and this request code (pubkey hash + 20000) is also the
            // one ProximityEngine used for the same contact, so the two notifications shared
            // one PendingIntent and FLAG_UPDATE_CURRENT made one open the other's screen.
            data = "locapeer-notif://track-request/${event.pubkey}".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("navigateTo", "share-request")
            putExtra(EXTRA_SENDER_PUBKEY, payload.senderPublicKeyHex)
            putExtra(EXTRA_SENDER_NAME, senderName)
            putExtra(EXTRA_SENDER_RELAY, senderRelay)
            putExtra(EXTRA_IS_ROLE_CHANGE, payload.isRoleChange)
            if (payload.requestedRole != null) putExtra(EXTRA_REQUESTED_ROLE, payload.requestedRole)
            putExtra(com.locapeer.EXTRA_CANCEL_NOTIF_TAG, event.pubkey)
            putExtra(com.locapeer.EXTRA_CANCEL_NOTIF_ID, NOTIF_ID_TRACK_REQUEST)
            putExtra(com.locapeer.EXTRA_CANCEL_NOTIF_TOKEN, com.locapeer.util.NotificationCancelToken.get(context))
        }
        val declineIntent = Intent(context, TrackRequestReceiver::class.java).apply {
            setPackage(context.packageName)
            action = ACTION_TRACK_DECLINE
            putExtra(EXTRA_SENDER_PUBKEY, payload.senderPublicKeyHex)
            putExtra(EXTRA_SENDER_NAME, senderName)
            putExtra(EXTRA_SENDER_RELAY, senderRelay)
            putExtra(EXTRA_IS_ROLE_CHANGE, payload.isRoleChange)
        }
        val reviewPi = PendingIntent.getActivity(context, notifId, reviewIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val declinePi = PendingIntent.getBroadcast(context, notifId + 1, declineIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val senderLabel = senderName.take(40).ifBlank { context.getString(R.string.notif_someone) }
        val notifTitle = if (payload.isRoleChange) context.getString(R.string.notif_track_update_title, senderLabel)
                         else context.getString(R.string.notif_track_new_title, senderLabel)
        val requestedRoleLabel = when (payload.requestedRole) {
            PeerEntity.ROLE_SEND_RECEIVE -> context.getString(R.string.notif_role_send_receive)
            PeerEntity.ROLE_SEND -> context.getString(R.string.notif_role_send)
            PeerEntity.ROLE_RECEIVE -> context.getString(R.string.notif_role_receive)
            PeerEntity.ROLE_NONE -> context.getString(R.string.notif_role_none)
            else -> null
        }
        val notifBody = when {
            payload.isRoleChange && requestedRoleLabel != null ->
                context.getString(R.string.notif_track_requesting, senderLabel, requestedRoleLabel)
            payload.isRoleChange ->
                context.getString(R.string.notif_track_update_body, senderLabel)
            else ->
                context.getString(R.string.notif_track_new_body, senderLabel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notif_message)
            .setContentTitle(notifTitle)
            .setContentText(notifBody)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(reviewPi)
            .addAction(0, context.getString(R.string.common_review), reviewPi)
            .addAction(0, context.getString(R.string.common_decline), declinePi)
            .build()
        notificationManager.notify(event.pubkey, NOTIF_ID_TRACK_REQUEST, notification)
    }

    private suspend fun processTrackAccept(event: NostrEvent) {
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt track accept from ${event.pubkey}", e)
            return
        }
        val payload = try {
            json.decodeFromString<com.locapeer.invite.TrackAcceptPayload>(plaintext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode track accept payload", e)
            return
        }

        // Bind the payload's claimed identity to the signing key. The accept only ever
        // touches the signer's peer row, so a payload advertising a different pubkey is
        // either malformed or an attempt to redirect the relationship - reject it.
        if (payload.acceptorPublicKeyHex != event.pubkey) {
            Log.w(TAG, "Track accept identity mismatch: payload=${payload.acceptorPublicKeyHex} signer=${event.pubkey}")
            return
        }

        // A TRACK_ACCEPT is only meaningful as a reply to a request we sent, and every
        // outgoing request is preceded by a local peer row (scanning adds the peer
        // optimistically; role-change requests operate on an existing contact). With no
        // local record we never asked this key - ignoring the accept prevents a stranger
        // from unilaterally self-adding as a location recipient.
        val existingPeer = peerDao.getPeer(event.pubkey) ?: run {
            Log.w(TAG, "Ignoring unsolicited track accept from ${event.pubkey}")
            return
        }

        Log.i(TAG, "Received track acceptance from ${payload.acceptorDisplayName} (${event.pubkey})")
        // e.g. if they accepted RECEIVE (they receive from us), we become SEND (we send to them) -
        // but only if we were already sending; see roleAfterTrackAccept.
        val newLocationRole = roleAfterTrackAccept(existingPeer.locationRole, payload.acceptedRole)

        // Keep the known relay when the payload's is not a usable wss:// URL; the relay client
        // would refuse it anyway, leaving the contact with no reachable relay on record.
        val relayUrl = payload.acceptorRelayUrl.takeIf { it.startsWith("wss://", ignoreCase = true) }
            ?: existingPeer.relayUrl
        peerDao.upsertPeer(existingPeer.copy(relayUrl = relayUrl, locationRole = newLocationRole))
        relayClient.connect(relayUrl)
        // Only notify if this is a new connection or a role change - not on catch-up re-delivery.
        // Show the locally-stored contact name, not the payload's (remote-controlled) name, so a
        // peer can't spoof the name rendered in the system notification.
        val notifyName = existingPeer.displayName.ifBlank { payload.acceptorDisplayName }
        if (existingPeer.locationRole != newLocationRole) {
            sendAcceptanceNotification(event.pubkey, notifyName)
        }
        Log.i(TAG, "$notifyName accepted your track request (LocationRole: $newLocationRole)")
    }

    private suspend fun processTrackDecline(event: NostrEvent) {
        // Only act if we actually sent a request to this peer (peer exists optimistically)
        val peer = peerDao.getPeer(event.pubkey) ?: return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt track decline from ${event.pubkey}", e)
            return
        }
        val payload = try {
            json.decodeFromString<com.locapeer.invite.TrackDeclinePayload>(plaintext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode track decline payload", e)
            return
        }

        Log.i(TAG, "Received track decline from ${payload.declinerDisplayName} (${event.pubkey})")
        // For new-request declines, remove the optimistically-added peer entry.
        // Role-change declines should leave the existing contact relationship intact.
        if (!payload.isRoleChange) {
            // A removal is irreversible, so apply the same replay guard as PEER_REMOVED: an
            // old decline redelivered by a relay must not wipe a since-re-added contact.
            if (isStalePeerCommand(event, peer)) return
            discardPendingHeartbeats(event.pubkey)
            peerManager.handleRemovalByPeer(event.pubkey)
        }
        // Prefer the locally stored name over the payload's remote-controlled one, as the
        // accept path does, so a peer can't choose the name shown in the notification.
        sendDeclineNotification(event.pubkey, peer.displayName.ifBlank { payload.declinerDisplayName })
    }

    private fun sendAcceptanceNotification(pubkey: String, name: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
            putExtra("navigateTo", "contacts")
        }
        val notifId = pubkey.hashCode() + 30000
        val pi = PendingIntent.getActivity(context, notifId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notif_message)
            .setContentTitle(context.getString(R.string.notif_contact_connected_title))
            .setContentText(context.getString(R.string.notif_contact_connected_text, name))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(pubkey, NOTIF_ID_ACCEPT, notification)
    }

    private fun sendDeclineNotification(pubkey: String, name: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
            putExtra("navigateTo", "contacts")
        }
        val notifId = pubkey.hashCode() + 40000
        val pi = PendingIntent.getActivity(context, notifId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notif_message)
            .setContentTitle(context.getString(R.string.notif_request_declined_title))
            .setContentText(context.getString(R.string.notif_request_declined_text, name))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(pubkey, NOTIF_ID_DECLINE, notification)
    }

    private fun createAlertChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_ALERTS,
            context.getString(R.string.channel_name_alerts),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_desc_alerts)
            // Redact contact names / coordinates on the device lock screen by default.
            // Takes effect on new installs (Android ignores channel-property changes after
            // creation); the per-notification setVisibility on the sensitive alerts covers
            // existing installs too. A locked phone showing a peer's location or an SOS's
            // coordinates is exactly what a location app must not do.
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createMessageChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_MESSAGES,
            context.getString(R.string.channel_name_messages),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_desc_messages)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        notificationManager.createNotificationChannel(channel)
    }
}
