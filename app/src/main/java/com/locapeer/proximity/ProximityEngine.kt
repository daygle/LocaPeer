package com.locapeer.proximity

import android.annotation.SuppressLint
import com.locapeer.util.backgroundScope
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.locapeer.MainActivity
import com.locapeer.R
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.ProximityAlertDao
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.sharing.SharingSchedule
import com.locapeer.sharing.toScheduleRules
import com.locapeer.subscriber.TrackingAlertPayload
import com.locapeer.util.GeoMath
import dagger.hilt.android.qualifiers.ApplicationContext
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val COOLDOWN_MS = 10 * 60 * 1000L // 10 minutes per peer
// Paired with a per-peer tag (notify(tag, id, ...)) since two peers' deviceId hashCodes can
// collide and silently overwrite each other's notification.
const val NOTIF_ID_PROXIMITY = 50000

@Singleton
class ProximityEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val proximityAlertDao: ProximityAlertDao,
    private val proximityStateStore: ProximityStateStore,
    private val notificationManager: NotificationManager,
    private val relayClient: NostrRelayClient,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val prefs: AppPreferences
) {
    private val scope = backgroundScope("ProximityEngine")
    private var fusedLocation = LocationServices.getFusedLocationProviderClient(context)
    // Whether each peer is currently within their alert radius (with hysteresis), their
    // notification cooldown, and both values' persistence. Alerts fire on the outside→inside
    // transition only, so a peer who *stays* nearby doesn't re-alert every cooldown period;
    // leaving re-arms the alert. The cache serializes saves against contact removal.
    private val stateCache = ProximityStateCache(proximityStateStore)

    // Cache of proximity alerts per peer device ID. Wrapped because ConcurrentHashMap rejects
    // null values, and "this peer has no alert" (the common case) must be cached too.
    private class CachedAlert(val alert: com.locapeer.data.entity.ProximityAlertEntity?)
    private val alertsCache = ConcurrentHashMap<String, CachedAlert>()

    // Cache our own location to avoid redundant FusedLocation calls when multiple peers pulse at once.
    @Volatile private var cachedOwnLocation: android.location.Location? = null
    @Volatile private var lastOwnLocationFetchAt = 0L
    private val ownLocationMutex = Mutex()
    // Serializes the nearby-state flip and the notification decision. Evaluations are
    // launched per heartbeat, and a live pulse can land while an earlier one is still
    // waiting on the own-location fetch; two interleaved evaluations would both read the
    // old state before either wrote, double-alerting one arrival.
    private val decisionMutex = Mutex()
    // Whether each peer's alert was on at the last DB emission, so an alert switched back
    // on can report the visit already in progress.
    private val alertWasActive = ConcurrentHashMap<String, Boolean>()
    // Peers whose alert was just re-enabled: the next evaluation reports the current state
    // instead of seeding silently.
    private val announceOnNextEvaluation = ConcurrentHashMap.newKeySet<String>()

    init {
        scope.launch {
            var prunedStoredState = false
            proximityAlertDao.getAll().collect { alerts ->
                alertsCache.clear()
                val present = alerts.mapTo(mutableSetOf()) { it.peerDeviceId }
                // [alertWasActive] only knows alerts seen by this process, so state persisted for
                // an alert (or contact) deleted while the app was not running would never be
                // released - and would greet a later alert for that peer with a stale "already
                // nearby". Prune it once against the first full list.
                if (!prunedStoredState) {
                    prunedStoredState = true
                    runCatching { proximityStateStore.storedPeerIds() }
                        .getOrDefault(emptySet())
                        .filter { it !in present }
                        .forEach { peer -> stateCache.remove(peer) }
                }
                // An alert deleted outright releases its peer's nearby/cooldown state, so it
                // can't leak into a later alert or accumulate for peers without one.
                val removed = alertWasActive.keys.filter { it !in present }
                removed.forEach { peer ->
                    alertWasActive.remove(peer)
                    announceOnNextEvaluation.remove(peer)
                }
                removed.forEach { peer -> stateCache.remove(peer) }
                alerts.forEach { alert ->
                    val before = alertWasActive.put(alert.peerDeviceId, alert.active)
                    if (before == false && alert.active) announceOnNextEvaluation += alert.peerDeviceId
                    if (!alert.active) announceOnNextEvaluation -= alert.peerDeviceId
                }
                announceOnNextEvaluation.retainAll(present)
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun evaluate(peerHeartbeat: HeartbeatEntity) {
        val alert = (alertsCache[peerHeartbeat.deviceId]
            ?: CachedAlert(proximityAlertDao.getForPeer(peerHeartbeat.deviceId))
                .also { alertsCache[peerHeartbeat.deviceId] = it }
            ).alert ?: return
        if (!alert.active) return

        val ownLocation = getFreshOwnLocation() ?: return

        // A stale own position only proves where we *were*; skip rather than raise or
        // re-arm an alert against it. getFreshOwnLocation already refuses fixes past
        // this age; the re-check covers the 30 s cache reuse window.
        if (!isOwnFixFresh(System.currentTimeMillis() - ownLocation.time)) return
        // A peer fix is a position from the past, so its distance uncertainty grows with
        // age and with the speed the peer reported: a beat delayed by a relay backlog can't
        // assert a "nearby" that ended minutes ago. Both clock readings are used - the
        // sender's timestamp and our receive time - so clock skew can only make the
        // allowance larger, never smaller.
        val peerFixAgeMs = maxOf(
            System.currentTimeMillis() - peerHeartbeat.timestamp,
            System.currentTimeMillis() - peerHeartbeat.receivedAt
        ).coerceAtLeast(0L)
        // When the combined uncertainty exceeds the radius, "within radius" can't
        // be decided - this also keeps suburb-precision peers from false-alerting.
        val uncertainty = GeoMath.proximityUncertaintyM(
            ownAccuracyM = ownLocation.accuracy.toDouble(),
            peerAccuracyM = peerHeartbeat.accuracy.toDouble(),
            peerSpeedMps = peerHeartbeat.speed.coerceAtLeast(0f).toDouble(),
            peerFixAgeMs = peerFixAgeMs
        )

        val distanceMetres = GeoMath.haversineMetres(
            ownLocation.latitude, ownLocation.longitude,
            peerHeartbeat.lat, peerHeartbeat.lng
        )

        decisionMutex.withLock {
            val peerId = peerHeartbeat.deviceId
            // Loading the persisted state here (not at process start) keeps a restart
            // from re-alerting an arrival that already fired or forgetting a cooldown.
            val snapshot = stateCache.snapshot(peerId)
            val wasInside = snapshot.nearby
            // Switching an existing alert back on shouldn't wait for the peer to leave and
            // return: report the visit that is already happening. A first-ever observation
            // (restart, brand-new alert) still seeds silently.
            val announce = announceOnNextEvaluation.contains(peerId)
            val decision = ProximityDecisionPolicy.decide(
                distanceMetres = distanceMetres,
                radiusMetres = alert.radiusMetres.toDouble(),
                uncertaintyMetres = uncertainty,
                wasNearby = wasInside,
                announce = announce,
                lastNotifiedAtMs = snapshot.lastNotifiedAtMs,
                nowMs = System.currentTimeMillis(),
                cooldownMs = COOLDOWN_MS,
                // Only notify if the current time matches the alert's schedule.
                // Empty schedule defaults to active (all days/times).
                scheduleActive = SharingSchedule.isActive(alert.scheduleRules.toScheduleRules())
            )
            // Hold: this reading can't decide, so keep the state (and any pending
            // re-enable announcement) for a sharper beat.
            val nearby = decision.nearby ?: return@withLock
            if (announce) announceOnNextEvaluation.remove(peerId)
            val notifiedAtMs = if (decision.notify) decision.notifiedAtMs else snapshot.lastNotifiedAtMs
            // Persist the transition (and a fresh cooldown) before acting on it, so a
            // restart right after an alert can't re-arm it or forget the cooldown. The
            // save is refused if the contact was removed while this evaluation ran.
            if (nearby != wasInside || decision.notify) {
                stateCache.save(peerId, snapshot, ProximityPeerState(nearby, notifiedAtMs))
            }
            if (decision.notify) {
                sendNotification(peerHeartbeat.displayName, peerId, distanceMetres.toInt())
                sendTrackingAlertToPeer(peerId)
            }
        }
    }

    /**
     * Drop this peer's state so a contact removed and later re-added with the same
     * pubkey starts from a clean seed instead of inheriting pre-removal nearby/cooldown
     * state (which would suppress their first "nearby" alert after re-add). Awaits the
     * persisted clear, and [ProximityStateCache] refuses any save still in flight from an
     * evaluation that started before this, so stale state can't be resurrected. Called
     * from [com.locapeer.peer.PeerManager] on every removal path.
     */
    suspend fun onPeerRemoved(deviceId: String) {
        announceOnNextEvaluation.remove(deviceId)
        stateCache.remove(deviceId)
    }

    private fun sendTrackingAlertToPeer(peerPubkey: String) {
        scope.launch {
            try {
                val (privHex, pubHex) = keyManager.ensureKeypair()
                val myName = prefs.settings.first().displayName.ifBlank { context.getString(R.string.notif_someone) }
                val payload = TrackingAlertPayload(
                    type = "PROXIMITY",
                    triggerName = myName
                )
                val encrypted = crypto.nip44Encrypt(
                    crypto.hexToBytes(privHex),
                    peerPubkey,
                    Json.encodeToString(payload)
                )
                val event = NostrEvent.build(
                    privKeyHex = privHex,
                    pubKeyHex = pubHex,
                    kind = NostrEventKind.TRACKING_ALERT,
                    content = encrypted,
                    tags = listOf(listOf("p", peerPubkey)),
                    crypto = crypto
                )
                relayClient.publishEvent(event)
            } catch (e: Exception) {
                android.util.Log.e("ProximityEngine", "Failed to send tracking alert to $peerPubkey", e)
            }
        }
    }

    private suspend fun sendNotification(personName: String, personDeviceId: String, distanceMetres: Int) {
        // Alert notifications honour the global switches and quiet hours (Settings -> Privacy).
        // Only the notification is suppressed: the local alert row and the peer-side
        // TRACKING_ALERT still emit, so the audit trail stays honest.
        val settings = prefs.settings.first()
        if (!settings.alertNotificationsEnabled) return
        if (com.locapeer.util.QuietHours.isQuietNow(settings.quietHoursEnabled, settings.quietStartMinute, settings.quietEndMinute)) return
        // The distance is inserted into the localized "%1$s from you" template, so it must
        // not carry its own English suffix (that produced "500 m away from you").
        val displayDistance = com.locapeer.util.DisplayFormat.distanceValue(distanceMetres.toDouble())

        // A unique data URI keys each PendingIntent to its peer. The request code alone
        // can't: extras don't take part in PendingIntent matching (Intent.filterEquals),
        // so two peers whose deviceId hashCodes collide would share one PendingIntent and
        // FLAG_UPDATE_CURRENT would overwrite the other's extras, routing its tap to the
        // wrong contact.
        val chatIntent = Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = "locapeer-notif://proximity/$personDeviceId/chat".toUri()
            putExtra("navigateTo", "chat")
            putExtra("openChat", personDeviceId)
            putExtra("peerName", personName)
            putExtra(com.locapeer.EXTRA_CANCEL_NOTIF_TAG, personDeviceId)
            putExtra(com.locapeer.EXTRA_CANCEL_NOTIF_ID, NOTIF_ID_PROXIMITY)
            putExtra(com.locapeer.EXTRA_CANCEL_NOTIF_TOKEN, com.locapeer.util.NotificationCancelToken.get(context))
        }
        val chatPi = PendingIntent.getActivity(
            context, "proximity:$personDeviceId:chat".hashCode(), chatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mapIntent = Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = "locapeer-notif://proximity/$personDeviceId/map".toUri()
            putExtra("navigateTo", "map")
            putExtra("highlightPeer", personDeviceId)
        }
        val mapPi = PendingIntent.getActivity(
            context, "proximity:$personDeviceId:map".hashCode(), mapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, com.locapeer.util.AppNotifications.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notif_location)
            .setContentTitle(context.getString(R.string.notif_person_nearby, personName))
            .setContentText(context.getString(R.string.notif_distance_from_you, displayDistance))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(mapPi)
            .addAction(R.drawable.ic_notif_message, context.getString(R.string.notif_message_person, personName), chatPi)
            .setAutoCancel(true)
            // Who is near you is location data: redact it on a locked screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        notificationManager.notify(personDeviceId, NOTIF_ID_PROXIMITY, notification)
    }

    private fun isDeadObject(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is android.os.DeadObjectException) return true
            cause = cause.cause
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private suspend fun getFreshOwnLocation(): android.location.Location? = ownLocationMutex.withLock {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedOwnLocation
        if (cached != null && now - lastOwnLocationFetchAt < OWN_FIX_CACHE_MS &&
            isOwnFixFresh(System.currentTimeMillis() - cached.time)
        ) {
            return@withLock cached
        }

        // Passive fused cache first (free). A fix too old to say where we are now is
        // not a position at all: pay for one bounded active fetch, and if that fails
        // too, return null so this beat is skipped rather than measured from an old
        // place (which re-armed the alert while the user stood next to the peer).
        val passive = fetchLastLocation()?.takeIf {
            isOwnFixFresh(System.currentTimeMillis() - it.time)
        }
        val location = passive ?: fetchCurrentLocation()?.takeIf {
            isOwnFixFresh(System.currentTimeMillis() - it.time)
        }
        cachedOwnLocation = location
        if (location != null) lastOwnLocationFetchAt = now
        location
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchLastLocation(): android.location.Location? = try {
        suspendCancellableCoroutine<android.location.Location?> { cont ->
            fusedLocation.lastLocation
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener {
                    if (isDeadObject(it)) {
                        fusedLocation = LocationServices.getFusedLocationProviderClient(context)
                    }
                    cont.resume(null)
                }
                .addOnCanceledListener { cont.resume(null) }
        }
    } catch (e: Exception) {
        if (isDeadObject(e)) {
            fusedLocation = LocationServices.getFusedLocationProviderClient(context)
        }
        null
    }

    /**
     * One bounded active fix for proximity judgements: balanced power (proximity is
     * a coarse-radius judgement, not a pin), accepts a system-cached fix up to
     * [OWN_FIX_STALE_MS] old instantly, and gives up after 15s so a burst of peer
     * heartbeats can't hold the evaluation mutex waiting on a cold GPS. Null on any
     * failure; the caller falls back to the passive fix (age-gated at the call site).
     */
    @SuppressLint("MissingPermission")
    private suspend fun fetchCurrentLocation(): android.location.Location? = try {
        suspendCancellableCoroutine<android.location.Location?> { cont ->
            val request = com.google.android.gms.location.CurrentLocationRequest.Builder()
                .setPriority(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setMaxUpdateAgeMillis(OWN_FIX_STALE_MS)
                .setDurationMillis(15_000L)
                .build()
            fusedLocation.getCurrentLocation(request, null)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener {
                    if (isDeadObject(it)) {
                        fusedLocation = LocationServices.getFusedLocationProviderClient(context)
                    }
                    cont.resume(null)
                }
                .addOnCanceledListener { cont.resume(null) }
        }
    } catch (e: Exception) {
        if (isDeadObject(e)) {
            fusedLocation = LocationServices.getFusedLocationProviderClient(context)
        }
        null
    }
}
