package com.locapeer.subscriber

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.locapeer.beacon.PurgeRequestPayload
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerDao
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

private const val TAG = "RetentionWorker"

@HiltWorker
class RetentionEnforcementWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val prefs: AppPreferences,
    private val peerDao: PeerDao,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = prefs.settings.first()
        if (settings.retentionDays == 0) return Result.success()  // user chose "forever"

        val deleteBeforeMs = System.currentTimeMillis() - settings.retentionDays * 24 * 3600 * 1000L
        return try {
            val (privHex, pubHex) = keyManager.ensureKeypair()
            val payload = Json.encodeToString(
                PurgeRequestPayload(deviceId = pubHex, deleteOlderThanMs = deleteBeforeMs)
            )
            val subscribers = peerDao.getSubscribers().first()
            subscribers.forEach { sub ->
                val encrypted = crypto.nip04Encrypt(
                    senderPrivKey = crypto.hexToBytes(privHex),
                    recipientXOnlyHex = sub.publicKeyHex,
                    plaintext = payload
                )
                val event = NostrEvent.build(
                    privKeyHex = privHex,
                    pubKeyHex = pubHex,
                    kind = NostrEventKind.PURGE_REQUEST,
                    content = encrypted,
                    tags = listOf(listOf("p", sub.publicKeyHex)),
                    crypto = crypto
                )
                relayClient.publishEvent(event)
            }
            Log.d(TAG, "Sent purge request to ${subscribers.size} subscribers (before ${deleteBeforeMs}ms)")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send purge requests", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "retention_enforcement"

        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<RetentionEnforcementWorker>(1, TimeUnit.DAYS)
                .build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
