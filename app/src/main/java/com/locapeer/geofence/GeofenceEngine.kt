package com.locapeer.geofence

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.locapeer.MainActivity
import com.locapeer.data.dao.GeofenceDao
import com.locapeer.data.entity.GeofenceEntity
import com.locapeer.data.entity.HeartbeatEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Singleton
class GeofenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geofenceDao: GeofenceDao,
    private val notificationManager: NotificationManager
) {
    suspend fun evaluate(current: HeartbeatEntity, previous: HeartbeatEntity?) {
        val fences = geofenceDao.getActiveGeofencesForDevice(current.deviceId)
        fences.forEach { fence ->
            val inNow = isInside(current.lat, current.lng, fence)
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
                val title = if (entered) "${current.displayName} has arrived at ${fence.name}"
                else "${current.displayName} has left ${fence.name}"
                sendGeofenceNotification(fence, title)
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
        return r * 2 * asin(sqrt(a))
    }

    private fun sendGeofenceNotification(fence: GeofenceEntity, title: String) {
        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "locapeer_alerts")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title)
            .setContentText("Geofence: ${fence.name}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(fence.id.hashCode(), notification)
    }
}
