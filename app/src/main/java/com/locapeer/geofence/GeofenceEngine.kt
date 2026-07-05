package com.locapeer.geofence

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.locapeer.MainActivity
import com.locapeer.R
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.GeofenceDao
import com.locapeer.data.entity.GeofenceEntity
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.subscriber.TrackingAlertPayload
import com.locapeer.util.GeoMath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val COOLDOWN_MS = 5 * 60 * 1000L // 5 minutes per fence
// Minimum hysteresis buffer past the fence radius before an exit counts.
private const val MIN_EXIT_BUFFER_M = 50.0
// Paired with a per fence+person tag (notify(tag, id, ...)) since two peers/fences' hashCodes
// can collide and silently overwrite each other's notification.
private const val NOTIF_ID_GEOFENCE = 60000

@Singleton
class GeofenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geofenceDao: GeofenceDao,
    private val notificationManager: NotificationManager,
    private val relayClient: NostrRelayClient,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val prefs: AppPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Tracks the last time a notification was sent per fenceId to prevent GPS-jitter spam
    private val lastNotifiedAt = ConcurrentHashMap<String, Long>()
    // Inside/outside membership per fence+person, with hysteresis. In-memory: after a
    // process restart it re-seeds from the previous stored heartbeat, missing at most
    // one transition rather than inventing one.
    private val insideState = ConcurrentHashMap<String, Boolean>()

    suspend fun evaluate(current: HeartbeatEntity, previous: HeartbeatEntity?) {
        val fences = geofenceDao.getActiveGeofencesForDevice(current.deviceId)
        fences.forEach { fence ->
            // A fix coarser than the fence itself can't tell inside from outside —
            // this also keeps suburb-precision peers from tripping street-sized fences.
            if (current.accuracy > fence.radiusMetres.toFloat()) return@forEach

            val key = "${fence.id}:${current.deviceId}"
            val dist = GeoMath.haversineMetres(current.lat, current.lng, fence.lat, fence.lng)
            val buffer = maxOf(MIN_EXIT_BUFFER_M, current.accuracy.toDouble())
            val wasInside = insideState[key]
                ?: previous?.takeIf { it.accuracy <= fence.radiusMetres.toFloat() }
                    ?.let { GeoMath.haversineMetres(it.lat, it.lng, fence.lat, fence.lng) <= fence.radiusMetres.toDouble() }
            val inNow = GeoMath.isInsideWithHysteresis(dist, fence.radiusMetres.toDouble(), buffer, wasInside == true)
            insideState[key] = inNow
            // First reliable observation for this fence+person: seed only — we don't
            // know where they came from, so neither entered nor exited can fire.
            if (wasInside == null) return@forEach

            val entered = !wasInside && inNow
            val exited = wasInside && !inNow

            val shouldNotify = when (fence.triggerOn) {
                "ENTER" -> entered
                "EXIT" -> exited
                "BOTH" -> entered || exited
                else -> false
            }

            if (shouldNotify) {
                val cooldownKey = "${fence.id}:${current.deviceId}:${if (entered) "enter" else "exit"}"
                val now = System.currentTimeMillis()
                val last = lastNotifiedAt[cooldownKey] ?: 0L
                if (now - last < COOLDOWN_MS) return@forEach
                lastNotifiedAt[cooldownKey] = now

                val verb = if (entered) "arrived at" else "left"
                sendGeofenceNotification(
                    fence = fence,
                    personName = current.displayName,
                    personDeviceId = current.deviceId,
                    title = "${current.displayName} $verb ${fence.name}",
                    subtitle = "at ${formatTime(current.timestamp)} · ${com.locapeer.util.DisplayFormat.distanceValue(fence.radiusMetres.toDouble())} radius"
                )
                sendTrackingAlertToPeer(current.deviceId, fence.name)
            }
        }
    }

    private fun sendTrackingAlertToPeer(peerPubkey: String, fenceName: String) {
        scope.launch {
            try {
                val (privHex, pubHex) = keyManager.ensureKeypair()
                val myName = prefs.settings.first().displayName.ifBlank { "Someone" }
                val payload = TrackingAlertPayload(
                    type = "GEOFENCE",
                    alertName = fenceName,
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

    private fun sendGeofenceNotification(
        fence: GeofenceEntity,
        personName: String,
        personDeviceId: String,
        title: String,
        subtitle: String
    ) {
        val openMapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigateTo", "map")
            putExtra("highlightPeer", personDeviceId)
        }
        // Request codes include the person so two people triggering the same fence
        // don't overwrite each other's PendingIntent extras.
        val openMapPi = PendingIntent.getActivity(
            context, "${fence.id}:$personDeviceId:map".hashCode(), openMapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val chatIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigateTo", "chat")
            putExtra("openChat", personDeviceId)
            putExtra("peerName", personName)
            putExtra(com.locapeer.EXTRA_CANCEL_NOTIF_TAG, "${fence.id}:$personDeviceId")
            putExtra(com.locapeer.EXTRA_CANCEL_NOTIF_ID, NOTIF_ID_GEOFENCE)
        }
        val chatPi = PendingIntent.getActivity(
            context, "${fence.id}:$personDeviceId:chat".hashCode(), chatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, "locapeer_alerts")
            .setSmallIcon(R.drawable.ic_notif_alert)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openMapPi)
            .addAction(R.drawable.ic_notif_message, "Message $personName", chatPi)
            .setAutoCancel(true)
            .build()
        notificationManager.notify("${fence.id}:$personDeviceId", NOTIF_ID_GEOFENCE, notification)
    }

    private fun formatTime(millis: Long): String =
        com.locapeer.util.DisplayFormat.timeFormat().format(Date(millis))
}
