package com.locapeer.subscriber

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.locapeer.MainActivity
import com.locapeer.R
import com.locapeer.beacon.AdaptiveIntervalManager
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.settings.AppPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

// Paired with a per-peer tag (notify(tag, id, ...)) since two peers' deviceId hashCodes can
// collide and silently overwrite each other's notification.
private const val NOTIF_ID_MISSED_HEARTBEAT = 5000

@HiltWorker
class MissedHeartbeatWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val heartbeatDao: HeartbeatDao,
    private val peerDao: PeerDao,
    private val prefs: AppPreferences,
    private val intervalManager: AdaptiveIntervalManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = prefs.settings.first()
        val receiveContacts = peerDao.getReceiveContacts().first()
        val now = System.currentTimeMillis()

        val notificationManager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        receiveContacts.forEach { peer ->
            val latest = heartbeatDao.getLatestHeartbeat(peer.deviceId) ?: return@forEach
            val expected = intervalManager.getExpectedIntervalMillis(latest.motionState, settings)
            val elapsed = now - latest.timestamp
            if (elapsed > expected * 2) {
                val minutesAgo = elapsed / 60_000
                val intent = Intent(applicationContext, MainActivity::class.java)
                val pi = PendingIntent.getActivity(
                    applicationContext, peer.deviceId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE
                )
                val notification = NotificationCompat.Builder(applicationContext, "locapeer_alerts")
                    .setSmallIcon(R.drawable.ic_notif_alert)
                    .setContentTitle("No update from ${peer.displayName}")
                    .setContentText("Last seen ${minutesAgo}m ago near (${latest.lat}, ${latest.lng})")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
                notificationManager.notify(peer.deviceId, NOTIF_ID_MISSED_HEARTBEAT, notification)
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "missed_heartbeat_check"

        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<MissedHeartbeatWorker>(15, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
