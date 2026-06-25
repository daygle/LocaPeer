package com.locapeer.subscriber

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleCoroutineScope
import com.locapeer.MainActivity
import com.locapeer.R
import com.locapeer.beacon.HeartbeatPayload
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.geofence.GeofenceEngine
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrFilter
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "HeartbeatReceiver"
private const val CHANNEL_ID_ALERTS = "locapeer_alerts"
private const val SUB_ID = "locapeer-heartbeats"

@Singleton
class HeartbeatReceiver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val relayClient: NostrRelayClient,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val heartbeatDao: HeartbeatDao,
    private val peerDao: PeerDao,
    private val prefs: AppPreferences,
    private val geofenceEngine: GeofenceEngine,
    private val notificationManager: NotificationManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        createAlertChannel()
        scope.launch {
            val settings = prefs.settings.first()
            val (_, pubHex) = keyManager.ensureKeypair()
            relayClient.connect(settings.relayUrl)
            relayClient.subscribe(
                SUB_ID,
                NostrFilter(
                    kinds = listOf(NostrEventKind.HEARTBEAT, NostrEventKind.SOS_ALERT),
                    pTags = listOf(pubHex)
                )
            )
        }

        relayClient.events
            .onEach { event -> processEvent(event) }
            .launchIn(scope)
    }

    private suspend fun processEvent(event: NostrEvent) {
        if (event.kind != NostrEventKind.HEARTBEAT && event.kind != NostrEventKind.SOS_ALERT) return

        val broadcaster = peerDao.getPeer(event.pubkey) ?: return

        if (!NostrEvent.verify(event, crypto)) {
            Log.w(TAG, "Signature verification failed for event ${event.id}")
            return
        }

        try {
            val privHex = keyManager.getPrivateKeyHexBlocking() ?: return
            val privBytes = crypto.hexToBytes(privHex)
            val plaintext = crypto.nip04Decrypt(privBytes, event.pubkey, event.content)
            val payload = json.decodeFromString<HeartbeatPayload>(plaintext)

            val prevHeartbeat = heartbeatDao.getLatestHeartbeat(payload.deviceId)

            val entity = HeartbeatEntity(
                deviceId = payload.deviceId,
                displayName = payload.displayName,
                timestamp = Instant.parse(payload.timestamp).toEpochMilli(),
                lat = payload.lat,
                lng = payload.lng,
                accuracy = payload.accuracy,
                battery = payload.battery,
                motionState = payload.motionState,
                isSos = payload.isSos
            )
            heartbeatDao.insert(entity)

            if (payload.isSos) {
                sendSosNotification(broadcaster.displayName, payload)
            }

            geofenceEngine.evaluate(entity, prevHeartbeat)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process heartbeat", e)
        }
    }

    private fun sendSosNotification(name: String, payload: HeartbeatPayload) {
        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SOS from $name!")
            .setContentText("${name} has activated SOS at (${payload.lat}, ${payload.lng})")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(payload.deviceId.hashCode(), notification)
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
}
