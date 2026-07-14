package com.locapeer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.locapeer.data.entity.CircleEntity
import com.locapeer.data.entity.CircleMemberEntity
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
    suspend fun replaceMembers(circleId: String, memberPubkeys: List<String>) {
        clearMembers(circleId)
        memberPubkeys.forEach { addMember(CircleMemberEntity(circleId, it)) }
    }

    /**
     * Creates the circle (if missing) and sets its membership from a received group message, so a
     * recipient sees the same circle the sender defined without a separate invite.
     *
     * Creator-authoritative: on first sight the claimed [creatorPubkey] is recorded (trust on first
     * use). Thereafter only a message whose [senderPubkey] equals that stored creator may change the
     * name or membership; a message from any other member is still delivered (the caller inserts it)
     * but is not allowed to silently rewrite who is in the circle on this device.
     */
    suspend fun materialiseFromRemote(
        circleId: String,
        name: String,
        creatorPubkey: String,
        senderPubkey: String,
        memberPubkeys: List<String>
    ) {
        val existing = getCircle(circleId)
        if (existing == null) {
            upsertCircle(CircleEntity(id = circleId, name = name, creatorPubkey = creatorPubkey))
            replaceMembers(circleId, memberPubkeys)
            return
        }
        // Only the recorded creator may mutate membership/name. Fall back to the sender when no
        // creator was ever recorded (circle predates this field), preserving prior behaviour.
        val authority = existing.creatorPubkey.ifBlank { senderPubkey }
        if (senderPubkey == authority) {
            if (existing.name != name && name.isNotBlank()) renameCircle(circleId, name)
            replaceMembers(circleId, memberPubkeys)
        }
    }
}
