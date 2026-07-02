package com.locapeer.geofence

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.locapeer.MainActivity
import com.locapeer.R
import com.locapeer.data.dao.GeofenceDao
import com.locapeer.data.entity.GeofenceEntity
import com.locapeer.data.entity.HeartbeatEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val COOLDOWN_MS = 5 * 60 * 1000L // 5 minutes per fence
// Paired with a per fence+person tag (notify(tag, id, ...)) since two peers/fences' hashCodes
// can collide and silently overwrite each other's notification.
private const val NOTIF_ID_GEOFENCE = 60000

@Singleton
class GeofenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geofenceDao: GeofenceDao,
    private val notificationManager: NotificationManager
) {
    // Tracks the last time a notification was sent per fenceId to prevent GPS-jitter spam
    private val lastNotifiedAt = ConcurrentHashMap<String, Long>()

    suspend fun evaluate(current: HeartbeatEntity, previous: HeartbeatEntity?) {
        val fences = geofenceDao.getActiveGeofencesForDevice(current.deviceId)
        fences.forEach { fence ->
            val inNow = isInside(current.lat, current.lng, fence)
            // First heartbeat for this device: treat previous position as same as current so
            // neither entered nor exited fires — we don't know where they came from.
            val inPrev = previous?.let { isInside(it.lat, it.lng, fence) } ?: inNow

            val entered = !inPrev && inNow
            val exited = inPrev && !inNow

            val shouldNotify = when (fence.triggerOn) {
                "ENTER" -> entered
                "EXIT" -> exited
                "BOTH" -> entered || exited
                else -> false
            }

            if (shouldNotify) {
                val cooldownKey = "${fence.id}:${if (entered) "enter" else "exit"}"
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
                    subtitle = "at ${formatTime(current.timestamp)} · ${fence.radiusMetres}m radius"
                )
            }
        }
    }

    private fun isInside(lat: Double, lng: Double, fence: GeofenceEntity): Boolean =
        haversineMetres(lat, lng, fence.lat, fence.lng) <= fence.radiusMetres

    private fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).let { it * it }
        return r * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
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
        val openMapPi = PendingIntent.getActivity(
            context, fence.id.hashCode(), openMapIntent,
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
            context, fence.id.hashCode() + 1, chatIntent,
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
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
}
