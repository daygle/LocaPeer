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
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
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
/**
 * Only arm the doze backstop for delays at least this long. Short-interval states
 * (moving profiles) don't need it - doze doesn't engage on a moving device, the
 * watchdog worker covers service death - and arming it there costs a full exact-alarm
 * wakeup per pulse. Applied by BOTH schedulers ([reschedulePulse] and the runnable's
 * own re-post): if either armed unconditionally, one stray reschedule would start a
 * self-perpetuating alarm chain (alarm -> PULSE_CHECK -> reschedule -> alarm...) that
 * keeps exact alarms firing at short cadence indefinitely.
 */
private const val DOZE_BACKSTOP_MIN_DELAY_MS = 300_000L
/** Re-check cadence while suspended when the schedule can never match (all-empty
 *  day masks), where there is no next-window time to sleep towards. */
private const val NEVER_ACTIVE_RECHECK_MS = 3_600_000L
/** Ignore cached last-known locations older than this when pre-seeding at startup. */
private const val MAX_SEED_AGE_MS = 10 * 60_000L
/**
 * How long to hold high-accuracy GPS after the device leaves a settled place, so the
 * classifier gets clean speed to reach the right moving state (notably DRIVING, the
 * only state that otherwise requests high accuracy) instead of being stuck on the
 * coarse fixes used while stationary. Auto-expires back to the settled profile.
 */
private const val CLASSIFY_BOOST_MS = 45_000L
/**
 * How long to hold high-accuracy GPS right after the device *settles* into STATIONARY,
 * so a single sharp fix can anchor the resting pin before GPS relaxes to the low-power
 * profile it uses for the rest of the stay. Stationary is where the device spends most
 * of its time and its pin is the one peers watch most, yet its coarse network fixes can
 * sit hundreds of metres off; one short burst per settle buys a sharp anchor for a
 * small, bounded battery cost. Auto-expires back to the stationary profile.
 */
private const val STATIONARY_ANCHOR_BOOST_MS = 20_000L

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
    // Among fixes that clear the filter, picks the sharpest still-current one to report,
    // rather than whichever landed last before the pulse (see FixSelector).
    private val fixSelector = FixSelector()

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
     * Fused with [currentMotionState] to produce the stored/broadcast label - see
     * [MotionFusion.fuse]. Interval selection stays keyed off the GPS state.
     */
    @Volatile private var arMotionState: MotionState? = null
    /** Elapsed-time stamp of the last AR transition (any type, including a repeat of the
     *  current state), so [arIsStale] can strip a silently-dead AR feed of its authority. */
    @Volatile private var arMotionStateAtMs = 0L
    /** The cadence state last pushed into [intervalManager], so [refreshCadenceState]
     *  can detect changes that arrive without a new GPS/AR event (an AR reading going
     *  stale between pulses). */
    private var appliedCadenceState = MotionState.UNKNOWN
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
    /** Elapsed-time deadline until which GPS stays high-accuracy after settling, to sharpen
     *  the resting anchor before relaxing to the stationary profile (see STATIONARY_ANCHOR_BOOST_MS). */
    @Volatile private var stationaryAnchorBoostUntilMs = 0L
    /**
     * True while location updates are stopped because the global schedule is inactive,
     * so the pulse that finds the schedule open again knows to restart GPS. Probing
     * "was the previous minute inactive?" instead is wrong whenever the pulse interval
     * exceeds one minute: the first in-window pulse usually lands well past the first
     * minute, the check reads the window as already-open, and GPS stays off for the
     * whole active window while stale coordinates keep being broadcast.
     */
    @Volatile private var gpsSuspendedBySchedule = false
    /**
     * True from the moment GPS resumes after a schedule suspension until the first live
     * fix lands. While set, pulses skip the broadcast: the cached position predates the
     * off-window (the device may have moved for hours with GPS off) and must not be
     * shipped stamped with the current time. Peers seeing silence until a real fix
     * exists beats peers seeing a confidently-wrong pin. SOS bypasses this everywhere -
     * a stale emergency position still beats none.
     */
    @Volatile private var awaitingFreshFix = false

    private var isStarted = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                // Outlier fixes are dropped wholesale - before motion
                // classification too, since a glitch fix also poisons the
                // displacement-derived speed of the fix after it.
                if (!locationFilter.accept(loc.latitude, loc.longitude, loc.accuracy, loc.elapsedRealtimeNanos)) {
                    Log.d(TAG, "Dropped implausible fix: acc=${loc.accuracy}m")
                    return
                }
                // Classification (and the prev-fix baseline estimateSpeed advances) runs
                // on every accepted fix, independent of which fix is chosen to report.
                val speed = estimateSpeed(loc)
                // Report the sharpest still-current fix in the window, not merely the
                // latest: the selected fix's whole payload moves together so history and
                // peers never see a coarse position stamped with a sharp fix's speed.
                // While stationary the selector's age backstop is disabled, otherwise the
                // 5-min low-power cadence out-ages every held fix and the coarse network
                // fixes would wobble the resting pin (and could push lastAccuracy past
                // the send gate, silencing heartbeats for the whole stay).
                val stationaryHold = currentMotionState == MotionState.STATIONARY
                if (fixSelector.select(loc.latitude, loc.longitude, loc.accuracy, loc.elapsedRealtimeNanos, stationaryHold)) {
                    lastLat = loc.latitude
                    lastLng = loc.longitude
                    lastAccuracy = loc.accuracy
                    lastBearing = if (loc.hasBearing()) loc.bearing else 0f
                    // GPS altitude is often missing (indoors, cold fix); keep the last known
                    // reading rather than flicker to a misleading 0 m / sea level.
                    if (loc.hasAltitude()) lastAltitude = loc.altitude
                    lastSpeed = speed ?: 0f
                }
                speed?.let { onMotionSample(MotionMath.classify(it)) }
                if (currentMotionState == MotionState.STATIONARY) checkStationaryExit(loc)
                // Two cases need a pulse as soon as a usable fix exists: no heartbeat has
                // gone out yet (empty lastLocation cache at start, so the initial pulse
                // was skipped), or GPS just resumed after a schedule suspension and the
                // broadcast was held for a fresh fix. Gate on the accuracy of the fix
                // actually being reported (lastAccuracy, i.e. post-selector): a blocked
                // pulse would only be withheld again, re-triggering on every fix until
                // GPS sharpens.
                if (isStarted && (lastPulseElapsedMs == 0L || awaitingFreshFix) &&
                    !sendGateBlocks(lastAccuracy)
                ) {
                    awaitingFreshFix = false
                    pulseNow()
                }
            }
        }
    }

    /**
     * Battery is also sampled once per pulse, but the pulse can be a full low-battery
     * interval away (30-120 min): without these events a device plugged in at 15% kept
     * crawling at the low-battery cadence for up to that long after charging past the
     * threshold, and a device draining fast between long stationary pulses kept
     * full-rate GPS running well below 20%. The receiver only does work when the
     * low-battery state actually flips.
     */
    private val batteryEventReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (!isStarted) return
            intervalManager.updateBattery(getBatteryLevel())
            val lowNow = intervalManager.isLowBattery()
            if (lowNow != wasLowBattery) {
                wasLowBattery = lowNow
                updateLocationRequest()
                reschedulePulse()
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

    /**
     * True when the AR reading is old enough that a missed transition is plausible
     * (e.g. the STILL after parking never arrived). See [MotionFusion.AR_STALE_MS]
     * for how staleness changes the fusion rules.
     */
    private fun arIsStale(): Boolean =
        arMotionState != null &&
            SystemClock.elapsedRealtime() - arMotionStateAtMs > MotionFusion.AR_STALE_MS

    /**
     * State that drives pulse cadence and GPS power: the GPS classifier fused with the
     * latest Activity Recognition reading (see [MotionFusion.fuseForInterval]). Recomputed
     * wherever either input changes so a stale GPS DRIVING doesn't keep the radio hot while
     * AR reports the user is on foot.
     */
    private fun effectiveIntervalState(): MotionState =
        MotionFusion.fuseForInterval(currentMotionState, arMotionState, arIsStale())

    /**
     * Recompute the cadence state and, when it changed, push it into the interval
     * manager. Returns whether it changed so callers can decide to rebuild the
     * location request / pulse schedule. Called from every GPS/AR event AND once per
     * pulse: the cadence can change with no new event at all when the AR reading
     * crosses [MotionFusion.AR_STALE_MS] - without the per-pulse call, a missed AR
     * STILL would keep a parked device on the driving profile forever.
     */
    private fun refreshCadenceState(): Boolean {
        val cadence = effectiveIntervalState()
        if (cadence == appliedCadenceState) return false
        appliedCadenceState = cadence
        intervalManager.updateMotionState(cadence)
        return true
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
                // Just settled: hold high accuracy briefly so a sharp fix can anchor the
                // resting pin (and tighten the exit anchor) before GPS drops to low power.
                beginStationaryAnchorBoost()
            }
            // Just started moving from a settled state: boost GPS so the classifier can
            // reach the correct moving state (esp. DRIVING) on clean fixes.
            if (newState.isMoving() && !previous.isMoving()) beginClassificationBoost()
            refreshCadenceState()
            // Unconditional even when the fused cadence didn't change: the boost windows
            // armed above alter the request independently of the cadence state.
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
     * A single settled exit can reach here twice - first from [checkStationaryExit]
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
     * Arm the post-settle high-accuracy window for [STATIONARY_ANCHOR_BOOST_MS] and
     * schedule a re-request that relaxes GPS back to the stationary profile once it
     * elapses. Unlike [beginClassificationBoost] this fires *while* STATIONARY, so
     * [buildLocationRequest] gives it its own branch. Each genuine settle re-arms the
     * window from scratch; the caller refreshes the request so the boost takes effect at
     * once. The caller already re-requests on the state change, and the FixSelector plus
     * the exit-anchor tighten logic pick up the sharper fixes the window produces.
     */
    private fun beginStationaryAnchorBoost() {
        stationaryAnchorBoostUntilMs = SystemClock.elapsedRealtime() + STATIONARY_ANCHOR_BOOST_MS
        handler.removeCallbacks(stationaryAnchorDowngrade)
        handler.postDelayed(stationaryAnchorDowngrade, STATIONARY_ANCHOR_BOOST_MS)
    }

    private val stationaryAnchorDowngrade = Runnable {
        // Anchor window elapsed; re-request so stationary GPS relaxes to low power.
        if (isStarted) updateLocationRequest()
    }

    /**
     * Escape hatch for the STATIONARY state. The low-power fixes used while
     * stationary can be so coarse (cell-tower fixes are off by hundreds of metres
     * to kilometres) that the distance travelled between fixes never exceeds their
     * accuracy radius, so speed-based detection reads 0 and the device stays
     * STATIONARY for an entire drive - and never upgrades to GPS to find out.
     * Instead compare against where the device *became* stationary: total
     * displacement grows without bound while driving, so once it clears the
     * combined uncertainty the device has provably moved. Exit to UNKNOWN - which
     * latches on the very next classified fix rather than asserting a possibly-wrong
     * WALKING - and boost GPS so that fix carries clean speed.
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
            refreshCadenceState()
            updateLocationRequest()
            reschedulePulse()
        }
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            // Backstop against posts that land after onDestroy (which clears pending
            // callbacks before this could be re-posted): without it one stale post
            // re-arms the self-rescheduling loop in a destroyed service.
            if (!isStarted) return

            // Re-sample the inputs that can go stale with no event of their own, BEFORE
            // any early return: battery (previously only sampled deep inside a successful
            // broadcast, so a blocked heartbeat could leave full-rate GPS running at 5%,
            // and recovery after charging waited a whole low-battery interval) and the
            // fused cadence state (an AR reading crossing its staleness threshold changes
            // the cadence with no new GPS/AR event - see refreshCadenceState).
            intervalManager.updateBattery(getBatteryLevel())
            val lowNow = intervalManager.isLowBattery()
            var requestStale = refreshCadenceState()
            if (lowNow != wasLowBattery) {
                wasLowBattery = lowNow
                requestStale = true
            }

            val scheduleActive = isSos || SharingSchedule.isActive(currentSettings.globalScheduleRules)
            if (!scheduleActive) {
                Log.d(TAG, "Heartbeat suppressed: outside global schedule. Suspending GPS.")
                // Stop GPS to save battery during off-hours, then sleep until the window
                // actually opens instead of re-checking at the motion cadence: the rules
                // are fully known, so pulsing every few minutes all night (each pulse a
                // wakeup, some an exact alarm) buys nothing. Rule edits mid-sleep are
                // handled by the settings collector, and a DST shift is self-correcting -
                // the woken pulse re-checks and re-sleeps on the recomputed gap.
                suspendLocationUpdatesForSchedule()
                val (dayIndex, minute) = SharingSchedule.nowDayMinute()
                val minutesUntilOpen = SharingSchedule.minutesUntilNextActive(
                    currentSettings.globalScheduleRules, dayIndex, minute
                )
                val delay = minutesUntilOpen?.let { (it * 60_000L).coerceAtLeast(60_000L) }
                    ?: NEVER_ACTIVE_RECHECK_MS
                handler.postDelayed(this, delay)
                if (delay >= DOZE_BACKSTOP_MIN_DELAY_MS) armDozeBackstop(delay)
                return
            }

            // GPS was stopped while off-schedule (restarting it arms the fresh-fix hold),
            // or its profile inputs changed above; rebuild the request either way.
            if (gpsSuspendedBySchedule || requestStale) updateLocationRequest()

            // While the fresh-fix hold is set the cached position predates the schedule
            // window and must not be broadcast; the location callback pulses as soon as
            // a live fix lands. SOS bypasses the hold - stale beats silent in an emergency.
            if ((isSos || !awaitingFreshFix) && broadcastHeartbeat()) {
                lastPulseElapsedMs = SystemClock.elapsedRealtime()
            }

            val interval = intervalManager.getIntervalMillis(currentSettings).coerceAtLeast(5000L)
            handler.postDelayed(this, interval)
            if (interval >= DOZE_BACKSTOP_MIN_DELAY_MS) armDozeBackstop(interval)
        }
    }

    /**
     * Doze defers both handler callbacks and low-power location fixes, so a phone
     * left untouched can stretch pins far past the stationary interval (and trip
     * peers' missed-heartbeat alerts). This alarm punches through idle and re-runs
     * the pulse check; it is a backstop, not the scheduler - the slack means the
     * handler wins whenever the device is awake, making the check a no-op. It also
     * revives the service if it died: alarm delivery grants the temporary allowlist
     * that permits the foreground-service start.
     */
    private fun armDozeBackstop(delayMs: Long) {
        try {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            val triggerAt = SystemClock.elapsedRealtime() + delayMs + PULSE_BACKSTOP_SLACK_MS
            // Exact when permitted: on Android 12+ only exact-alarm delivery grants the
            // temporary allowlist that allows the revive-if-died foreground-service
            // start; an inexact alarm still reaches the running service but its
            // allowlist forbids the FGS start, so the revival would be silently
            // dropped. Inexact remains the fallback for the pulse-catch-up case.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pulseCheckIntent()
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pulseCheckIntent()
                )
            }
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
        // Charger and battery-threshold events, so the low-battery profile engages and
        // releases promptly instead of waiting for the next (possibly distant) pulse.
        ContextCompat.registerReceiver(
            this,
            batteryEventReceiver,
            android.content.IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        lifecycleScope.launch {
            prefs.settings.collect { settings ->
                val previous = currentSettings
                currentSettings = settings
                if (!isStarted) return@collect
                if (settings.globalScheduleRules != previous.globalScheduleRules) {
                    // Schedule edits must apply NOW, not at the next pulse: while
                    // suspended, the pending pulse can be an entire off-window away, so
                    // a user deleting their schedule (expecting sharing to resume) would
                    // otherwise wait hours. Rebuild the request (which suspends or
                    // resumes GPS as the new rules dictate) and re-anchor the pulse -
                    // after a long suspension the elapsed interval makes it fire at
                    // once, and the fresh-fix hold keeps that pulse honest.
                    updateLocationRequest()
                    reschedulePulse()
                } else if (
                    intervalManager.getIntervalMillis(settings) != intervalManager.getIntervalMillis(previous)
                ) {
                    // Re-anchor the pending pulse when an interval setting changes so the
                    // new cadence applies now rather than after the next heartbeat.
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
        // The power profile follows the fused cadence state, so AR reporting on-foot can
        // relax a stale GPS DRIVING off high accuracy. The boost window still keys off the
        // GPS classifier, since it exists to help that classifier reach the right state.
        val cadenceState = effectiveIntervalState()
        // The mirror of [boosting] for the settle direction: a short high-accuracy burst
        // *while* stationary to sharpen the resting anchor, then back to low power.
        val anchorBoosting = !isSos && !lowBattery &&
            cadenceState == MotionState.STATIONARY &&
            SystemClock.elapsedRealtime() < stationaryAnchorBoostUntilMs
        val priority = when {
            isSos -> Priority.PRIORITY_HIGH_ACCURACY
            lowBattery -> Priority.PRIORITY_LOW_POWER
            boosting -> Priority.PRIORITY_HIGH_ACCURACY
            anchorBoosting -> Priority.PRIORITY_HIGH_ACCURACY
            cadenceState == MotionState.DRIVING -> Priority.PRIORITY_HIGH_ACCURACY
            cadenceState == MotionState.STATIONARY -> Priority.PRIORITY_LOW_POWER
            else -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val pollIntervalMs = when {
            isSos -> 10_000L
            lowBattery -> 120_000L
            // Sample fast during the settle burst so a sharp fix actually lands inside the
            // window; must precede the stationary branch, which otherwise waits 5 minutes.
            anchorBoosting -> 15_000L
            cadenceState == MotionState.STATIONARY -> 300_000L // 5 min for stationary
            boosting -> 15_000L
            else -> 30_000L
        }
        return LocationRequest.Builder(priority, pollIntervalMs)
            .setMinUpdateIntervalMillis(pollIntervalMs / 2)
            // Hold briefly for a sharper first fix after each request reboot instead of
            // returning the first coarse one; the reboot happens on every state change.
            .setWaitForAccurateLocation(true)
            // Fine granularity where the permission allows it, so a fix is never silently
            // coarsened below what the priority already asked for.
            .setGranularity(Granularity.GRANULARITY_FINE)
            // A request reboot may otherwise replay a stale cached fix as the first
            // delivery; require a genuinely fresh one so pulses never ship an old point.
            .setMaxUpdateAgeMillis(0)
            .build()
    }

    private fun suspendLocationUpdatesForSchedule() {
        gpsSuspendedBySchedule = true
        try {
            fusedLocation.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun updateLocationRequest() {
        // If the global schedule is inactive, don't start location updates.
        // The heartbeatRunnable will re-check and resume them when the schedule re-opens.
        if (!isSos && !SharingSchedule.isActive(currentSettings.globalScheduleRules)) {
            Log.d(TAG, "updateLocationRequest suppressed: outside global schedule")
            suspendLocationUpdatesForSchedule()
            return
        }

        if (gpsSuspendedBySchedule) {
            // Resuming after an off-schedule suspension: the cached position predates
            // the window (GPS was off, the device may have moved). Hold broadcasts
            // until a live fix lands (cleared in the location callback) - except in
            // SOS, where a stale position still beats none.
            if (!isSos) awaitingFreshFix = true
            gpsSuspendedBySchedule = false
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
     * requested - the most recent one is the current activity. Gracefully no-ops when
     * the permission is absent; the service then simply runs GPS-only as before.
     */
    @SuppressLint("MissingPermission")
    private fun startActivityRecognition() {
        if (!hasActivityRecognitionPermission()) {
            Log.d(TAG, "Activity Recognition unavailable; motion labels use GPS only")
            return
        }
        val activityTypes: List<Int> = listOf(
            DetectedActivity.STILL,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.IN_VEHICLE,
        )
        val transitions = ArrayList<ActivityTransition>()
        for (activityType in activityTypes) {
            transitions.add(
                ActivityTransition.Builder()
                    .setActivityType(activityType)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build()
            )
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
        // Always attempt removal, even if the permission is now missing: if the user
        // revoked ACTIVITY_RECOGNITION while updates were registered, guarding on the
        // permission here would leave the transition PendingIntent live and keep waking
        // the service. Removal needs no permission; SecurityException is caught below.
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
                    // Every event refreshes the staleness clock, including a re-entry of
                    // the current state: a repeated reading is fresh evidence.
                    arMotionStateAtMs = SystemClock.elapsedRealtime()
                    Log.d(TAG, "Activity Recognition -> $mapped")
                    // A new reading can change the effective cadence state (e.g. AR now
                    // reports on-foot while GPS is a stale DRIVING), so re-apply it to the
                    // interval, the GPS power profile and the pulse schedule. A refreshed
                    // timestamp alone can also un-stale a reading, so recompute even when
                    // the mapped state itself is unchanged.
                    if (isStarted && refreshCadenceState()) {
                        updateLocationRequest()
                        reschedulePulse()
                    }
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
        // getCurrentLocation rather than lastLocation: the cache can be arbitrarily old
        // (or empty), and an SOS position stamped "now" from a days-old cached point is
        // actively misleading. The max-age bound serves an acceptably recent cached fix
        // instantly; otherwise a genuinely fresh high-accuracy fix is requested, with a
        // duration cap so the SOS pulse is never held long. Both listeners run on the
        // main thread and check isStarted so a callback landing after onDestroy can't
        // re-post the (self-rescheduling) heartbeat runnable into a destroyed service.
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(MAX_SEED_AGE_MS)
            .setDurationMillis(15_000L)
            .build()
        try {
            fusedLocation.getCurrentLocation(request, null).addOnSuccessListener { loc ->
                if (loc != null) {
                    lastLat = loc.latitude
                    lastLng = loc.longitude
                    lastAccuracy = loc.accuracy
                    if (loc.hasBearing()) lastBearing = loc.bearing
                    if (loc.hasAltitude()) lastAltitude = loc.altitude
                    // This forced position bypasses the selector; drop its baseline so the
                    // next live fix is taken rather than held against a one-shot fetch.
                    fixSelector.reset()
                    Log.d(TAG, "Force fetch successful")
                } else {
                    // Timed out with no fix; the 10s SOS live request keeps trying, and
                    // the first fix it lands triggers a pulse via the location callback.
                    Log.w(TAG, "Force fetch returned null location")
                }
                if (isStarted) pulseNow()
            }.addOnFailureListener {
                Log.e(TAG, "Force fetch failed", it)
                if (isStarted) pulseNow()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Force fetch unavailable", e)
            if (isStarted) pulseNow()
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
        // Same threshold as the runnable's own re-post (see DOZE_BACKSTOP_MIN_DELAY_MS):
        // arming unconditionally here started an alarm chain at short intervals, since
        // each PULSE_CHECK lands back in this method and re-armed the next alarm.
        if (delay >= DOZE_BACKSTOP_MIN_DELAY_MS) armDozeBackstop(delay)
    }

    /**
     * Sender-side accuracy gate: true when a fix this coarse must be withheld from
     * local history and peers. Never blocks in SOS - a coarse emergency position
     * still beats none. 0 (the default) never blocks.
     */
    private fun sendGateBlocks(accuracyM: Float): Boolean {
        val gate = currentSettings.sendMaxAccuracyMeters
        return !isSos && gate > 0 && accuracyM > gate
    }

    /** Returns false when no heartbeat was recorded because no location fix exists yet. */
    private fun broadcastHeartbeat(isSos: Boolean = this.isSos): Boolean {
        // Snapshot the reported fix once, on the main thread, before anything async:
        // the fields are individually @Volatile but written as a group by the location
        // callback, so reading them again later (especially from the crypto worker
        // below, which runs on Default while fixes keep landing on main) could mix
        // fields of two different fixes - or ship different positions to the local
        // history row and each recipient within one heartbeat.
        val fixLat = lastLat
        val fixLng = lastLng
        val fixAccuracy = lastAccuracy
        val fixSpeed = lastSpeed
        val fixBearing = lastBearing
        val fixAltitude = lastAltitude
        // Stored/broadcast label fuses the GPS classifier with the Activity Recognition
        // reading (staleness-aware); interval selection stays keyed off the cadence state.
        val motionStateLabel = MotionFusion.fuse(currentMotionState, arMotionState, arIsStale()).name

        if (fixLat == 0.0 && fixLng == 0.0) {
            Log.d(TAG, "Skipping heartbeat: no location fixed yet")
            return false
        }
        // Sender-side accuracy gate: withhold a fix we don't trust from both local
        // history and peers, so a coarse cell fix doesn't paint a misleading pin.
        if (sendGateBlocks(fixAccuracy)) {
            Log.d(TAG, "Skipping heartbeat: accuracy ${fixAccuracy}m worse than gate ${currentSettings.sendMaxAccuracyMeters}m")
            return false
        }
        // Low-battery profile transitions are handled by the pulse runnable and the
        // battery event receiver; here the level is only read for the payload.
        val battery = getBatteryLevel()

        lifecycleScope.launch {
            try {
                val (privHex, pubHex) = keyManager.ensureKeypair()
                val settings = prefs.settings.first()

                val nowMs = System.currentTimeMillis()
                val expectedIntervalSec = intervalManager.getIntervalMillis(settings) / 1000
                heartbeatDao.insert(
                    HeartbeatEntity(
                        deviceId = pubHex,
                        displayName = settings.displayName,
                        timestamp = nowMs,
                        lat = fixLat,
                        lng = fixLng,
                        accuracy = fixAccuracy,
                        battery = battery,
                        motionState = motionStateLabel,
                        isSos = isSos,
                        receivedAt = nowMs,
                        pinColor = settings.pinColor,
                        speed = fixSpeed,
                        bearing = fixBearing,
                        altitude = fixAltitude,
                        expectedIntervalSeconds = expectedIntervalSec
                    )
                )
                val retentionDays = settings.localLocationRetentionDays
                if (retentionDays > 0) {
                    val cutoff = nowMs - retentionDays * 24 * 60 * 60 * 1000L
                    heartbeatDao.deleteOlderThanForDevice(pubHex, cutoff)
                }

                val (dayIndex, currentMinute) = SharingSchedule.nowDayMinute()

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
                val nowSec = nowMs / 1000L
                withContext(Dispatchers.Default) {
                    targetRecipients.forEach { recipient ->
                        val cfg = configMap[recipient.deviceId]

                        if (!isSos && cfg != null && !SharingSchedule.isPeerSharingActive(
                                cfg.scheduleRules(), dayIndex, currentMinute,
                                cfg.temporaryShareEndsAtEpochSeconds, nowSec
                            )
                        ) return@forEach

                        // Suburb precision deliberately shares only an approximate area. Coarsen the
                        // position AND drop the fine-grained fields that would otherwise undo it:
                        // exact altitude is a strong location discriminator, and exact speed/bearing
                        // let a recipient dead-reckon a finer track than the ~1 km grid implies.
                        val suburb = cfg?.precisionMode == PrecisionMode.SUBURB.name && !isSos
                        val (sendLat, sendLng) = if (suburb) {
                            SharingSchedule.toSuburbPrecision(fixLat, fixLng)
                        } else {
                            fixLat to fixLng
                        }

                        val payload = HeartbeatPayload(
                            deviceId = pubHex,
                            displayName = settings.displayName,
                            timestamp = Instant.now().toString(),
                            lat = sendLat,
                            lng = sendLng,
                            accuracy = if (suburb) 1100f else fixAccuracy,
                            battery = battery,
                            motionState = motionStateLabel,
                            isSos = isSos,
                            retentionDays = cfg?.retentionDaysLocation ?: 30,
                            pinColor = settings.pinColor,
                            speed = if (suburb) 0f else fixSpeed,
                            bearing = if (suburb) 0f else fixBearing,
                            altitude = if (suburb) 0.0 else fixAltitude,
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
                            // If we just seeded the first location, trigger a pulse immediately 
                            // so the user sees their pin on the map without waiting for the first scheduled tick.
                            if (isStarted && lastPulseElapsedMs == 0L) {
                                pulseNow()
                            }
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
        val title = if (isSos) getString(R.string.notif_sos_active_title) else getString(R.string.notification_heartbeat_title)
        val text = if (isSos) getString(R.string.notif_sos_active_text) else getString(R.string.notification_heartbeat_text)
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
        isStarted = false
        handler.removeCallbacks(heartbeatRunnable)
        handler.removeCallbacks(locationBoostDowngrade)
        handler.removeCallbacks(stationaryAnchorDowngrade)
        try {
            unregisterReceiver(batteryEventReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister battery receiver", e)
        }
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
