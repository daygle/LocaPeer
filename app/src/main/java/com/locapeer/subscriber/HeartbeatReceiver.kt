package com.locapeer.subscriber

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
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
import com.locapeer.supervised.ACTION_SUPERVISED_REGISTER_ACCEPT
import com.locapeer.supervised.ACTION_SUPERVISED_REGISTER_DECLINE
import com.locapeer.supervised.EXTRA_REQUESTER_NAME
import com.locapeer.supervised.EXTRA_REQUESTER_PUBKEY
import com.locapeer.supervised.EXTRA_REQUESTER_RELAY
import com.locapeer.supervised.SupervisedModeManager
import com.locapeer.supervised.SupervisedRegisterReceiver
import com.locapeer.supervised.SupervisionApprovalManager
import com.locapeer.supervised.SupervisedRegisterPayload
import com.locapeer.supervised.SupervisedRegisterResponsePayload
import com.locapeer.supervised.UnlockRequestPayload
import com.locapeer.supervised.UnlockResponsePayload
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrFilter
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
// Side effects (SOS alarm, geofence/proximity alerts) only fire for heartbeats this
// recent; older ones are history backfill and must not raise stale alerts.
private const val FRESH_HEARTBEAT_MS = 10 * 60_000L
// Throttle for persisting the heartbeat sync baseline as live events arrive.
private const val HB_EPOCH_SAVE_INTERVAL_S = 300L

// Notification ids are paired with a per-peer tag (notify(tag, id, ...)) instead of folding the
// peer/pubkey hashCode into the id, since two different peers' hashCodes can collide and silently
// overwrite each other's notification.
private const val NOTIF_ID_SOS = 1
private const val NOTIF_ID_MESSAGE = 10000
const val NOTIF_ID_TRACK_REQUEST = 20000
private const val NOTIF_ID_ACCEPT = 30000
private const val NOTIF_ID_DECLINE = 40000
private const val NOTIF_ID_TRACKING_ALERT = 80000
const val NOTIF_ID_SUPERVISED_REGISTER = 70000

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
    NostrEventKind.TRACKING_ALERT,
    NostrEventKind.CIRCLE_LEAVE
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
    private val peerManager: PeerManager,
    private val trackResponseSender: com.locapeer.invite.TrackResponseSender
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    /** Highest heartbeat event epoch persisted as the catch-up baseline (throttled). */
    @Volatile private var lastPersistedHbEpoch = 0L

    /**
     * In-memory mirror of [AppPreferences.leftCircleIds], kept fresh by a collector started in
     * [start]. Read on the per-message hot path in [processDmInBackground] to suppress circles the
     * user left, so that path never touches DataStore.
     */
    @Volatile private var leftCircleIds: Set<String> = emptySet()

    // Buffer for batched heartbeat inserts (see HB_BATCH_* constants). Guarded by
    // batchMutex. pendingKeys mirrors the buffer's (deviceId, timestamp) pairs so a
    // duplicate arriving from a second relay within the same flush window is dropped
    // before it can become a second row (autoGenerated ids defeat REPLACE-based dedup).
    private val batchMutex = Mutex()
    private val pendingHeartbeats = mutableListOf<HeartbeatEntity>()
    private val pendingKeys = HashSet<String>()
    private val pendingRetentionDays = HashMap<String, Int>()
    private var flushJob: Job? = null

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
                        NostrEventKind.TRACK_REQUEST,
                        NostrEventKind.TRACK_ACCEPT,
                        NostrEventKind.TRACK_DECLINE,
                        NostrEventKind.PEER_REMOVED,
                        NostrEventKind.DELETE_MY_MESSAGES,
                        NostrEventKind.DELETE_MY_LOCATION,
                        NostrEventKind.TRACKING_ALERT,
                        NostrEventKind.CIRCLE_LEAVE
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
                    NostrEventKind.TRACK_REQUEST -> scope.launch { processTrackRequest(event) }
                    NostrEventKind.TRACK_ACCEPT -> scope.launch { processTrackAccept(event) }
                    NostrEventKind.TRACK_DECLINE -> scope.launch { processTrackDecline(event) }
                    NostrEventKind.PEER_REMOVED -> scope.launch { processPeerRemoved(event) }
                    NostrEventKind.DELETE_MY_MESSAGES -> scope.launch { processDeleteMyMessages(event) }
                    NostrEventKind.DELETE_MY_LOCATION -> scope.launch { processDeleteMyLocation(event) }
                    NostrEventKind.TRACKING_ALERT -> scope.launch { processTrackingAlert(event) }
                    NostrEventKind.CIRCLE_LEAVE -> scope.launch { processCircleLeave(event) }
                }
              } catch (e: Exception) {
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
        heartbeatDao.deleteOlderThanForDevice(payload.deviceId, payload.deleteOlderThanMs)
        Log.d(TAG, "Purged ${payload.deviceId} heartbeats before ${payload.deleteOlderThanMs}ms")
    }

    private suspend fun processEvent(event: NostrEvent) {
        if (event.kind != NostrEventKind.HEARTBEAT && event.kind != NostrEventKind.SOS_ALERT) return
        val broadcaster = peerDao.getPeer(event.pubkey) ?: return

        // Ensure peer is unarchived if they send an SOS or update their heartbeat
        // so they are visible in the contacts/messaging UI during active tracking
        if (event.kind == NostrEventKind.SOS_ALERT) {
            peerDao.unarchive(event.pubkey)
        }

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
            // Use event.pubkey (canonical 64-char Nostr pubkey) so the heartbeat always
            // matches the peer row regardless of what the payload.deviceId contains.
            val canonicalDeviceId = event.pubkey
            val timestampMs = Instant.parse(payload.timestamp).toEpochMilli()
            // Catch-up subscriptions can replay events already stored (relay overlap,
            // dedupe-cache eviction across restarts) - never insert the same ping twice.
            // Also drops duplicates still sitting in the un-flushed batch buffer.
            if (isDuplicateHeartbeat(canonicalDeviceId, timestampMs)) {
                advanceHeartbeatBaseline(event.createdAt)
                return
            }
            val entity = HeartbeatEntity(
                deviceId = canonicalDeviceId,
                displayName = payload.displayName,
                timestamp = timestampMs,
                lat = payload.lat,
                lng = payload.lng,
                accuracy = payload.accuracy,
                battery = payload.battery,
                motionState = payload.motionState,
                isSos = payload.isSos,
                pinColor = payload.pinColor,
                speed = payload.speed,
                bearing = payload.bearing,
                altitude = payload.altitude,
                expectedIntervalSeconds = payload.expectedIntervalSeconds
            )
            // Buffer the insert; the batch flusher persists it transactionally so a
            // catch-up burst produces a handful of Room invalidations, not hundreds.
            enqueueHeartbeat(entity, payload.retentionDays)
            // Replayed (old) heartbeats are history backfill: recording them is correct,
            // but alerting on them is not - the situation they describe is long over.
            // The bulk of a catch-up burst is stale, so this side-effect path (and its
            // extra DB read for the previous position) is skipped for the common case.
            val isFresh = System.currentTimeMillis() - timestampMs < FRESH_HEARTBEAT_MS
            if (isFresh) {
                val prevHeartbeat = heartbeatDao.getLatestHeartbeat(canonicalDeviceId)
                if (payload.isSos) sendSosNotification(broadcaster.displayName, payload)
                geofenceEngine.evaluate(entity, prevHeartbeat)
                proximityEngine.evaluate(entity)
            }
            advanceHeartbeatBaseline(event.createdAt)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process heartbeat", e)
        }
    }

    /**
     * Persist the heartbeat sync baseline (throttled) so the next session's catch-up
     * starts near where this one left off instead of replaying the whole window.
     * Only near-live events advance it: relays replay stored events newest-first, so
     * taking the baseline from a replayed event could skip older ones still streaming
     * in if the app dies mid-replay. Re-replaying is safe - inserts are deduped.
     */
    private suspend fun advanceHeartbeatBaseline(eventEpoch: Long) {
        val nowEpoch = System.currentTimeMillis() / 1000
        if (nowEpoch - eventEpoch > 3600) return
        if (eventEpoch > lastPersistedHbEpoch + HB_EPOCH_SAVE_INTERVAL_S) {
            lastPersistedHbEpoch = eventEpoch
            prefs.setLastHeartbeatSubEpoch(eventEpoch)
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
     * [isDuplicateHeartbeat] first. retentionDays (>0) records the sender's retention so
     * the flush can prune old history for that device in the same transaction window.
     */
    private suspend fun enqueueHeartbeat(entity: HeartbeatEntity, retentionDays: Int) {
        val flushNow = batchMutex.withLock {
            pendingHeartbeats.add(entity)
            pendingKeys.add(hbKey(entity.deviceId, entity.timestamp))
            if (retentionDays > 0) pendingRetentionDays[entity.deviceId] = retentionDays
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
        val batch: List<HeartbeatEntity>
        val retention: Map<String, Int>
        batchMutex.withLock {
            if (!fromTimer) flushJob?.cancel()
            flushJob = null
            if (pendingHeartbeats.isEmpty()) return
            batch = pendingHeartbeats.toList()
            retention = pendingRetentionDays.toMap()
            pendingHeartbeats.clear()
            pendingKeys.clear()
            pendingRetentionDays.clear()
        }
        try {
            heartbeatDao.insertAll(batch)
            if (retention.isNotEmpty()) {
                val now = System.currentTimeMillis()
                retention.forEach { (deviceId, days) ->
                    // days.toLong() first: days * 24 * 3600 would overflow Int for large,
                    // peer-supplied retention values before the trailing 1000L widened it.
                    heartbeatDao.deleteOlderThanForDevice(deviceId, now - days.toLong() * 24 * 3600 * 1000)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush heartbeat batch (${batch.size})", e)
        }
    }

    private fun sendSosNotification(name: String, payload: HeartbeatPayload) {
        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notif_alert)
            .setContentTitle(context.getString(R.string.notif_sos_title, name))
            .setContentText(context.getString(R.string.notif_sos_text, name, payload.lat.toString(), payload.lng.toString()))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(payload.deviceId, NOTIF_ID_SOS, notification)
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
        if (messageDao.getByNostrEventId(event.id) != null) return
        val sender = peerDao.getPeer(event.pubkey) ?: return
        val isBlocked = !sender.messagingEnabled
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }

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
            if (knownCircle != null && knownCircle.creatorPubkey.isNotBlank() &&
                event.pubkey != knownCircle.creatorPubkey &&
                !circleDao.getMemberPubkeys(group.gid).contains(event.pubkey)
            ) {
                Log.w(TAG, "Dropping circle message for ${group.gid} from non-member ${event.pubkey}")
                return
            }
            circleDao.materialiseFromRemote(
                circleId = group.gid,
                name = group.gname,
                creatorPubkey = group.creator,
                senderPubkey = event.pubkey,
                // Never store our own pubkey as a member row: locally created circles keep only
                // the OTHER participants, so counts and the edit screen stay consistent across
                // created and materialised circles. Send-side fan-out re-appends self on the wire.
                memberPubkeys = group.members.filter { it != myPubHex }
            )
            // Auto-unarchive on receive, mirroring the 1:1 peer path: a fresh message pulls the
            // circle back onto the Circles tab, but a late-arriving/queued message that predates
            // an explicit archive action doesn't silently undo it.
            circleDao.getCircle(group.gid)?.let { circleRow ->
                if (!circleRow.isArchived || event.createdAt * 1000L > circleRow.archivedAt) {
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
                        timestamp = event.createdAt * 1000L,
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
                    timestamp = event.createdAt * 1000L,
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
                timestamp = event.createdAt * 1000L,
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
            timestamp = event.createdAt * 1000L,
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

    private suspend fun processReadReceiptInBackground(event: NostrEvent) {
        peerDao.getPeer(event.pubkey) ?: return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val receipt = try { json.decodeFromString<ReadReceiptPayload>(plaintext) } catch (e: Exception) { return }
        receipt.eventIds.forEach { eventId ->
            messageDao.updateDeliveryStateByNostrEventIdForPeer(eventId, event.pubkey, DeliveryState.READ.name)
        }
    }

    private suspend fun processDeliveryAckInBackground(event: NostrEvent) {
        peerDao.getPeer(event.pubkey) ?: return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try { json.decodeFromString<DeliveryAckPayload>(plaintext) } catch (e: Exception) { return }
        messageDao.updateDeliveryStateByNostrEventIdForPeer(payload.eventId, event.pubkey, DeliveryState.DELIVERED.name)
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
        supervisionApprovalManager.setPending(
            SupervisionApprovalManager.PendingRequest(
                fromPubkey = event.pubkey,
                deviceName = payload.deviceName,
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
        peerDao.getPeer(event.pubkey) ?: return
        // Don't re-notify if the supervisor already accepted this device
        if (sharingConfigDao.getForPeer(event.pubkey)?.isMySupervised == true) return
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
            action = ACTION_SUPERVISED_REGISTER_ACCEPT
            putExtra(EXTRA_REQUESTER_PUBKEY, event.pubkey)
            putExtra(EXTRA_REQUESTER_NAME, payload.deviceName)
            putExtra(EXTRA_REQUESTER_RELAY, payload.deviceRelayUrl)
        }
        val declineIntent = Intent(context, SupervisedRegisterReceiver::class.java).apply {
            action = ACTION_SUPERVISED_REGISTER_DECLINE
            putExtra(EXTRA_REQUESTER_PUBKEY, event.pubkey)
            putExtra(EXTRA_REQUESTER_NAME, payload.deviceName)
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
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigateTo", "contacts")
            putExtra(com.locapeer.EXTRA_CANCEL_NOTIF_TAG, event.pubkey)
            putExtra(com.locapeer.EXTRA_CANCEL_NOTIF_ID, NOTIF_ID_SUPERVISED_REGISTER)
        }
        val openAppPi = PendingIntent.getActivity(
            context, notifId + 2, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notif_alert)
            .setContentTitle(context.getString(R.string.notif_supervisor_request_title, payload.deviceName))
            .setContentText(context.getString(R.string.notif_supervisor_request_text))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.notif_supervisor_request_big, payload.deviceName)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppPi)
            .addAction(0, context.getString(R.string.notif_action_accept), acceptPi)
            .addAction(0, context.getString(R.string.common_decline), declinePi)
            .setAutoCancel(false)
            .build()
        notificationManager.notify(event.pubkey, NOTIF_ID_SUPERVISED_REGISTER, notification)
    }

    private suspend fun processRegisterResponse(event: NostrEvent, accepted: Boolean) {
        if (event.createdAt < Instant.now().epochSecond - 86400) return
        val settings = prefs.settings.first()
        if (settings.supervisorPubkey.isEmpty() || event.pubkey != settings.supervisorPubkey) return
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
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigateTo", "settings")
        }
        val pi = PendingIntent.getActivity(
            context, "supervised_register_response".hashCode(), openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (accepted) {
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

    private fun sendGroupMessageNotification(circleName: String, senderName: String, preview: String, circleId: String) {
        // Deep-link straight into the circle chat (not the Messages list) so tapping a circle
        // message opens the group thread - where a reply routes through the circle fan-out to
        // every member - and never the 1:1 chat with the sender. "openCircle" carries the circle
        // id the same way "openChat" carries a peer id for 1:1 notifications.
        val intent = Intent(context, MainActivity::class.java).apply {
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
            .build()
        notificationManager.notify(circleId, NOTIF_ID_MESSAGE, notification)
    }

    private fun sendBackgroundMessageNotification(senderName: String, preview: String, peerId: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
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
            .build()
        notificationManager.notify(peerId, NOTIF_ID_MESSAGE, notification)
    }

    private suspend fun processPeerRemoved(event: NostrEvent) {
        // Only act if we know this peer - ignore unknown senders
        peerDao.getPeer(event.pubkey) ?: return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        try {
            json.decodeFromString<PeerRemovedPayload>(plaintext)
        } catch (e: Exception) { return }
        peerManager.handleRemovalByPeer(event.pubkey)
    }

    private suspend fun processDeleteMyMessages(event: NostrEvent) {
        peerDao.getPeer(event.pubkey) ?: return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        peerManager.handleDeleteMyMessages(event.pubkey)
    }

    private suspend fun processDeleteMyLocation(event: NostrEvent) {
        peerDao.getPeer(event.pubkey) ?: return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
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

        peerDao.getPeer(event.pubkey) ?: return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try { json.decodeFromString<TrackingAlertPayload>(plaintext) } catch (e: Exception) { return }

        val title = context.getString(R.string.notif_tracking_alert_title)
        val message = when (payload.type) {
            "PROXIMITY" -> context.getString(R.string.notif_tracking_proximity, payload.triggerName)
            "GEOFENCE" -> context.getString(R.string.notif_tracking_geofence, payload.triggerName, payload.alertName)
            else -> context.getString(R.string.notif_tracking_generic, payload.triggerName)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
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

        Log.i(TAG, "Received track request from ${payload.senderDisplayName} (${event.pubkey})")

        val existing = peerDao.getPeer(event.pubkey)

        if (!payload.isRoleChange) {
            // For new requests only: skip if already sharing; auto-promote RECEIVE → SEND_RECEIVE
            if (existing?.locationRole == PeerEntity.ROLE_SEND || existing?.locationRole == PeerEntity.ROLE_SEND_RECEIVE) return
            if (existing != null && existing.locationRole == PeerEntity.ROLE_RECEIVE) {
                peerDao.upsertPeer(existing.copy(locationRole = PeerEntity.ROLE_SEND_RECEIVE))
                try {
                    trackResponseSender.sendAccept(
                        payload.senderPublicKeyHex, payload.senderRelayUrl, PeerEntity.ROLE_SEND_RECEIVE
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send track accept response", e)
                }
                Log.i(TAG, "Promoted ${existing.displayName} to SEND_RECEIVE")
                return
            }
        }

        val notifId = event.pubkey.hashCode() + 20000
        // Suppress relay retransmissions for new requests only - role changes are always shown
        if (!payload.isRoleChange &&
            notificationManager.activeNotifications.any { it.id == NOTIF_ID_TRACK_REQUEST && it.tag == event.pubkey }
        ) return

        pendingRequestDao.upsert(
            PendingRequestEntity(
                senderPubkey = payload.senderPublicKeyHex,
                senderName = payload.senderDisplayName,
                senderRelayUrl = payload.senderRelayUrl,
                isRoleChange = payload.isRoleChange,
                requestedRole = payload.requestedRole
            )
        )
        val reviewIntent = Intent(context, com.locapeer.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("navigateTo", "share-request")
            putExtra(EXTRA_SENDER_PUBKEY, payload.senderPublicKeyHex)
            putExtra(EXTRA_SENDER_NAME, payload.senderDisplayName)
            putExtra(EXTRA_SENDER_RELAY, payload.senderRelayUrl)
            putExtra(EXTRA_IS_ROLE_CHANGE, payload.isRoleChange)
            if (payload.requestedRole != null) putExtra(EXTRA_REQUESTED_ROLE, payload.requestedRole)
            putExtra(com.locapeer.EXTRA_CANCEL_NOTIF_TAG, event.pubkey)
            putExtra(com.locapeer.EXTRA_CANCEL_NOTIF_ID, NOTIF_ID_TRACK_REQUEST)
        }
        val declineIntent = Intent(context, TrackRequestReceiver::class.java).apply {
            action = ACTION_TRACK_DECLINE
            putExtra(EXTRA_SENDER_PUBKEY, payload.senderPublicKeyHex)
            putExtra(EXTRA_SENDER_NAME, payload.senderDisplayName)
            putExtra(EXTRA_SENDER_RELAY, payload.senderRelayUrl)
            putExtra(EXTRA_IS_ROLE_CHANGE, payload.isRoleChange)
        }
        val reviewPi = PendingIntent.getActivity(context, notifId, reviewIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val declinePi = PendingIntent.getBroadcast(context, notifId + 1, declineIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val senderLabel = payload.senderDisplayName.take(40).ifBlank { context.getString(R.string.notif_someone) }
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
        // e.g. if they accepted RECEIVE (they receive from us), we become SEND (we send to them).
        val newLocationRole = when (payload.acceptedRole) {
            PeerEntity.ROLE_RECEIVE -> PeerEntity.ROLE_SEND
            PeerEntity.ROLE_SEND -> PeerEntity.ROLE_RECEIVE
            PeerEntity.ROLE_NONE -> PeerEntity.ROLE_NONE
            else -> PeerEntity.ROLE_SEND_RECEIVE
        }

        val peer = PeerEntity(
            deviceId = existingPeer.deviceId,
            displayName = existingPeer.displayName,
            publicKeyHex = existingPeer.publicKeyHex,
            relayUrl = payload.acceptorRelayUrl,
            locationRole = newLocationRole,
            messagingEnabled = existingPeer.messagingEnabled,
            isArchived = existingPeer.isArchived,
            archivedAt = existingPeer.archivedAt,
            addedAt = existingPeer.addedAt
        )
        peerDao.upsertPeer(peer)
        relayClient.connect(payload.acceptorRelayUrl)
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
        peerDao.getPeer(event.pubkey) ?: return
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
            peerManager.handleRemovalByPeer(event.pubkey)
        }
        sendDeclineNotification(event.pubkey, payload.declinerDisplayName)
    }

    private fun sendAcceptanceNotification(pubkey: String, name: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
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
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createMessageChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_MESSAGES,
            context.getString(R.string.channel_name_messages),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = context.getString(R.string.channel_desc_messages) }
        notificationManager.createNotificationChannel(channel)
    }
}
