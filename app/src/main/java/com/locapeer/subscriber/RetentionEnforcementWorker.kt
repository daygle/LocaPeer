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
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.MessageDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.data.entity.PeerEntity
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
    private val configDao: PeerSharingConfigDao,
    private val peerDao: PeerDao,
    private val heartbeatDao: HeartbeatDao,
    private val messageDao: MessageDao,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = prefs.settings.first()
        val configs = configDao.getAll()
        val peerMap = peerDao.getAllPeers().first().associateBy { it.deviceId }

        val hasLocalWork = settings.localLocationRetentionDays > 0 || settings.localMessageRetentionDays > 0
        val hasRemoteWork = configs.any {
            it.retentionDaysLocation > 0 || it.retentionDaysMessages > 0
        }
        if (!hasLocalWork && !hasRemoteWork) return Result.success()

        return try {
            val (privHex, pubHex) = keyManager.ensureKeypair()

            // Local retention (always global, applies to data we've received)
            if (settings.localLocationRetentionDays > 0) {
                val cutoffMs = System.currentTimeMillis() - settings.localLocationRetentionDays * 24 * 3600 * 1000L
                heartbeatDao.deleteOlderThan(cutoffMs)
                Log.d(TAG, "Purged local location data older than ${settings.localLocationRetentionDays} days")
            }

            if (settings.localMessageRetentionDays > 0) {
                val cutoffMs = System.currentTimeMillis() - settings.localMessageRetentionDays * 24 * 3600 * 1000L
                messageDao.deleteOlderThan(cutoffMs)
                Log.d(TAG, "Purged local messages older than ${settings.localMessageRetentionDays} days")
            }

            // Per-peer remote purges
            configs.forEach { cfg ->
                val peer = peerMap[cfg.peerDeviceId] ?: return@forEach

                // Location purge: only meaningful for peers who actually keep our heartbeats
                if (cfg.retentionDaysLocation > 0 &&
                    (peer.locationRole == PeerEntity.ROLE_SEND || peer.locationRole == PeerEntity.ROLE_SEND_RECEIVE)
                ) {
                    val cutoffMs = System.currentTimeMillis() - cfg.retentionDaysLocation * 24 * 3600 * 1000L
                    publishPurge(
                        recipientPubKeyHex = peer.publicKeyHex,
                        deleteOlderThanMs = cutoffMs,
                        kind = NostrEventKind.PURGE_REQUEST,
                        privHex = privHex,
                        pubHex = pubHex
                    )
                }

                // Message purge: any peer we have chatted with could be holding messages
                if (cfg.retentionDaysMessages > 0) {
                    val cutoffMs = System.currentTimeMillis() - cfg.retentionDaysMessages * 24 * 3600 * 1000L
                    publishPurge(
                        recipientPubKeyHex = peer.publicKeyHex,
                        deleteOlderThanMs = cutoffMs,
                        kind = NostrEventKind.MESSAGE_PURGE_REQUEST,
                        privHex = privHex,
                        pubHex = pubHex
                    )
                }
            }
            Log.d(TAG, "Remote purge requests sent to ${configs.size} peers")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send purge requests", e)
            Result.retry()
        }
    }

    private suspend fun publishPurge(
        recipientPubKeyHex: String,
        deleteOlderThanMs: Long,
        kind: Int,
        privHex: String,
        pubHex: String
    ) {
        val payload = Json.encodeToString(
            PurgeRequestPayload(deviceId = pubHex, deleteOlderThanMs = deleteOlderThanMs)
        )
        val encrypted = crypto.nip44Encrypt(
            senderPrivKey = crypto.hexToBytes(privHex),
            recipientXOnlyHex = recipientPubKeyHex,
            plaintext = payload
        )
        relayClient.publishEvent(
            NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = kind,
                content = encrypted,
                tags = listOf(listOf("p", recipientPubKeyHex)),
                crypto = crypto
            )
        )
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
