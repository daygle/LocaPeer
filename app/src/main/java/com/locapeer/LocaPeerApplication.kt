package com.locapeer

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.locapeer.subscriber.HeartbeatReceiver
import com.locapeer.subscriber.MissedHeartbeatWorker
import com.locapeer.subscriber.RetentionEnforcementWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

private const val TAG = "LocaPeerApplication"

@HiltAndroidApp
class LocaPeerApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var heartbeatReceiver: HeartbeatReceiver

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = getExternalFilesDir(null) ?: filesDir
            osmdroidTileCache = java.io.File(osmdroidBasePath, "tiles")
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
