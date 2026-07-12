package com.locapeer.settings

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Process-singleton gate for the app-lock screen.
 *
 * Holds the single source of truth for "is the app currently unlocked?". Lives in the
 * process scope (not a Hilt ViewModel) so the value survives Activity recreation - the
 * lock screen is not the same Activity instance after a configuration change, but it
 * is the same process.
 *
 * Activity [android.app.Activity.onStop] is the wrong hook for tracking background time:
 * it fires on screen rotation and on transient system permission dialogs, which would
 * re-lock the app in moments the user perceives as still using it. [ProcessLifecycleOwner]
 * observes the whole process and emits ON_STOP only when the last Activity in the
 * process passes the lifecycle threshold, so the lock reflects genuine process
 * backgrounding.
 */
@Singleton
class AppLockManager @Inject constructor(
    private val prefs: AppPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _unlocked = MutableStateFlow(true)
    /** False means the AppLockScreen should be shown over the rest of the UI. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    // Written and read from coroutines dispatched off the two ProcessLifecycleOwner
    // callbacks (ON_STOP/ON_START), which run on different Dispatchers.Default threads.
    // @Volatile guarantees each foreground read sees the timestamp the last background
    // write published.
    @Volatile private var backgroundedAtMs: Long = 0L
    private var observerInstalled = false

    /**
     * Read the current snapshot of preferences synchronously so the very first frame
     * composes the right lock state, then start reacting to subsequent changes.
     *
     * The previous implementation launched a coroutine and set [_unlocked] from inside
     * its body, which let MainActivity compose a frame with `_unlocked = true` (the
     * default) before the DataStore read resolved. With the lock enabled, that produced
     * a visible flash of the unlocked UI before snapping to the AppLockScreen.
     *
     * Run-blocking on the main thread here is safe: DataStore caches the most-recently
     * emitted [AppSettings] in memory after the first read, so subsequent `first()` calls
     * resolve in microseconds - the cost is the equivalent of a getter call rather than a
     * disk read.
     */
    fun onAppStart() {
        val initialLocked = runBlocking { prefs.settings.first().appLockEnabled }
        _unlocked.value = !initialLocked

        scope.launch {
            // React to runtime pref changes - the user toggling the lock off while the app
            // is open should immediately drop back to the unlocked state. Locking-on-enable
            // is intentionally NOT modelled here so we don't re-lock the user mid-session;
            // re-locking happens through the foreground/background observer below.
            prefs.settings
                .map { it.appLockEnabled }
                .distinctUntilChanged()
                .drop(1) // skip the value we just applied synchronously
                .collect { enabled ->
                    if (!enabled) _unlocked.value = true
                }
        }
        installObserverIfNeeded()
    }

    fun setUnlocked(unlocked: Boolean) {
        _unlocked.value = unlocked
        if (unlocked) backgroundedAtMs = 0L
    }

    /**
     * Called when the process is brought to the foreground. If the time spent in the
     * background exceeded the user's grace period, re-lock the app so a fresh
     * biometric/device-credential prompt will fire at the next rendering.
     */
    private fun onProcessForeground() {
        scope.launch {
            val current = prefs.settings.first()
            // Capture the recorded background timestamp before clearing it: the guard
            // below needs to distinguish "no background was recorded" from "just came
            // back", and that state lives in this field, not in the reset value.
            val backgroundedAt = backgroundedAtMs
            backgroundedAtMs = 0L
            // No recorded background time means the process never went away (cold
            // start handled by [onAppStart]). Don't auto-relock.
            if (!current.appLockEnabled || backgroundedAt == 0L) return@launch
            val timeoutMs = current.appLockTimeoutSeconds * 1000L
            if (System.currentTimeMillis() - backgroundedAt >= timeoutMs) _unlocked.value = false
        }
    }

    private fun onProcessBackground() {
        scope.launch {
            val current = prefs.settings.first()
            if (!current.appLockEnabled) return@launch
            backgroundedAtMs = System.currentTimeMillis()
        }
    }

    private fun installObserverIfNeeded() {
        if (observerInstalled) return
        val owner: LifecycleOwner = ProcessLifecycleOwner.get()
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = onProcessForeground()
            override fun onStop(owner: LifecycleOwner) = onProcessBackground()
        })
        observerInstalled = true
    }
}
