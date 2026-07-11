package com.locapeer.subscriber

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.locapeer.MainActivity
import com.locapeer.R
import com.locapeer.beacon.HeartbeatService
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PeerSharingConfigDao
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
    private val sharingConfigDao: PeerSharingConfigDao,
    private val prefs: AppPreferences,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = prefs.settings.first()
        ensureHeartbeatServiceRunning(settings.heartbeatEnabled)
        val receiveContacts = peerDao.getReceiveContacts().first()
        val now = System.currentTimeMillis()

        val notificationManager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        val alertConfigs = sharingConfigDao.getAll().associateBy { it.peerDeviceId }

        receiveContacts.forEach { peer ->
            // Missed-heartbeat alerts are per-contact opt-in ("Missed Location Alert"
            // in Contact Settings); most contacts going quiet is not noteworthy.
            if (alertConfigs[peer.deviceId]?.notifyOnMissedHeartbeat != true) return@forEach
            val latest = heartbeatDao.getLatestHeartbeat(peer.deviceId) ?: return@forEach
            // The sender reports its own interval in every ping. The 60s floor keeps
            // SOS-rate (15s) senders from alerting on mere relay jitter - combined
            // with the ×2 threshold below that means 2 min of silence.
            val expected = (latest.expectedIntervalSeconds * 1000L).coerceAtLeast(60_000L)
            // Use receivedAt (stamped by the receiver's own clock at insertion) rather than
            // timestamp (the sender's clock) to avoid false alerts from inter-device clock skew.
            val elapsed = now - latest.receivedAt
            if (elapsed > expected * 2) {
                val minutesAgo = elapsed / 60_000
                // Unique data URI: extras don't participate in PendingIntent matching
                // (Intent.filterEquals), so without it a requestCode hash collision
                // between two peers would silently share one PendingIntent.
                val intent = Intent(applicationContext, MainActivity::class.java)
                    .setData(Uri.parse("locapeer-notif://missed/${peer.deviceId}"))
                val pi = PendingIntent.getActivity(
                    applicationContext, peer.deviceId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE
                )
                val notification = NotificationCompat.Builder(applicationContext, "locapeer_alerts")
                    .setSmallIcon(R.drawable.ic_notif_alert)
                    .setContentTitle(applicationContext.getString(R.string.notif_missed_title, peer.displayName))
                    .setContentText(applicationContext.getString(R.string.notif_missed_text, minutesAgo.toString(), latest.lat.toString(), latest.lng.toString()))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
                notificationManager.notify(peer.deviceId, NOTIF_ID_MISSED_HEARTBEAT, notification)
            }
        }
        return Result.success()
    }

    /**
     * Watchdog: the heartbeat service is sticky and restarted on boot, but an OEM
     * task killer can take it down without either hook firing - tracking would then
     * silently stop until the app is next opened. This worker already runs every
     * 15 minutes, so re-assert the service here. Started via an alarm PendingIntent
     * because a background worker may not launch a foreground service directly on
     * Android 12+, while EXACT alarm delivery grants the temporary allowlist that
     * can. Inexact delivery gets the FGS-not-allowed allowlist on 12+ and the
     * restart is silently dropped, so fall back to it only when the exact-alarm
     * permission is missing (still correct pre-12, and harmless otherwise).
     */
    private fun ensureHeartbeatServiceRunning(heartbeatEnabled: Boolean) {
        if (!heartbeatEnabled || HeartbeatService.isRunning) return
        val ctx = applicationContext
        val hasLocation =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) return
        try {
            val pi = PendingIntent.getForegroundService(
                ctx, 0,
                Intent(ctx, HeartbeatService::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = SystemClock.elapsedRealtime() + 1000L
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                Log.i("MissedHeartbeatWorker", "Heartbeat service not running; scheduled restart")
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                Log.w("MissedHeartbeatWorker", "Heartbeat service not running; exact-alarm permission missing, restart may be blocked")
            }
        } catch (e: Exception) {
            Log.e("MissedHeartbeatWorker", "Failed to schedule heartbeat service restart", e)
        }
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
