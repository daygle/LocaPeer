package com.locapeer.beacon

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.locapeer.MainActivity
import com.locapeer.R
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.data.entity.PrecisionMode
import com.locapeer.nostr.NostrEvent
import com.locapeer.data.entity.scheduleRules
import com.locapeer.sharing.SharingSchedule
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.settings.AppSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject

private const val TAG = "HeartbeatService"
const val CHANNEL_ID_HEARTBEAT = "locapeer_heartbeat"
const val NOTIFICATION_ID_HEARTBEAT = 1001
const val ACTION_SOS_ON = "com.locapeer.SOS_ON"
const val ACTION_SOS_OFF = "com.locapeer.SOS_OFF"
const val ACTION_STOP = "com.locapeer.STOP_HEARTBEAT"
private const val ACTION_ACTIVITY_UPDATE = "com.locapeer.ACTIVITY_UPDATE"

@AndroidEntryPoint
class HeartbeatService : LifecycleService() {

    @Inject lateinit var keyManager: KeyManager
    @Inject lateinit var crypto: CryptoUtils
    @Inject lateinit var relayClient: NostrRelayClient
    @Inject lateinit var peerDao: PeerDao
    @Inject lateinit var sharingConfigDao: PeerSharingConfigDao
    @Inject lateinit var prefs: AppPreferences
    @Inject lateinit var intervalManager: AdaptiveIntervalManager
    @Inject lateinit var notificationManager: NotificationManager

    private lateinit var fusedLocation: FusedLocationProviderClient
    private lateinit var activityClient: ActivityRecognitionClient
    private val handler = Handler(Looper.getMainLooper())

    private var lastLat = 0.0
    private var lastLng = 0.0
    private var lastAccuracy = 0f
    private var currentSettings = AppSettings()
    private var isSos = false
    private var currentMotionState = MotionState.UNKNOWN

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                lastLat = loc.latitude
                lastLng = loc.longitude
                lastAccuracy = loc.accuracy
            }
        }
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            broadcastHeartbeat()
            val interval = intervalManager.getIntervalMillis(currentSettings)
            handler.postDelayed(this, interval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        activityClient = ActivityRecognition.getClient(this)
        createNotificationChannel()
        lifecycleScope.launch {
            prefs.settings.collect { settings ->
                currentSettings = settings
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SOS_ON -> {
                isSos = true
                intervalManager.setSosMode(true)
                broadcastHeartbeat(isSos = true)
                reschedulePulse()
            }
            ACTION_SOS_OFF -> {
                isSos = false
                intervalManager.setSosMode(false)
                reschedulePulse()
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ACTIVITY_UPDATE -> {
                if (ActivityRecognitionResult.hasResult(intent)) {
                    val result = ActivityRecognitionResult.extractResult(intent) ?: return START_NOT_STICKY
                    val detected = result.mostProbableActivity
                    val newState = when (detected.type) {
                        DetectedActivity.STILL -> MotionState.STATIONARY
                        DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> MotionState.WALKING
                        DetectedActivity.RUNNING -> MotionState.RUNNING
                        DetectedActivity.ON_BICYCLE -> MotionState.CYCLING
                        DetectedActivity.IN_VEHICLE -> MotionState.DRIVING
                        else -> MotionState.UNKNOWN
                    }
                    if (newState != currentMotionState) {
                        currentMotionState = newState
                        intervalManager.updateMotionState(newState)
                        updateLocationRequest()
                        reschedulePulse()
                    }
                }
            }
            else -> startForegroundAndBroadcast()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startForegroundAndBroadcast() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID_HEARTBEAT, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID_HEARTBEAT, notification)
        }

        updateLocationRequest()

        val activityIntent = PendingIntent.getService(
            this, 0,
            Intent(this, HeartbeatService::class.java).apply { action = ACTION_ACTIVITY_UPDATE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        try {
            activityClient.requestActivityUpdates(30_000L, activityIntent)
        } catch (e: SecurityException) {
            Log.e(TAG, "Activity recognition permission missing: ${e.message}")
        }

        lifecycleScope.launch {
            try {
                currentSettings = prefs.settings.first()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load settings; using defaults", e)
            }
            relayClient.connect()
        }

        handler.post(heartbeatRunnable)
    }

    @SuppressLint("MissingPermission")
    private fun updateLocationRequest() {
        fusedLocation.removeLocationUpdates(locationCallback)
        val priority = when {
            isSos -> Priority.PRIORITY_HIGH_ACCURACY
            currentMotionState == MotionState.DRIVING -> Priority.PRIORITY_HIGH_ACCURACY
            currentMotionState == MotionState.STATIONARY -> Priority.PRIORITY_LOW_POWER
            else -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val pollIntervalMs = when {
            isSos -> 10_000L
            currentMotionState == MotionState.STATIONARY -> 120_000L
            currentMotionState == MotionState.DRIVING -> 15_000L
            else -> 30_000L
        }
        val locationRequest = LocationRequest.Builder(priority, pollIntervalMs)
            .setMinUpdateIntervalMillis(pollIntervalMs / 2)
            .build()
        fusedLocation.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun reschedulePulse() {
        handler.removeCallbacks(heartbeatRunnable)
        handler.post(heartbeatRunnable)
    }

    private fun broadcastHeartbeat(isSos: Boolean = this.isSos) {
        if (lastLat == 0.0 && lastLng == 0.0) {
            Log.d(TAG, "Skipping heartbeat: no location fixed yet")
            return
        }
        val battery = getBatteryLevel()
        intervalManager.updateBattery(battery)

        lifecycleScope.launch {
            try {
                val (privHex, pubHex) = keyManager.ensureKeypair()
                val settings = prefs.settings.first()

                if (!isSos && !SharingSchedule.isActive(settings.globalScheduleRules)) {
                    Log.d(TAG, "Heartbeat suppressed: outside global schedule")
                    return@launch
                }

                val sendContacts = peerDao.getSendContacts().first()
                val configMap = sharingConfigDao.getAll().associateBy { it.peerDeviceId }
                var sentCount = 0
                sendContacts.forEach { subscriber ->
                    val cfg = configMap[subscriber.deviceId]

                    if (isSos && cfg?.isSosContact == false) return@forEach
                    if (!isSos && cfg?.sharingEnabled == false) return@forEach
                    if (!isSos && !SharingSchedule.isActive(cfg?.scheduleRules() ?: emptyList())) return@forEach

                    val (sendLat, sendLng) = if (
                        cfg?.precisionMode == PrecisionMode.SUBURB.name && !isSos
                    ) {
                        SharingSchedule.toSuburbPrecision(lastLat, lastLng)
                    } else {
                        lastLat to lastLng
                    }

                    val payload = HeartbeatPayload(
                        deviceId = pubHex,
                        displayName = settings.displayName,
                        timestamp = Instant.now().toString(),
                        lat = sendLat,
                        lng = sendLng,
                        accuracy = if (cfg?.precisionMode == PrecisionMode.SUBURB.name && !isSos) 1100f else lastAccuracy,
                        battery = battery,
                        motionState = currentMotionState.name,
                        isSos = isSos,
                        retentionDays = cfg?.retentionDaysLocation ?: 30
                    )
                    val payloadJson = Json.encodeToString(payload)

                    val encrypted = crypto.nip44Encrypt(
                        senderPrivKey = crypto.hexToBytes(privHex),
                        recipientXOnlyHex = subscriber.publicKeyHex,
                        plaintext = payloadJson
                    )
                    val kind = if (isSos) NostrEventKind.SOS_ALERT else NostrEventKind.HEARTBEAT
                    val event = NostrEvent.build(
                        privKeyHex = privHex,
                        pubKeyHex = pubHex,
                        kind = kind,
                        content = encrypted,
                        tags = listOf(
                            listOf("p", subscriber.publicKeyHex),
                            listOf("t", "locapeer-heartbeat")
                        ),
                        crypto = crypto
                    )
                    relayClient.publishEvent(event)
                    sentCount++
                }
                Log.d(TAG, "Heartbeat sent to $sentCount/${sendContacts.size} sendContacts")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send heartbeat", e)
            }
        }
    }

    private fun getBatteryLevel(): Int {
        val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID_HEARTBEAT)
            .setSmallIcon(R.drawable.ic_notif_location)
            .setContentTitle(getString(R.string.notification_heartbeat_title))
            .setContentText(getString(R.string.notification_heartbeat_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_HEARTBEAT,
            getString(R.string.channel_name_heartbeat),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_desc_heartbeat)
        }
        notificationManager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeatRunnable)
        fusedLocation.removeLocationUpdates(locationCallback)
        val activityIntent = PendingIntent.getService(
            this, 0,
            Intent(this, HeartbeatService::class.java).apply { action = ACTION_ACTIVITY_UPDATE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        try {
            activityClient.removeActivityUpdates(activityIntent)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to remove activity updates: ${e.message}")
        }
        super.onDestroy()
    }
}
