package com.locapeer.beacon

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
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
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.PrecisionMode
import com.locapeer.nostr.NostrEvent
import com.locapeer.data.entity.scheduleRules
import com.locapeer.sharing.SharingSchedule
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.settings.AppSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject

private const val TAG = "HeartbeatService"
const val CHANNEL_ID_HEARTBEAT = "locapeer_heartbeat"
const val NOTIFICATION_ID_HEARTBEAT = 1001
const val ACTION_SOS_ON = "com.locapeer.SOS_ON"
const val ACTION_SOS_OFF = "com.locapeer.SOS_OFF"
const val ACTION_STOP = "com.locapeer.STOP_HEARTBEAT"
const val ACTION_PULSE_CHECK = "com.locapeer.PULSE_CHECK"
const val ACTION_ACTIVITY_TRANSITION = "com.locapeer.ACTIVITY_TRANSITION"

/** Slack added to the doze-backstop alarm so the handler normally fires first. */
private const val PULSE_BACKSTOP_SLACK_MS = 90_000L
/** Ignore cached last-known locations older than this when pre-seeding at startup. */
private const val MAX_SEED_AGE_MS = 10 * 60_000L
/**
 * How long to hold high-accuracy GPS after the device leaves a settled place, so the
 * classifier gets clean speed to reach the right moving state (notably DRIVING, the
 * only state that otherwise requests high accuracy) instead of being stuck on the
 * coarse fixes used while stationary. Auto-expires back to the settled profile.
 */
private const val CLASSIFY_BOOST_MS = 45_000L

private fun MotionState.isMoving(): Boolean =
    this == MotionState.WALKING || this == MotionState.RUNNING ||
        this == MotionState.CYCLING || this == MotionState.DRIVING

@AndroidEntryPoint
class HeartbeatService : LifecycleService() {

    companion object {
        /** Process-local liveness flag so the watchdog worker can detect a dead service. */
        @Volatile var isRunning = false
            private set
    }

    @Inject lateinit var keyManager: KeyManager
    @Inject lateinit var crypto: CryptoUtils
    @Inject lateinit var relayClient: NostrRelayClient
    @Inject lateinit var peerDao: PeerDao
    @Inject lateinit var sharingConfigDao: PeerSharingConfigDao
    @Inject lateinit var heartbeatDao: HeartbeatDao
    @Inject lateinit var prefs: AppPreferences
    @Inject lateinit var intervalManager: AdaptiveIntervalManager
    @Inject lateinit var notificationManager: NotificationManager

    private lateinit var fusedLocation: FusedLocationProviderClient
    private val handler = Handler(Looper.getMainLooper())
    private val locationFilter = LocationFilter()

    @Volatile private var lastLat = 0.0
    @Volatile private var lastLng = 0.0
    @Volatile private var lastAccuracy = 0f
    @Volatile private var lastSpeed = 0f
    @Volatile private var lastBearing = 0f
    @Volatile private var lastAltitude = 0.0
    private var currentSettings = AppSettings()
    @Volatile private var isSos = false
    /** True once an explicit SOS on/off command arrived; blocks the persisted-state
     *  restore, which could otherwise read a stale flag mid-write and undo the command. */
    @Volatile private var sosCommandReceived = false
    @Volatile private var currentMotionState = MotionState.UNKNOWN
    /**
     * Latest sensor-based reading from Activity Recognition, or null until the first
     * transition fires (also when AR is unavailable or its permission was denied).
     * Fused with [currentMotionState] to produce the stored/broadcast label — see
     * [MotionFusion.fuse]. Interval selection stays keyed off the GPS state.
     */
    @Volatile private var arMotionState: MotionState? = null
    private var candidateMotionState = MotionState.UNKNOWN
    private var candidateMotionCount = 0
    private var prevFixLat = 0.0
    private var prevFixLng = 0.0
    private var prevFixElapsedNs = 0L
    private var prevFixAccuracy = 0f
    private var wasLowBattery = false
    private var stationaryAnchorSet = false
    private var stationaryAnchorLat = 0.0
    private var stationaryAnchorLng = 0.0
    private var stationaryAnchorAcc = 0f
    @Volatile private var lastPulseElapsedMs = 0L
    /** Elapsed-time deadline until which GPS is boosted to high accuracy (see CLASSIFY_BOOST_MS). */
    @Volatile private var classificationBoostUntilMs = 0L

    private var isStarted = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                // Outlier fixes are dropped wholesale — before motion
                // classification too, since a glitch fix also poisons the
                // displacement-derived speed of the fix after it.
                if (!locationFilter.accept(loc.latitude, loc.longitude, loc.accuracy, loc.elapsedRealtimeNanos)) {
                    Log.d(TAG, "Dropped implausible fix: acc=${loc.accuracy}m")
                    return
                }
                lastLat = loc.latitude
                lastLng = loc.longitude
                lastAccuracy = loc.accuracy
                lastBearing = if (loc.hasBearing()) loc.bearing else 0f
                // GPS altitude is often missing (indoors, cold fix); keep the last known
                // reading rather than flicker to a misleading 0 m / sea level.
                if (loc.hasAltitude()) lastAltitude = loc.altitude
                val speed = estimateSpeed(loc)
                lastSpeed = speed ?: 0f
                speed?.let { onMotionSample(MotionMath.classify(it)) }
                if (currentMotionState == MotionState.STATIONARY) checkStationaryExit(loc)
                // If no heartbeat has gone out yet (empty lastLocation cache at start,
                // so the initial pulse was skipped), send one now that a usable fix
                // exists. Skip when the fix fails the accuracy gate: it would only be
                // withheld again, re-triggering this on every fix until GPS sharpens.
                if (isStarted && lastPulseElapsedMs == 0L && !sendGateBlocks(loc.accuracy)) pulseNow()
            }
        }
    }

    // Low-power (network) fixes often carry no speed, so fall back to
    // displacement over time; without this a stationary device that starts
    // driving could never leave the STATIONARY state.
    private fun estimateSpeed(loc: android.location.Location): Float? {
        val derived = if (prevFixElapsedNs > 0L) {
            val dtSec = (loc.elapsedRealtimeNanos - prevFixElapsedNs) / 1_000_000_000f
            // Cap the window at 240s: past that the previous fix is likely a doze gap or
            // a LocationFilter stale-accept (its threshold is 5 min), and dividing a large
            // displacement over such a gap would fabricate a one-off high speed.
            if (dtSec in 1f..240f) {
                val dist = FloatArray(1)
                android.location.Location.distanceBetween(
                    prevFixLat, prevFixLng, loc.latitude, loc.longitude, dist,
                )
                // Once moving, cap the accuracy subtraction so a coarse fix can't zero out
                // real speed and demote the state; while stationary/unknown keep the fully
                // conservative reading that rejects parked drift.
                val subtractCap = if (currentMotionState.isMoving()) {
                    MotionMath.MOVING_ACCURACY_SUBTRACT_CAP_M
                } else {
                    Float.MAX_VALUE
                }
                MotionMath.derivedSpeedMps(dist[0], dtSec, prevFixAccuracy + loc.accuracy, subtractCap)
            } else null
        } else null
        prevFixLat = loc.latitude
        prevFixLng = loc.longitude
        prevFixElapsedNs = loc.elapsedRealtimeNanos
        prevFixAccuracy = loc.accuracy
        return if (loc.hasSpeed()) loc.speed else derived
    }

    // Require consecutive fixes to agree before switching state (see
    // MotionMath.samplesRequiredToSwitch), so a single noisy GPS speed sample
    // doesn't churn the location request and pulse schedule.
    private fun onMotionSample(newState: MotionState) {
        if (newState == currentMotionState) {
            candidateMotionCount = 0
            return
        }
        if (newState == candidateMotionState) {
            candidateMotionCount++
        } else {
            candidateMotionState = newState
            candidateMotionCount = 1
        }
        val required = MotionMath.samplesRequiredToSwitch(currentMotionState, newState)
        if (candidateMotionCount >= required || currentMotionState == MotionState.UNKNOWN) {
            val previous = currentMotionState
            currentMotionState = newState
            candidateMotionCount = 0
            if (newState == MotionState.STATIONARY) {
                stationaryAnchorSet = true
                stationaryAnchorLat = lastLat
                stationaryAnchorLng = lastLng
                stationaryAnchorAcc = lastAccuracy
            }
            // Just started moving from a settled state: boost GPS so the classifier can
            // reach the correct moving state (esp. DRIVING) on clean fixes.
            if (newState.isMoving() && !previous.isMoving()) beginClassificationBoost()
            intervalManager.updateMotionState(newState)
            updateLocationRequest()
            reschedulePulse()
        }
    }

    /**
     * Arm the high-accuracy GPS window for [CLASSIFY_BOOST_MS] and schedule a re-request
     * so it falls back to the settled state's power profile once the window elapses.
     * Called when the device leaves a settled place, where the coarse stationary-grade
     * fixes would otherwise underclassify the new motion. The caller is expected to
     * refresh the location request (via the [updateLocationRequest] it already issues on
     * a state change) so the boost takes effect immediately.
     *
     * A single settled exit can reach here twice — first from [checkStationaryExit]
     * (STATIONARY -> UNKNOWN), then from the fix that latches UNKNOWN -> moving. An
     * already-running window is left as-is rather than re-armed, so the total boost
     * never exceeds [CLASSIFY_BOOST_MS] from the first trigger.
     */
    private fun beginClassificationBoost() {
        val now = SystemClock.elapsedRealtime()
        if (now < classificationBoostUntilMs) return
        classificationBoostUntilMs = now + CLASSIFY_BOOST_MS
        handler.removeCallbacks(locationBoostDowngrade)
        handler.postDelayed(locationBoostDowngrade, CLASSIFY_BOOST_MS)
    }

    private val locationBoostDowngrade = Runnable {
        // Boost window elapsed; re-request so GPS returns to the current state's profile.
        if (isStarted) updateLocationRequest()
    }

    /**
     * Escape hatch for the STATIONARY state. The low-power fixes used while
     * stationary can be so coarse (cell-tower fixes are off by hundreds of metres
     * to kilometres) that the distance travelled between fixes never exceeds their
     * accuracy radius, so speed-based detection reads 0 and the device stays
     * STATIONARY for an entire drive — and never upgrades to GPS to find out.
     * Instead compare against where the device *became* stationary: total
     * displacement grows without bound while driving, so once it clears the
     * combined uncertainty the device has provably moved. Exit to UNKNOWN — which
     * latches on the very next classified fix rather than asserting a possibly-wrong
     * WALKING — and boost GPS so that fix carries clean speed.
     */
    private fun checkStationaryExit(loc: android.location.Location) {
        if (!stationaryAnchorSet) return
        val dist = FloatArray(1)
        android.location.Location.distanceBetween(
            stationaryAnchorLat, stationaryAnchorLng, loc.latitude, loc.longitude, dist
        )
        // Tighten the anchor when a strictly better fix confirms the same place,
        // so a coarse initial anchor doesn't leave the exit threshold needlessly wide.
        if (MotionMath.shouldTightenAnchor(dist[0], stationaryAnchorAcc, loc.accuracy)) {
            stationaryAnchorLat = loc.latitude
            stationaryAnchorLng = loc.longitude
            stationaryAnchorAcc = loc.accuracy
            return
        }
        if (MotionMath.shouldExitStationary(dist[0], stationaryAnchorAcc, loc.accuracy)) {
            stationaryAnchorSet = false
            currentMotionState = MotionState.UNKNOWN
            candidateMotionCount = 0
            beginClassificationBoost()
            intervalManager.updateMotionState(MotionState.UNKNOWN)
            updateLocationRequest()
            reschedulePulse()
        }
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            val now = java.util.Calendar.getInstance()
            val dayIndex = when (now[java.util.Calendar.DAY_OF_WEEK]) {
                java.util.Calendar.MONDAY    -> 0
                java.util.Calendar.TUESDAY   -> 1
                java.util.Calendar.WEDNESDAY -> 2
                java.util.Calendar.THURSDAY  -> 3
                java.util.Calendar.FRIDAY    -> 4
                java.util.Calendar.SATURDAY  -> 5
                java.util.Calendar.SUNDAY    -> 6
                else                         -> 0
            }
            val currentMinute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
            val scheduleActive = isSos || SharingSchedule.isActive(currentSettings.globalScheduleRules, dayIndex, currentMinute)

            if (scheduleActive) {
                // If we were previously off-schedule, the GPS might be stopped. 
                // Restart it immediately upon entering the active window.
                if (lastPulseElapsedMs != 0L && !isSos && !SharingSchedule.isActive(currentSettings.globalScheduleRules, dayIndex, (currentMinute - 1).let { if (it < 0) 1439 else it })) {
                    updateLocationRequest()
                }

                if (broadcastHeartbeat()) {
                    lastPulseElapsedMs = SystemClock.elapsedRealtime()
                }
            } else {
                Log.d(TAG, "Heartbeat suppressed: outside global schedule. Suspending GPS.")
                // Stop GPS to save battery during off-hours
                try {
                    fusedLocation.removeLocationUpdates(locationCallback)
                } catch (e: Exception) {}
            }

            val interval = intervalManager.getIntervalMillis(currentSettings).coerceAtLeast(5000L)
            handler.postDelayed(this, interval)
            
            // Only arm the doze backstop for significant intervals to avoid excessive system calls
            if (interval >= 300_000L) {
                armDozeBackstop(interval)
            }
        }
    }

    /**
     * Doze defers both handler callbacks and low-power location fixes, so a phone
     * left untouched can stretch pins far past the stationary interval (and trip
     * peers' missed-heartbeat alerts). This alarm punches through idle and re-runs
     * the pulse check; it is a backstop, not the scheduler — the slack means the
     * handler wins whenever the device is awake, making the check a no-op. It also
     * revives the service if it died: alarm delivery grants the temporary allowlist
     * that permits the foreground-service start.
     */
    private fun armDozeBackstop(delayMs: Long) {
        try {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs + PULSE_BACKSTOP_SLACK_MS,
                pulseCheckIntent()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to arm doze backstop alarm", e)
        }
    }

    private fun pulseCheckIntent(): PendingIntent =
        PendingIntent.getForegroundService(
            this, 0,
            Intent(this, HeartbeatService::class.java).setAction(ACTION_PULSE_CHECK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        lifecycleScope.launch {
            prefs.settings.collect { settings ->
                val previous = currentSettings
                currentSettings = settings
                // Re-anchor the pending pulse when an interval setting changes so the
                // new cadence applies now rather than after the next heartbeat.
                if (isStarted &&
                    intervalManager.getIntervalMillis(settings) != intervalManager.getIntervalMillis(previous)
                ) {
                    reschedulePulse()
                }
            }
        }
        lifecycleScope.launch {
            // Room re-emits on every peers-table write; only reschedule when the
            // recipient list itself changed.
            peerDao.getPeersReceivingMyLocation().distinctUntilChanged().collect {
                if (isStarted) reschedulePulse()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ensure service is in foreground immediately to prevent "Unable to start service" crashes on Android 8.0+
        // We call this BEFORE super.onStartCommand to be as fast as possible.
        startForegroundAndBroadcast()

        super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            ACTION_SOS_ON -> {
                Log.i(TAG, "SOS Mode ACTIVATED")
                sosCommandReceived = true
                isSos = true
                intervalManager.setSosMode(enabled = true)
                updateLocationRequest()
                updateServiceNotification()
                // Force an immediate location fetch if we don't have one, then pulse
                if (lastLat == 0.0 && lastLng == 0.0) {
                    forceLocationFetchThenPulse()
                } else {
                    pulseNow()
                }
            }
            ACTION_SOS_OFF -> {
                Log.i(TAG, "SOS Mode DEACTIVATED")
                sosCommandReceived = true
                isSos = false
                intervalManager.setSosMode(enabled = false)
                updateLocationRequest()
                updateServiceNotification()
                reschedulePulse()
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            // Doze backstop fired: catch up if the handler was frozen past the
            // interval, otherwise this just re-arms the schedule.
            ACTION_PULSE_CHECK -> reschedulePulse()
            ACTION_ACTIVITY_TRANSITION -> handleActivityTransition(intent)
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startForegroundAndBroadcast() {
        try {
            val notification = buildNotification()
            val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasLocationPerm = hasFineLocation || hasCoarseLocation

            when {
                Build.VERSION.SDK_INT >= 34 -> { // UPSIDE_DOWN_CAKE (Android 14)
                    if (hasLocationPerm) {
                        // On Android 14+, FOREGROUND_SERVICE_LOCATION must be in manifest (checked at runtime too)
                        val hasFgsLocationPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (hasFgsLocationPerm) {
                            startForeground(NOTIFICATION_ID_HEARTBEAT, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                        } else {
                            Log.e(TAG, "Missing FOREGROUND_SERVICE_LOCATION permission in manifest")
                            stopSelf()
                            return
                        }
                    } else {
                        Log.e(TAG, "Cannot start location FGS: runtime location permission missing")
                        stopSelf()
                        return
                    }
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    startForeground(NOTIFICATION_ID_HEARTBEAT, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                }
                else -> {
                    startForeground(NOTIFICATION_ID_HEARTBEAT, notification)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: failed to start foreground service: ${e.message}")
            stopSelf()
            return
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            stopSelf()
            return
        }

        if (isStarted) return
        isStarted = true

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            updateLocationRequest()
        } else {
            Log.e(TAG, "Cannot start location updates: permission missing")
        }

        startActivityRecognition()

        lifecycleScope.launch {
            try {
                currentSettings = prefs.settings.first()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load settings; using defaults", e)
            }
            // The service is sticky and restarts with a null intent after process
            // death, so an active SOS must be restored from the persisted flag.
            if (currentSettings.sosActive && !isSos && !sosCommandReceived) {
                isSos = true
                intervalManager.setSosMode(enabled = true)
                updateLocationRequest()
            }
            relayClient.connect()
            // Pre-seed location from the OS cache so the very first heartbeat isn't dropped
            // by the lastLat/lastLng == 0.0 guard before the location callback has a chance to fire.
            seedLocationFromLastKnown()
            reschedulePulse()
        }
    }

    private fun buildLocationRequest(): LocationRequest {
        // Below 20% battery the heartbeat cadence already stretches to the
        // low-battery interval; keeping full-rate GPS running would defeat it.
        val lowBattery = !isSos && intervalManager.isLowBattery()
        // A brief high-accuracy window right after leaving a settled place, so the
        // classifier reaches the right moving state before GPS relaxes. Never overrides
        // low battery (which must stay frugal) and is moot while stationary.
        val boosting = !isSos && !lowBattery &&
            currentMotionState != MotionState.STATIONARY &&
            SystemClock.elapsedRealtime() < classificationBoostUntilMs
        val priority = when {
            isSos -> Priority.PRIORITY_HIGH_ACCURACY
            lowBattery -> Priority.PRIORITY_LOW_POWER
            boosting -> Priority.PRIORITY_HIGH_ACCURACY
            currentMotionState == MotionState.DRIVING -> Priority.PRIORITY_HIGH_ACCURACY
            currentMotionState == MotionState.STATIONARY -> Priority.PRIORITY_LOW_POWER
            else -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val pollIntervalMs = when {
            isSos -> 10_000L
            lowBattery -> 120_000L
            currentMotionState == MotionState.STATIONARY -> 300_000L // 5 min for stationary
            boosting -> 15_000L
            else -> 30_000L
        }
        return LocationRequest.Builder(priority, pollIntervalMs)
            .setMinUpdateIntervalMillis(pollIntervalMs / 2)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun updateLocationRequest() {
        // If the global schedule is inactive, don't start location updates.
        // The heartbeatRunnable will re-check and resume them when the schedule re-opens.
        if (!isSos && !SharingSchedule.isActive(currentSettings.globalScheduleRules)) {
            Log.d(TAG, "updateLocationRequest suppressed: outside global schedule")
            try {
                fusedLocation.removeLocationUpdates(locationCallback)
            } catch (_: Exception) {}
            return
        }

        try {
            fusedLocation.removeLocationUpdates(locationCallback)
            fusedLocation.requestLocationUpdates(buildLocationRequest(), locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to request location updates: missing permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update location request", e)
            if (isDeadObject(e)) {
                fusedLocation = LocationServices.getFusedLocationProviderClient(this)
                try {
                    fusedLocation.requestLocationUpdates(buildLocationRequest(), locationCallback, Looper.getMainLooper())
                } catch (e2: Exception) {
                    Log.e(TAG, "Location update retry failed", e2)
                }
            }
        }
    }

    private fun isDeadObject(e: Exception): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is android.os.DeadObjectException) return true
            cause = cause.cause
        }
        return false
    }

    /** True when Activity Recognition can be used: the runtime permission exists on
     *  Android 10+, and is a normal install-time permission (auto-granted) below that. */
    private fun hasActivityRecognitionPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun activityTransitionIntent(): PendingIntent =
        PendingIntent.getForegroundService(
            this, 1,
            Intent(this, HeartbeatService::class.java).setAction(ACTION_ACTIVITY_TRANSITION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /**
     * Subscribe to Activity Recognition transitions so a sensor-based motion reading
     * corroborates the GPS classifier (see [MotionFusion]). Only ENTER transitions are
     * requested — the most recent one is the current activity. Gracefully no-ops when
     * the permission is absent; the service then simply runs GPS-only as before.
     */
    @SuppressLint("MissingPermission")
    private fun startActivityRecognition() {
        if (!hasActivityRecognitionPermission()) {
            Log.d(TAG, "Activity Recognition unavailable; motion labels use GPS only")
            return
        }
        val transitions = listOf(
            DetectedActivity.STILL,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.IN_VEHICLE,
        ).map { type ->
            ActivityTransition.Builder()
                .setActivityType(type)
                .setActivityTransitionType(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        }
        try {
            ActivityRecognition.getClient(this)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), activityTransitionIntent())
                .addOnFailureListener { Log.w(TAG, "Failed to subscribe to activity transitions", it) }
        } catch (e: Exception) {
            Log.w(TAG, "Activity Recognition subscription error", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopActivityRecognition() {
        if (!hasActivityRecognitionPermission()) return
        try {
            ActivityRecognition.getClient(this)
                .removeActivityTransitionUpdates(activityTransitionIntent())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove activity transition updates", e)
        }
    }

    /** Apply the newest ENTER transition to [arMotionState]; ignore types with no
     *  usable motion signal so a stale-but-valid reading is never clobbered. */
    private fun handleActivityTransition(intent: Intent?) {
        if (intent == null || !ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        result.transitionEvents
            .lastOrNull { it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER }
            ?.let { event ->
                MotionFusion.fromActivityType(event.activityType)?.let { mapped ->
                    arMotionState = mapped
                    Log.d(TAG, "Activity Recognition -> $mapped")
                }
            }
    }

    /** Fire a heartbeat now and restart the pulse schedule from this moment. */
    private fun pulseNow() {
        handler.removeCallbacks(heartbeatRunnable)
        handler.post(heartbeatRunnable)
    }

    @SuppressLint("MissingPermission")
    private fun forceLocationFetchThenPulse() {
        fusedLocation.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    lastLat = loc.latitude
                    lastLng = loc.longitude
                    lastAccuracy = loc.accuracy
                    if (loc.hasBearing()) lastBearing = loc.bearing
                    if (loc.hasAltitude()) lastAltitude = loc.altitude
                    Log.d(TAG, "Force fetch successful")
                } else {
                Log.w(TAG, "Force fetch returned null location")
            }
            pulseNow()
        }.addOnFailureListener {
            Log.e(TAG, "Force fetch failed", it)
            pulseNow()
        }
    }

    /**
     * Recompute the pulse schedule against the current interval without sending an
     * early heartbeat: the next one fires once the interval has elapsed since the
     * last pulse. Firing immediately here caused extra history entries whenever the
     * motion state flapped or the peer list re-emitted while stationary.
     */
    private fun reschedulePulse() {
        handler.removeCallbacks(heartbeatRunnable)
        if (lastPulseElapsedMs == 0L) {
            handler.post(heartbeatRunnable)
            return
        }
        val interval = intervalManager.getIntervalMillis(currentSettings).coerceAtLeast(5000L)
        val elapsed = SystemClock.elapsedRealtime() - lastPulseElapsedMs
        val delay = (interval - elapsed).coerceAtLeast(0L)
        handler.postDelayed(heartbeatRunnable, delay)
        armDozeBackstop(delay)
    }

    /**
     * Sender-side accuracy gate: true when a fix this coarse must be withheld from
     * local history and peers. Never blocks in SOS — a coarse emergency position
     * still beats none. 0 (the default) never blocks.
     */
    private fun sendGateBlocks(accuracyM: Float): Boolean {
        val gate = currentSettings.sendMaxAccuracyMeters
        return !isSos && gate > 0 && accuracyM > gate
    }

    /** Returns false when no heartbeat was recorded because no location fix exists yet. */
    private fun broadcastHeartbeat(isSos: Boolean = this.isSos): Boolean {
        if (lastLat == 0.0 && lastLng == 0.0) {
            Log.d(TAG, "Skipping heartbeat: no location fixed yet")
            return false
        }
        // Sender-side accuracy gate: withhold a fix we don't trust from both local
        // history and peers, so a coarse cell fix doesn't paint a misleading pin.
        if (sendGateBlocks(lastAccuracy)) {
            Log.d(TAG, "Skipping heartbeat: accuracy ${lastAccuracy}m worse than gate ${currentSettings.sendMaxAccuracyMeters}m")
            return false
        }
        val battery = getBatteryLevel()
        intervalManager.updateBattery(battery)
        // Battery is sampled once per heartbeat; when it crosses the low-battery
        // threshold the location request must be rebuilt to match the new profile.
        val lowNow = intervalManager.isLowBattery()
        if (lowNow != wasLowBattery) {
            wasLowBattery = lowNow
            updateLocationRequest()
        }

        lifecycleScope.launch {
            try {
                val (privHex, pubHex) = keyManager.ensureKeypair()
                val settings = prefs.settings.first()

                val nowMs = System.currentTimeMillis()
                val expectedIntervalSec = intervalManager.getIntervalMillis(settings) / 1000
                // Stored/broadcast label fuses the GPS classifier with the Activity
                // Recognition reading; interval selection above stays GPS-driven.
                val motionStateLabel = MotionFusion.fuse(currentMotionState, arMotionState).name
                heartbeatDao.insert(
                    HeartbeatEntity(
                        deviceId = pubHex,
                        displayName = settings.displayName,
                        timestamp = nowMs,
                        lat = lastLat,
                        lng = lastLng,
                        accuracy = lastAccuracy,
                        battery = battery,
                        motionState = motionStateLabel,
                        isSos = isSos,
                        receivedAt = nowMs,
                        pinColor = settings.pinColor,
                        speed = lastSpeed,
                        bearing = lastBearing,
                        altitude = lastAltitude,
                        expectedIntervalSeconds = expectedIntervalSec
                    )
                )
                val retentionDays = settings.localLocationRetentionDays
                if (retentionDays > 0) {
                    val cutoff = nowMs - retentionDays * 24 * 60 * 60 * 1000L
                    heartbeatDao.deleteOlderThanForDevice(pubHex, cutoff)
                }

                val now = java.util.Calendar.getInstance()
                val dayIndex = when (now[java.util.Calendar.DAY_OF_WEEK]) {
                    java.util.Calendar.MONDAY    -> 0
                    java.util.Calendar.TUESDAY   -> 1
                    java.util.Calendar.WEDNESDAY -> 2
                    java.util.Calendar.THURSDAY  -> 3
                    java.util.Calendar.FRIDAY    -> 4
                    java.util.Calendar.SATURDAY  -> 5
                    java.util.Calendar.SUNDAY    -> 6
                    else                         -> 0
                }
                val currentMinute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)

                if (!isSos && !SharingSchedule.isActive(settings.globalScheduleRules, dayIndex, currentMinute)) {
                    Log.d(TAG, "Heartbeat suppressed: outside global schedule")
                    return@launch
                }

                // Recipients: 
                // 1. In SOS mode: send ONLY to contacts marked as SOS contacts.
                // 2. In Normal mode: send to all contacts who receive our location.
                val allPeers = peerDao.getAllPeers().first()
                val configMap = sharingConfigDao.getAll().associateBy { it.peerDeviceId }
                
                val targetRecipients = allPeers.filter { peer ->
                    val cfg = configMap[peer.deviceId]
                    if (isSos) {
                        cfg?.isSosContact == true
                    } else {
                        val roleAllows = peer.locationRole == PeerEntity.ROLE_SEND || 
                                        peer.locationRole == PeerEntity.ROLE_SEND_RECEIVE
                        roleAllows && (cfg?.sharingEnabled ?: true)
                    }
                }

                if (targetRecipients.isEmpty()) {
                    Log.d(TAG, "No recipients for this heartbeat (isSos=$isSos)")
                    return@launch
                }

                var sentCount = 0

                // Move heavy crypto (NIP-44 encryption + Schnorr signing) to Default dispatcher
                // to avoid skipping frames on the main thread.
                withContext(Dispatchers.Default) {
                    targetRecipients.forEach { recipient ->
                        val cfg = configMap[recipient.deviceId]

                        if (!isSos && cfg != null && !SharingSchedule.isActive(cfg.scheduleRules(), dayIndex, currentMinute)) return@forEach

                        // Suburb precision deliberately shares only an approximate area. Coarsen the
                        // position AND drop the fine-grained fields that would otherwise undo it:
                        // exact altitude is a strong location discriminator, and exact speed/bearing
                        // let a recipient dead-reckon a finer track than the ~1 km grid implies.
                        val suburb = cfg?.precisionMode == PrecisionMode.SUBURB.name && !isSos
                        val (sendLat, sendLng) = if (suburb) {
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
                            accuracy = if (suburb) 1100f else lastAccuracy,
                            battery = battery,
                            motionState = motionStateLabel,
                            isSos = isSos,
                            retentionDays = cfg?.retentionDaysLocation ?: 30,
                            pinColor = settings.pinColor,
                            speed = if (suburb) 0f else lastSpeed,
                            bearing = if (suburb) 0f else lastBearing,
                            altitude = if (suburb) 0.0 else lastAltitude,
                            expectedIntervalSeconds = expectedIntervalSec
                        )
                        val payloadJson = Json.encodeToString(payload)

                        val encrypted = crypto.nip44Encrypt(
                            senderPrivKey = crypto.hexToBytes(privHex),
                            recipientXOnlyHex = recipient.publicKeyHex,
                            plaintext = payloadJson
                        )
                        val kind = if (isSos) NostrEventKind.SOS_ALERT else NostrEventKind.HEARTBEAT
                        val event = NostrEvent.build(
                            privKeyHex = privHex,
                            pubKeyHex = pubHex,
                            kind = kind,
                            content = encrypted,
                            tags = listOf(
                                listOf("p", recipient.publicKeyHex),
                                listOf("t", "locapeer-heartbeat")
                            ),
                            crypto = crypto
                        )
                        relayClient.publishEvent(event)
                        sentCount++
                    }
                }
                Log.d(TAG, "Heartbeat sent to $sentCount/${targetRecipients.size} recipients (isSos=$isSos)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send heartbeat", e)
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private suspend fun seedLocationFromLastKnown() {
        if (lastLat != 0.0 || lastLng != 0.0) return
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                fusedLocation.lastLocation.addOnCompleteListener { task ->
                    try {
                        // task.result throws on a failed Task; a crash here would also
                        // leave the continuation suspended and the pulse never scheduled.
                        val loc = if (task.isSuccessful) task.result else null
                        // An old cached position would be broadcast stamped with the
                        // current time; better to wait for the first real fix instead.
                        if (loc != null && System.currentTimeMillis() - loc.time <= MAX_SEED_AGE_MS) {
                            lastLat = loc.latitude
                            lastLng = loc.longitude
                            lastAccuracy = loc.accuracy
                            // Seed the outlier filter too, so the first live fix
                            // is sanity-checked against this cached position.
                            locationFilter.accept(loc.latitude, loc.longitude, loc.accuracy, loc.elapsedRealtimeNanos)
                            Log.d(TAG, "Pre-seeded location from lastLocation cache: $lastLat, $lastLng")
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Could not pre-seed location: ${e.message}")
                    } finally {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not pre-seed location: ${e.message}")
        }
    }

    private fun getBatteryLevel(): Int {
        val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level < 0) 100 else level
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (isSos) "SOS ACTIVE!" else getString(R.string.notification_heartbeat_title)
        val text = if (isSos) "Broadcasting emergency alert to SOS contacts" else getString(R.string.notification_heartbeat_text)
        val icon = if (isSos) R.drawable.ic_notif_alert else R.drawable.ic_notif_location
        val color = if (isSos) 0xFFD32F2F.toInt() else 0xFF1976D2.toInt()

        return NotificationCompat.Builder(this, CHANNEL_ID_HEARTBEAT)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setColor(color)
            .setColorized(isSos)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(if (isSos) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .setCategory(if (isSos) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_SERVICE)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .build()
    }

    private fun updateServiceNotification() {
        notificationManager.notify(NOTIFICATION_ID_HEARTBEAT, buildNotification())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_HEARTBEAT,
            getString(R.string.channel_name_heartbeat),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_desc_heartbeat)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(heartbeatRunnable)
        handler.removeCallbacks(locationBoostDowngrade)
        stopActivityRecognition()
        try {
            (getSystemService(ALARM_SERVICE) as AlarmManager).cancel(pulseCheckIntent())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel doze backstop alarm", e)
        }
        try {
            if (::fusedLocation.isInitialized) {
                fusedLocation.removeLocationUpdates(locationCallback)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to remove updates: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error during onDestroy", e)
        }
        super.onDestroy()
    }
}
