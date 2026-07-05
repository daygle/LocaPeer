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
import javax.inject.Inject

private const val TAG = "LocaPeerApplication"

@HiltAndroidApp
class LocaPeerApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var heartbeatReceiver: HeartbeatReceiver
    @Inject lateinit var appPreferences: AppPreferences

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Seed display formatting from the device, then keep it in sync with user settings so
        // the app's synchronous time/speed formatters reflect the current preference.
        DisplayFormat.initClockDefault(this)
        appPreferences.settings
            .onEach {
                DisplayFormat.useImperialSpeed = it.useImperialSpeed
                DisplayFormat.use24HourTime = it.use24HourTime
                DisplayFormat.useImperialElevation = it.useImperialElevation
            }
            .launchIn(appScope)
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = filesDir
            osmdroidTileCache = java.io.File(filesDir, "osmdroid/tiles")
            // Load configuration from shared preferences (recommended by OSMDroid)
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        }
        try {
            heartbeatReceiver.start()
            
            val workManager = try {
                WorkManager.getInstance(this)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get WorkManager instance", e)
                null
            }

            workManager?.let { wm ->
                MissedHeartbeatWorker.schedule(wm)
                RetentionEnforcementWorker.schedule(wm)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical failure during Application.onCreate", e)
        }
    }
}
