package com.locapeer.proximity

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.locapeer.MainActivity
import com.locapeer.R
import com.locapeer.data.dao.ProximityAlertDao
import com.locapeer.data.entity.HeartbeatEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val COOLDOWN_MS = 10 * 60 * 1000L // 10 minutes per peer
// Paired with a per-peer tag (notify(tag, id, ...)) since two peers' deviceId hashCodes can
// collide and silently overwrite each other's notification.
private const val NOTIF_ID_PROXIMITY = 50000

@Singleton
class ProximityEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val proximityAlertDao: ProximityAlertDao,
    private val notificationManager: NotificationManager
) {
    private var fusedLocation = LocationServices.getFusedLocationProviderClient(context)
    private val lastNotifiedAt = ConcurrentHashMap<String, Long>()

    @SuppressLint("MissingPermission")
    suspend fun evaluate(peerHeartbeat: HeartbeatEntity) {
        val alert = proximityAlertDao.getForPeer(peerHeartbeat.deviceId) ?: return
        if (!alert.active) return

        val ownLocation = try {
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
        } ?: return

        val distanceMetres = haversineMetres(
            ownLocation.latitude, ownLocation.longitude,
            peerHeartbeat.lat, peerHeartbeat.lng
        )

        if (distanceMetres <= alert.radiusMetres) {
            val now = System.currentTimeMillis()
            if (now - (lastNotifiedAt[peerHeartbeat.deviceId] ?: 0L) < COOLDOWN_MS) return
            lastNotifiedAt[peerHeartbeat.deviceId] = now
            sendNotification(peerHeartbeat.displayName, peerHeartbeat.deviceId, distanceMetres.toInt())
        }
    }

    private fun sendNotification(personName: String, personDeviceId: String, distanceMetres: Int) {
        val displayDistance = if (distanceMetres < 1000) "${distanceMetres}m away"
        else "${"%.1f".format(distanceMetres / 1000.0)}km away"

        val chatIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigateTo", "chat")
            putExtra("openChat", personDeviceId)
            putExtra("peerName", personName)
            putExtra(com.locapeer.EXTRA_CANCEL_NOTIF_TAG, personDeviceId)
            putExtra(com.locapeer.EXTRA_CANCEL_NOTIF_ID, NOTIF_ID_PROXIMITY)
        }
        val chatPi = PendingIntent.getActivity(
            context, personDeviceId.hashCode() + 20000, chatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigateTo", "map")
            putExtra("highlightPeer", personDeviceId)
        }
        val mapPi = PendingIntent.getActivity(
            context, personDeviceId.hashCode() + 21000, mapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, "locapeer_alerts")
            .setSmallIcon(R.drawable.ic_notif_location)
            .setContentTitle("$personName is nearby")
            .setContentText("$displayDistance from you")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(mapPi)
            .addAction(R.drawable.ic_notif_message, "Message $personName", chatPi)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(personDeviceId, NOTIF_ID_PROXIMITY, notification)
    }

    private fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).let { it * it }
        return r * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private fun isDeadObject(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is android.os.DeadObjectException) return true
            cause = cause.cause
        }
        return false
    }
}
