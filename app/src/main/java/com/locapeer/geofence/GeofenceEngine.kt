package com.locapeer.geofence

import android.app.NotificationManager
import com.locapeer.util.backgroundScope
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.core.app.NotificationCompat
import com.locapeer.MainActivity
import com.locapeer.R
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.ActiveGeofence
import com.locapeer.data.dao.GeofenceAssignmentDao
import com.locapeer.data.dao.GeofenceDao
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val COOLDOWN_MS = 5 * 60 * 1000L // 5 minutes per fence
// Minimum hysteresis buffer past the fence radius before an exit counts.
private const val MIN_EXIT_BUFFER_M = 50.0
// How far back a stored heartbeat may be used to seed inside/outside state after a
// restart. Matches the receiver's fresh-heartbeat window: alerts only run for beats that
// recent, so an older "previous" means the engine was not watching for the whole gap and
// a transition between the two is an invention, not an observation.
private const val SEED_MAX_GAP_MS = 10 * 60 * 1000L
// ENTER fixes up to this multiple of the fence radius in accuracy are trusted (see
// evaluate): a fix within 2x the radius of the centre genuinely puts the person inside
// the fence, so waiting for a sharper one only delays the arrival alert. Small fences
// get a floor so tiny fences aren't unmalertable, while the suburb-precision ~1.1km
// accuracy stays excluded unless the fence is genuinely district-sized.
private const val ENTER_ACCURACY_FACTOR = 2.0
private const val ENTER_ACCURACY_FLOOR_M = 100.0
// Paired with a per fence+person tag (notify(tag, id, ...)) since two peers/fences' hashCodes
// can collide and silently overwrite each other's notification.
const val NOTIF_ID_GEOFENCE = 60000

@Singleton
class GeofenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geofenceAssignmentDao: GeofenceAssignmentDao,
    private val geofenceDao: GeofenceDao,
    private val notificationManager: NotificationManager,
    private val relayClient: NostrRelayClient,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val prefs: AppPreferences
) {
    private val scope = backgroundScope("GeofenceEngine")
    // Tracks the last time a notification was sent per fenceId to prevent GPS-jitter spam
    private val lastNotifiedAt = ConcurrentHashMap<String, Long>()
    // Inside/outside membership per fence+person, with hysteresis. In-memory: after a
    // process restart it re-seeds from the previous stored heartbeat, missing at most
    // one transition rather than inventing one.
    private val insideState = ConcurrentHashMap<String, Boolean>()

    // Cache of active geofences per tracked device ID.
    private val fencesCache = ConcurrentHashMap<String, List<ActiveGeofence>>()
    // Whether each fence+person assignment was on at the last DB emission, so an
    // assignment switched back on can report the visit already in progress.
    private val wasActiveAssignment = ConcurrentHashMap<String, Boolean>()
    // Fence+person pairs just switched back on: the next reliable evaluation reports
    // "inside" rather than seeding silently.
    private val announceOnNextEvaluation = ConcurrentHashMap.newKeySet<String>()

    init {
        scope.launch {
            // Re-fetch everything whenever ANY assignment changes, and notice assignments
            // turned back on so their current state can be reported.
            geofenceAssignmentDao.observeAll().collect { assignments ->
                fencesCache.clear()
                val present = assignments.mapTo(mutableSetOf()) { "${it.geofenceId}:${it.trackedDeviceId}" }
                assignments.forEach { a ->
                    val key = "${a.geofenceId}:${a.trackedDeviceId}"
                    val before = wasActiveAssignment.put(key, a.active)
                    if (before == false && a.active) announceOnNextEvaluation += key
                    if (!a.active) announceOnNextEvaluation -= key
                }
                // Deleted assignments stop being tracked, so re-creating one never looks
                // like a re-enable.
                wasActiveAssignment.keys.retainAll(present)
                announceOnNextEvaluation.retainAll(present)
            }
        }
        scope.launch {
            // Editing an area's centre or radius only touches the geofence row, so watch
            // that table too - and drop state for areas that no longer exist.
            geofenceDao.getAllGeofences().collect { fences ->
                fencesCache.clear()
                val ids = fences.mapTo(HashSet()) { it.id }
                insideState.keys.removeIf { it.substringBefore(':') !in ids }
                lastNotifiedAt.keys.removeIf { it.substringBefore(':') !in ids }
                announceOnNextEvaluation.removeIf { it.substringBefore(':') !in ids }
            }
        }
    }

    suspend fun evaluate(current: HeartbeatEntity, previous: HeartbeatEntity?) {
        val fences = fencesCache.getOrPut(current.deviceId) {
            geofenceAssignmentDao.getActiveGeofencesForDevice(current.deviceId)
        }
        fences.forEach { fence ->
            val key = "${fence.id}:${current.deviceId}"
            val wasInside = insideState[key]
                ?: previous?.takeIf {
                    it.accuracy <= fence.radiusMetres.toFloat() &&
                        GeoMath.canSeedFromPrevious(current.timestamp, it.timestamp, SEED_MAX_GAP_MS)
                }?.let {
                    GeoMath.haversineMetres(it.lat, it.lng, fence.lat, fence.lng) <=
                        fence.radiusMetres.toDouble()
                }

            val dist = GeoMath.haversineMetres(current.lat, current.lng, fence.lat, fence.lng)
            // A coarse fix cannot place the person outside the fence any more precisely
            // than inside it, so the exit boundary is buffered by the fix's accuracy (never
            // less than the minimum). The old minimum-only buffer for EXIT/BOTH fences
            // assumed the sender's accuracy gate withheld coarse fixes, but that gate is
            // opt-in and defaults off: a 500 m network fix could otherwise "leave" a 100 m
            // fence while the person was standing in it.
            val buffer = maxOf(MIN_EXIT_BUFFER_M, current.accuracy.toDouble())
            val inNow = GeoMath.isInsideWithHysteresis(dist, fence.radiusMetres.toDouble(), buffer, wasInside == true)

            // A fix coarser than the fence itself can't reliably tell inside from outside -
            // this keeps suburb-precision peers from tripping street-sized fences. Fences
            // large enough to accept a fix within ENTER_ACCURACY_FACTOR x the radius alert
            // on the arrival ping instead of waiting (possibly several heartbeats) for a
            // sharper fix. Only "inside" is gated; if a fix is far enough away to be
            // "outside" even with its accuracy buffer, it is accepted so EXIT alerts fire
            // even during poor GPS reception.
            if (inNow && wasInside != true &&
                current.accuracy > maxOf(
                    fence.radiusMetres * ENTER_ACCURACY_FACTOR,
                    ENTER_ACCURACY_FLOOR_M
                )
            ) return@forEach

            // An assignment switched back on reports the visit that is already happening;
            // a first-ever observation (restart, brand-new assignment) still seeds silently.
            val announce = announceOnNextEvaluation.remove(key) == true
            insideState[key] = inNow
            // First reliable observation for this fence+person: seed only - we don't
            // know where they came from, so neither entered nor exited can fire.
            if (wasInside == null && !announce) return@forEach

            val entered = wasInside == false && inNow
            val exited = wasInside == true && !inNow
            // Already inside when the assignment came back on: not a crossing, so report
            // "is inside" rather than claiming an arrival that never happened.
            val announceInside = announce && inNow && !entered

            val shouldNotify = when (fence.triggerOn) {
                "ENTER" -> entered || announceInside
                "EXIT" -> exited
                "BOTH" -> entered || exited || announceInside
                else -> false
            }

            if (shouldNotify) {
                // Schedule check: only notify if the current time matches the assignment's schedule.
                // Empty schedule defaults to active (all days/times).
                val rules = fence.scheduleRules.toScheduleRules()
                if (!SharingSchedule.isActive(rules)) return@forEach

                val cooldownKey = "${fence.id}:${current.deviceId}:${if (exited) "exit" else "enter"}"
                val now = System.currentTimeMillis()
                val last = lastNotifiedAt[cooldownKey] ?: 0L
                if (now - last < COOLDOWN_MS) return@forEach
                lastNotifiedAt[cooldownKey] = now

                val title = when {
                    exited -> context.getString(R.string.notif_geofence_left_title, current.displayName, fence.name)
                    entered -> context.getString(R.string.notif_geofence_entered_title, current.displayName, fence.name)
                    else -> context.getString(R.string.notif_geofence_inside_title, current.displayName, fence.name)
                }
                sendGeofenceNotification(
                    fence = fence,
                    personName = current.displayName,
                    personDeviceId = current.deviceId,
                    title = title,
                    subtitle = context.getString(R.string.notif_geofence_subtitle, formatTime(current.timestamp), com.locapeer.util.DisplayFormat.distanceValue(fence.radiusMetres.toDouble()))
                )
                // An "is inside" report follows a settings change, not a crossing, so the
                // peer's "you entered or exited a fence" alert would be false.
                if (!announceInside) sendTrackingAlertToPeer(current.deviceId)
            }
        }
    }

    /**
     * Drop this device's in-memory state (inside/outside + cooldowns) so a contact
     * removed and later re-added with the same pubkey starts from a clean seed instead
     * of inheriting pre-removal state - which would otherwise suppress their first
     * alert after re-add (the "first observation seeds only" rule would treat the
     * stale state as a real position and skip the transition). Called from
     * [com.locapeer.peer.PeerManager] on every removal path.
     */
    fun onPeerRemoved(deviceId: String) {
        insideState.keys.removeIf { it.endsWith(":$deviceId") }
        announceOnNextEvaluation.removeIf { it.endsWith(":$deviceId") }
        // Cooldown keys always carry the :enter/:exit suffix.
        lastNotifiedAt.keys.removeIf { it.endsWith(":$deviceId:enter") || it.endsWith(":$deviceId:exit") }
    }

    private fun sendTrackingAlertToPeer(peerPubkey: String) {
        scope.launch {
            try {
                val (privHex, pubHex) = keyManager.ensureKeypair()
                val myName = prefs.settings.first().displayName.ifBlank { context.getString(R.string.notif_someone) }
                // The fence's name is the watcher's own label for that place ("Home",
                // "Clinic"); it is never shared with the tracked peer anywhere else. Tell
                // them an alert fired, not what the watcher calls the area.
                val payload = TrackingAlertPayload(
                    type = "GEOFENCE",
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
                android.util.Log.e("GeofenceEngine", "Failed to send tracking alert to $peerPubkey", e)
            }
        }
    }

    private suspend fun sendGeofenceNotification(
        fence: ActiveGeofence,
        personName: String,
        personDeviceId: String,
        title: String,
        subtitle: String
    ) {
        // Alert notifications honour the global switches and quiet hours (Settings -> Privacy).
        // Only the notification is suppressed: the event is still recorded locally and the
        // peer-side TRACKING_ALERT still sends, so the audit trail stays honest.
        val settings = prefs.settings.first()
        if (!settings.alertNotificationsEnabled) return
        if (com.locapeer.util.QuietHours.isQuietNow(settings.quietHoursEnabled, settings.quietStartMinute, settings.quietEndMinute)) return
        // A unique data URI keys each PendingIntent to its fence+person. The request
        // code alone can't: extras don't participate in PendingIntent matching
        // (Intent.filterEquals), so two keys whose String.hashCode collides would
        // share one PendingIntent and FLAG_UPDATE_CURRENT would overwrite the other
        // notification's extras, routing its tap to the wrong fence/person.
        val openMapIntent = Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = "locapeer-notif://geofence/${fence.id}/$personDeviceId/map".toUri()
            putExtra("navigateTo", "map")
            putExtra("highlightPeer", personDeviceId)
        }
        val openMapPi = PendingIntent.getActivity(
            context, "${fence.id}:$personDeviceId:map".hashCode(), openMapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val chatIntent = Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = "locapeer-notif://geofence/${fence.id}/$personDeviceId/chat".toUri()
            putExtra("navigateTo", "chat")
            putExtra("openChat", personDeviceId)
            putExtra("peerName", personName)
            putExtra(com.locapeer.EXTRA_CANCEL_NOTIF_TAG, "${fence.id}:$personDeviceId")
            putExtra(com.locapeer.EXTRA_CANCEL_NOTIF_ID, NOTIF_ID_GEOFENCE)
            putExtra(com.locapeer.EXTRA_CANCEL_NOTIF_TOKEN, com.locapeer.util.NotificationCancelToken.get(context))
        }
        val chatPi = PendingIntent.getActivity(
            context, "${fence.id}:$personDeviceId:chat".hashCode(), chatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, "locapeer_alerts")
            .setSmallIcon(R.drawable.ic_notif_locapeer)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openMapPi)
            .addAction(R.drawable.ic_notif_message, context.getString(R.string.notif_message_person, personName), chatPi)
            .setAutoCancel(true)
            // Who arrived where is location data: redact it on a locked screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        notificationManager.notify("${fence.id}:$personDeviceId", NOTIF_ID_GEOFENCE, notification)
    }

    private fun formatTime(millis: Long): String =
        com.locapeer.util.DisplayFormat.timeFormat().format(Date(millis))
}
