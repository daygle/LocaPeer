package com.locapeer

import android.app.Application
import com.locapeer.util.backgroundScope
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
import kotlinx.coroutines.Dispatchers
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

    private val appScope = backgroundScope(TAG, Dispatchers.Default)

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

        // Initialise MapLibre before any screen can create a MapView, and send the app's own
        // User-Agent with tile requests: the OpenStreetMap tile usage policy requires clients
        // to identify themselves, and generic agents can be blocked.
        try {
            org.maplibre.android.MapLibre.getInstance(applicationContext)
            val userAgent = "LocaPeer/${BuildConfig.VERSION_NAME} (https://github.com/daygle/LocaPeer)"
            org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(
                okhttp3.OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
                    }
                    .build()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialise MapLibre", e)
        }

        // Offload the remaining heavy initialization (networking setup and WorkManager) to
        // background so it cannot delay the first UI frame.
        appScope.launch(Dispatchers.IO) {
            // One-time cleanup after the move from osmdroid to MapLibre: its tile cache and
            // settings are no longer read. MapLibre keeps its own cache.
            try {
                java.io.File(filesDir, "osmdroid").takeIf { it.exists() }?.deleteRecursively()
                deleteSharedPreferences("osmdroid")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove the old osmdroid cache", e)
            }
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
