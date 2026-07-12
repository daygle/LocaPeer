package com.locapeer.circles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.CircleDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.data.entity.CircleEntity
import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.PeerSharingConfig
import com.locapeer.sharing.TempShareExpiryWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class CirclesViewModel @Inject constructor(
    private val circleDao: CircleDao,
    private val peerDao: PeerDao,
    private val configDao: PeerSharingConfigDao,
    private val keyManager: KeyManager,
    private val workManager: WorkManager
) : ViewModel() {

    /** All contacts, used as the pool to pick circle members from. */
    val contacts: StateFlow<List<PeerEntity>> =
        peerDao.getAllPeers().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun observeCircle(circleId: String): Flow<CircleEntity?> = circleDao.observeCircle(circleId)

    fun observeMembers(circleId: String): Flow<List<String>> = circleDao.observeMemberPubkeys(circleId)

    fun createCircle(name: String, memberPubkeys: List<String>) {
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            val (_, myPubHex) = keyManager.ensureKeypair()
            circleDao.upsertCircle(
                CircleEntity(id = id, name = name.trim().ifBlank { "Circle" }, creatorPubkey = myPubHex)
            )
            circleDao.replaceMembers(id, memberPubkeys)
        }
    }

    fun renameCircle(circleId: String, name: String) {
        viewModelScope.launch { circleDao.renameCircle(circleId, name.trim().ifBlank { "Circle" }) }
    }

    fun setMembers(circleId: String, memberPubkeys: List<String>) {
        viewModelScope.launch { circleDao.replaceMembers(circleId, memberPubkeys) }
    }

    /** Deletes the circle, its membership and its message thread. */
    fun deleteCircle(circleId: String) {
        viewModelScope.launch {
            circleDao.clearMembers(circleId)
            circleDao.deleteCircle(circleId)
        }
    }

    /**
     * Share live location with every member of a circle for [durationMinutes]. Reuses the per-peer
     * temporary-share mechanism: enables sharing and sets an expiry window on each member's config,
     * scheduling the same [TempShareExpiryWorker] that clears it. Members you already share location
     * with (SEND / SEND_RECEIVE role) start broadcasting immediately; others are unaffected until
     * such a relationship exists.
     */
    fun shareLocationWithCircle(circleId: String, durationMinutes: Int) {
        viewModelScope.launch {
            val members = circleDao.getMemberPubkeys(circleId)
            val endsAtSec = System.currentTimeMillis() / 1000L + durationMinutes.coerceAtLeast(1) * 60L
            members.forEach { memberPub ->
                // Ensure a config row exists so the UPDATE-based setters below take effect.
                if (configDao.getForPeer(memberPub) == null) {
                    configDao.upsert(PeerSharingConfig(peerDeviceId = memberPub))
                }
                configDao.setSharingEnabled(memberPub, true)
                configDao.setTemporaryShareEndsAt(memberPub, endsAtSec)
                val request = OneTimeWorkRequestBuilder<TempShareExpiryWorker>()
                    .setInitialDelay(durationMinutes.coerceAtLeast(1) * 60L, TimeUnit.SECONDS)
                    .setInputData(
                        androidx.work.Data.Builder().putString("peerDeviceId", memberPub).build()
                    )
                    .build()
                workManager.enqueueUniqueWork(
                    TempShareExpiryWorker.workNameFor(memberPub),
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            }
        }
    }
}
