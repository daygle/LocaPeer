package com.locapeer.proximity

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
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
import com.locapeer.subscriber.TrackingAlertPayload
import com.locapeer.util.GeoMath
import dagger.hilt.android.qualifiers.ApplicationContext
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val COOLDOWN_MS = 10 * 60 * 1000L // 10 minutes per peer
// Our own cached position older than this can't prove the peer is near *us* now.
private const val MAX_OWN_FIX_AGE_MS = 10 * 60 * 1000L
// Minimum hysteresis buffer past the alert radius before the peer counts as gone.
private const val MIN_EXIT_BUFFER_M = 100.0
// Paired with a per-peer tag (notify(tag, id, ...)) since two peers' deviceId hashCodes can
// collide and silently overwrite each other's notification.
private const val NOTIF_ID_PROXIMITY = 50000

@Singleton
class ProximityEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val proximityAlertDao: ProximityAlertDao,
    private val notificationManager: NotificationManager,
    private val relayClient: NostrRelayClient,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val prefs: AppPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var fusedLocation = LocationServices.getFusedLocationProviderClient(context)
    private val lastNotifiedAt = ConcurrentHashMap<String, Long>()
    // Whether each peer is currently within their alert radius, with hysteresis.
    // Alerts fire on the outside→inside transition only, so a peer who *stays*
    // nearby doesn't re-alert every cooldown period; leaving re-arms the alert.
    private val wasNearby = ConcurrentHashMap<String, Boolean>()

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

        // A stale own position only proves where we *were*; skip rather than
        // raise a "nearby" alert against it.
        if (System.currentTimeMillis() - ownLocation.time > MAX_OWN_FIX_AGE_MS) return
        // When the combined uncertainty exceeds the radius, "within radius" can't
        // be decided — this also keeps suburb-precision peers from false-alerting.
        val uncertainty = ownLocation.accuracy.toDouble() + peerHeartbeat.accuracy.toDouble()

        val distanceMetres = GeoMath.haversineMetres(
            ownLocation.latitude, ownLocation.longitude,
            peerHeartbeat.lat, peerHeartbeat.lng
        )

        val peerId = peerHeartbeat.deviceId
        val wasInside = wasNearby[peerId]
        val buffer = maxOf(MIN_EXIT_BUFFER_M, uncertainty)
        val inside = GeoMath.isInsideWithHysteresis(
            distanceMetres, alert.radiusMetres.toDouble(), buffer, wasInside == true
        )

        // If the uncertainty is larger than the radius, we can't reliably confirm
        // a NEW entry. However, if we are currently "outside" even with the buffer,
        // we allow the state to update to "outside" so that future entry alerts
        // can fire when accuracy improves.
        if (inside && wasInside != true && uncertainty > alert.radiusMetres.toDouble()) return

        wasNearby[peerId] = inside
        // First observation since process start: seed only. A peer who was already
        // nearby shouldn't re-alert on every app restart; arrivals fire next beat.
        if (wasInside == null) return

        if (inside && !wasInside) {
            val now = System.currentTimeMillis()
            if (now - (lastNotifiedAt[peerId] ?: 0L) < COOLDOWN_MS) return
            lastNotifiedAt[peerId] = now
            sendNotification(peerHeartbeat.displayName, peerId, distanceMetres.toInt())
            sendTrackingAlertToPeer(peerId)
        }
    }

    private fun sendTrackingAlertToPeer(peerPubkey: String) {
        scope.launch {
            try {
                val (privHex, pubHex) = keyManager.ensureKeypair()
                val myName = prefs.settings.first().displayName.ifBlank { "Someone" }
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

    private fun sendNotification(personName: String, personDeviceId: String, distanceMetres: Int) {
        val displayDistance = "${com.locapeer.util.DisplayFormat.distanceValue(distanceMetres.toDouble())} away"

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

    private fun isDeadObject(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is android.os.DeadObjectException) return true
            cause = cause.cause
        }
        return false
    }
}
