package com.locapeer

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.locapeer.settings.AppPreferences
import com.locapeer.subscriber.HeartbeatReceiver
import com.locapeer.subscriber.MissedHeartbeatWorker
import com.locapeer.subscriber.RetentionEnforcementWorker
import com.locapeer.util.DisplayFormat
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "LocaPeerApplication"

@HiltAndroidApp
class LocaPeerApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var heartbeatReceiver: HeartbeatReceiver
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var appLockManager: com.locapeer.settings.AppLockManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Initialise the lock state from the persisted snapshot and start the
        // ProcessLifecycleOwner observer so the foreground/background book-keeping
        // works for BiometricPrompt re-locking. Done synchronously here so the first
        // composition sees the right value.
        appLockManager.onAppStart()
        // Seed display formatting from the device, then keep it in sync with user settings so
        // the app's synchronous time/speed formatters reflect the current preference.
        DisplayFormat.init(this)
        appPreferences.settings
            .onEach {
                DisplayFormat.useImperialSpeed = it.useImperialSpeed
                DisplayFormat.use24HourTime = it.use24HourTime
                DisplayFormat.useImperialElevation = it.useImperialElevation
                DisplayFormat.useImperialDistance = it.useImperialDistance
            }
            .launchIn(appScope)

        // Configure osmdroid before any Activity can create a MapView. Doing this from the
        // background startup coroutine races the first frame: a map opened immediately after
        // launch can construct its tile provider with the default (unconfigured) cache/user-agent
        // settings and remain stuck showing grey tile placeholders.
        try {
            org.osmdroid.config.Configuration.getInstance().apply {
                osmdroidBasePath = filesDir
                osmdroidTileCache = java.io.File(filesDir, "osmdroid/tiles")
                // Load persisted paths/settings first. osmdroid's first-run load also writes its
                // default user-agent, so setting ours before load would silently be overwritten.
                load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
                // Identify the app to public OSM tile servers; a generic/default user-agent
                // can be rejected and leaves osmdroid showing only its tile grid.
                userAgentValue = "LocaPeer/${BuildConfig.VERSION_NAME} (https://github.com/daygle/LocaPeer)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure osmdroid", e)
        }

        // Offload the remaining heavy initialization (networking setup and WorkManager) to
        // background so it cannot delay the first UI frame.
        appScope.launch(Dispatchers.IO) {
            try {
                heartbeatReceiver.start()

                val workManager = try {
                    WorkManager.getInstance(this@LocaPeerApplication)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get WorkManager instance", e)
                    null
                }

                workManager?.let { wm ->
                    MissedHeartbeatWorker.schedule(wm)
                    RetentionEnforcementWorker.schedule(wm)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background initialization failure", e)
            }
        }
    }
}
