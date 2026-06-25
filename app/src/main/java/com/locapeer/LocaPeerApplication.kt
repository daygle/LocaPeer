package com.locapeer

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.locapeer.subscriber.HeartbeatReceiver
import com.locapeer.subscriber.MissedHeartbeatWorker
import com.locapeer.subscriber.RetentionEnforcementWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

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
        heartbeatReceiver.start()
        val workManager = androidx.work.WorkManager.getInstance(this)
        MissedHeartbeatWorker.schedule(workManager)
        RetentionEnforcementWorker.schedule(workManager)
    }
}
