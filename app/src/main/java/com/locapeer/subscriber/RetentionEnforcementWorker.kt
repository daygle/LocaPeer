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
        if (settings.retentionDays == 0 && settings.messageRetentionDays == 0) return Result.success()

        return try {
            val (privHex, pubHex) = keyManager.ensureKeypair()

            if (settings.retentionDays > 0) {
                val cutoffMs = System.currentTimeMillis() - settings.retentionDays * 24 * 3600 * 1000L
                val locationPayload = Json.encodeToString(
                    PurgeRequestPayload(deviceId = pubHex, deleteOlderThanMs = cutoffMs)
                )
                val subscribers = peerDao.getSubscribers().first()
                subscribers.forEach { sub ->
                    val encrypted = crypto.nip04Encrypt(
                        senderPrivKey = crypto.hexToBytes(privHex),
                        recipientXOnlyHex = sub.publicKeyHex,
                        plaintext = locationPayload
                    )
                    relayClient.publishEvent(
                        NostrEvent.build(
                            privKeyHex = privHex,
                            pubKeyHex = pubHex,
                            kind = NostrEventKind.PURGE_REQUEST,
                            content = encrypted,
                            tags = listOf(listOf("p", sub.publicKeyHex)),
                            crypto = crypto
                        )
                    )
                }
                Log.d(TAG, "Sent location purge to ${subscribers.size} subscribers (before ${cutoffMs}ms)")
            }

            if (settings.messageRetentionDays > 0) {
                val cutoffMs = System.currentTimeMillis() - settings.messageRetentionDays * 24 * 3600 * 1000L
                val msgPayload = Json.encodeToString(
                    PurgeRequestPayload(deviceId = pubHex, deleteOlderThanMs = cutoffMs)
                )
                val allPeers = peerDao.getAllPeers().first()
                allPeers.forEach { peer ->
                    val encrypted = crypto.nip04Encrypt(
                        senderPrivKey = crypto.hexToBytes(privHex),
                        recipientXOnlyHex = peer.publicKeyHex,
                        plaintext = msgPayload
                    )
                    relayClient.publishEvent(
                        NostrEvent.build(
                            privKeyHex = privHex,
                            pubKeyHex = pubHex,
                            kind = NostrEventKind.MESSAGE_PURGE_REQUEST,
                            content = encrypted,
                            tags = listOf(listOf("p", peer.publicKeyHex)),
                            crypto = crypto
                        )
                    )
                }
                Log.d(TAG, "Sent message purge to ${allPeers.size} peers (before ${cutoffMs}ms)")
            }

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
