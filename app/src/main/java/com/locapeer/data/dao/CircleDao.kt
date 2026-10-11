package com.locapeer.data.dao

import androidx.room.*
import com.locapeer.data.entity.CircleEntity
import com.locapeer.data.entity.CircleMemberEntity
import com.locapeer.messaging.GroupControl
import kotlinx.coroutines.flow.Flow

data class CircleMemberCount(val circleId: String, val cnt: Int)

@Dao
interface CircleDao {

    @Query("SELECT circleId, COUNT(*) as cnt FROM circle_members GROUP BY circleId")
    fun observeMemberCounts(): Flow<List<CircleMemberCount>>

    @Query("SELECT * FROM circles ORDER BY name COLLATE NOCASE ASC")
    fun observeCircles(): Flow<List<CircleEntity>>

    @Query("SELECT * FROM circles WHERE id = :circleId LIMIT 1")
    suspend fun getCircle(circleId: String): CircleEntity?

    /** Point-in-time circle count, for the remote-circle-creation flood cap. */
    @Query("SELECT COUNT(*) FROM circles")
    suspend fun countCircles(): Int

    @Query("SELECT * FROM circles WHERE id = :circleId LIMIT 1")
    fun observeCircle(circleId: String): Flow<CircleEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCircle(circle: CircleEntity)

    @Query("DELETE FROM circles WHERE id = :circleId")
    suspend fun deleteCircle(circleId: String)

    @Query("UPDATE circles SET name = :name WHERE id = :circleId")
    suspend fun renameCircle(circleId: String, name: String)

    @Query("UPDATE circles SET membershipUpdatedAt = :createdAt WHERE id = :circleId")
    suspend fun setMembershipUpdatedAt(circleId: String, createdAt: Long)

    @Query(
        "UPDATE circles SET isArchived = :archived, " +
            "archivedAt = CASE WHEN :archived = 1 THEN :now ELSE archivedAt END " +
            "WHERE id = :circleId"
    )
    suspend fun setArchived(circleId: String, archived: Boolean, now: Long)

    suspend fun unarchive(circleId: String) = setArchived(circleId, false, 0)

    @Query("SELECT memberPubkey FROM circle_members WHERE circleId = :circleId")
    fun observeMemberPubkeys(circleId: String): Flow<List<String>>

    @Query("SELECT memberPubkey FROM circle_members WHERE circleId = :circleId")
    suspend fun getMemberPubkeys(circleId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMember(member: CircleMemberEntity)

    @Query("DELETE FROM circle_members WHERE circleId = :circleId AND memberPubkey = :memberPubkey")
    suspend fun removeMember(circleId: String, memberPubkey: String)

    @Query("DELETE FROM circle_members WHERE circleId = :circleId")
    suspend fun clearMembers(circleId: String)

    /** Removes a contact from every circle it belongs to (e.g. when the contact is deleted). */
    @Query("DELETE FROM circle_members WHERE memberPubkey = :memberPubkey")
    suspend fun removeMemberFromAllCircles(memberPubkey: String)

    /** Replaces the whole membership of a circle. */
    @Transaction
    suspend fun replaceMembers(circleId: String, memberPubkeys: List<String>) {
        clearMembers(circleId)
        memberPubkeys.forEach { addMember(CircleMemberEntity(circleId, it)) }
    }

    /** Creates a circle and its membership in one transaction. */
    @Transaction
    suspend fun createCircle(circle: CircleEntity, memberPubkeys: List<String>) {
        upsertCircle(circle)
        replaceMembers(circle.id, memberPubkeys)
    }

    @Query("UPDATE circles SET controlVersion = :version, controlSig = :sig WHERE id = :circleId")
    suspend fun setControl(circleId: String, version: Long, sig: String)

    @Query("UPDATE circles SET creatorPubkey = :creatorPubkey WHERE id = :circleId")
    suspend fun setCreator(circleId: String, creatorPubkey: String)

    /**
     * Creates the circle (if missing) and sets its membership from a received group message, so a
     * recipient sees the same circle the sender defined without a separate invite.
     *
     * [signedVersion] / [signedSig] are the message's creator-signed state, already verified by
     * the caller (0 / "" when unsigned). A signed message proves who the creator is, so the circle
     * can be created from any member's copy, and only a newer signed version updates it. Unsigned
     * messages (older app versions) keep the earlier rules: the claimed creator is recorded on
     * first sight, then only that creator may change the name or membership, never with a message
     * older than the last applied one ([eventCreatedAt]). See [GroupControl.decide].
     *
     * A message that may not update the circle is still delivered; the caller inserts it.
     */
    @Transaction
    suspend fun materialiseFromRemote(
        circleId: String,
        name: String,
        creatorPubkey: String,
        senderPubkey: String,
        memberPubkeys: List<String>,
        eventCreatedAt: Long,
        signedVersion: Long = 0,
        signedSig: String = "",
        selfPubkey: String = ""
    ) {
        val existing = getCircle(circleId)
        if (existing == null) {
            // A non-creator's unsigned copy may carry a stale view, so only a creator message sets
            // the ordering baseline; otherwise the creator's next message (of any age) still applies.
            val baseline = if (creatorPubkey.isBlank() || senderPubkey == creatorPubkey) eventCreatedAt else 0L
            upsertCircle(
                CircleEntity(
                    id = circleId, name = name, creatorPubkey = creatorPubkey, membershipUpdatedAt = baseline,
                    controlVersion = signedVersion, controlSig = if (signedVersion > 0) signedSig else ""
                )
            )
            replaceMembers(circleId, memberPubkeys)
            return
        }
        when (
            GroupControl.decide(
                knownCreator = existing.creatorPubkey,
                knownVersion = existing.controlVersion,
                knownUpdatedAt = existing.membershipUpdatedAt,
                // A creator message has been applied (it sets the baseline), or the circle is ours.
                knownCreatorConfirmed = existing.membershipUpdatedAt > 0 ||
                    (selfPubkey.isNotEmpty() && existing.creatorPubkey == selfPubkey),
                senderPubkey = senderPubkey,
                claimedCreator = creatorPubkey,
                signedVersion = signedVersion,
                eventCreatedAt = eventCreatedAt,
            )
        ) {
            GroupControl.Update.APPLY_SIGNED -> {
                if (existing.creatorPubkey != creatorPubkey) setCreator(circleId, creatorPubkey)
                if (existing.name != name && name.isNotBlank()) renameCircle(circleId, name)
                replaceMembers(circleId, memberPubkeys)
                setControl(circleId, signedVersion, signedSig)
            }
            GroupControl.Update.APPLY_UNSIGNED -> {
                if (existing.name != name && name.isNotBlank()) renameCircle(circleId, name)
                replaceMembers(circleId, memberPubkeys)
                setMembershipUpdatedAt(circleId, eventCreatedAt)
            }
            GroupControl.Update.IGNORE -> Unit
        }
    }
}
