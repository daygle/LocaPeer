package com.locapeer.circles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.CircleDao
import com.locapeer.data.dao.MessageDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.CircleEntity
import com.locapeer.data.entity.PeerEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class CirclesViewModel @Inject constructor(
    private val circleDao: CircleDao,
    private val peerDao: PeerDao,
    private val messageDao: MessageDao,
    private val keyManager: KeyManager
) : ViewModel() {

    /** All contacts, used as the pool to pick circle members from. */
    val contacts: StateFlow<List<PeerEntity>> =
        peerDao.getAllPeers().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * This device's own pubkey, used to decide whether the local user owns a circle
     * (`circle.creatorPubkey == myPubkeyHex`). Only the owner may rename a circle or change its
     * membership; the edit UI is gated on this so a non-owner member can't reshape the group.
     * Empty until the keypair loads.
     */
    val myPubkeyHex: StateFlow<String> =
        flow { emit(keyManager.ensureKeypair().second) }
            .stateIn(viewModelScope, SharingStarted.Lazily, "")

    /**
     * True when the local user may edit [circle] (rename / change members). The owner is whoever
     * created the circle ([CircleEntity.creatorPubkey]); circles created before ownership existed
     * carry a blank creator and stay editable by anyone (there is no recorded owner to enforce).
     */
    fun canEditCircle(circle: CircleEntity?, myPubkey: String): Boolean {
        val creator = circle?.creatorPubkey ?: return true
        return creator.isBlank() || creator == myPubkey
    }

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
            // The thread must go with the circle: orphaned rows would be invisible in the UI
            // (group lists join on the circles table) yet keep message content on disk.
            messageDao.deleteAllForGroup(circleId)
        }
    }
}
