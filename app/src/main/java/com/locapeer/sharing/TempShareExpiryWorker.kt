package com.locapeer.sharing

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.locapeer.data.dao.PeerSharingConfigDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

private const val TAG = "TempShareExpiryWorker"
private const val INPUT_PEER_DEVICE_ID = "peerDeviceId"

private const val WORK_NAME_PREFIX = "tempshare-expiry-"

/**
 * Clears a single peer's `temporaryShareEndsAtEpochSeconds` once the deadline has passed.
 * Scheduled by [PeerSharingViewModel.setTemporaryShare] via WorkManager's unique-work
 * API; cancelled by [PeerSharingViewModel.clearTemporaryShare] if the user revokes the
 * temp share early.
 *
 * Idempotent: if the deadline has already been cleared by a status check on the
 * reading path, the clear here is a no-op. If the deadline has been extended (which
 * the UI doesn't currently allow but the worker schema defends against anyway), the
 * worker will be re-scheduled with a fresh delay and replace this work in the queue
 * via [ExistingWorkPolicy.REPLACE].
 */
@HiltWorker
class TempShareExpiryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val configDao: PeerSharingConfigDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val peerDeviceId = inputData.getString(INPUT_PEER_DEVICE_ID) ?: return Result.failure()
        Log.i(TAG, "Clearing temporary share for $peerDeviceId")
        configDao.setTemporaryShareEndsAt(peerDeviceId, null)
        return Result.success()
    }

    companion object {
        /** Unique work name per peer; matches the convention used by the scheduled API. */
        fun workNameFor(peerDeviceId: String): String = WORK_NAME_PREFIX + peerDeviceId
    }
}
