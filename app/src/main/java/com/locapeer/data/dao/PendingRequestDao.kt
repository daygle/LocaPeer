package com.locapeer.data.dao

import androidx.room.*
import com.locapeer.data.entity.PendingRequestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingRequestDao {
    @Query("SELECT * FROM pending_requests ORDER BY receivedAt DESC")
    fun observeAll(): Flow<List<PendingRequestEntity>>

    @Query("SELECT * FROM pending_requests WHERE senderPubkey = :senderPubkey")
    suspend fun getByPubkey(senderPubkey: String): PendingRequestEntity?

    @Query("SELECT * FROM pending_requests WHERE senderPubkey = :senderPubkey")
    fun observeByPubkey(senderPubkey: String): Flow<PendingRequestEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(request: PendingRequestEntity)

    @Query("DELETE FROM pending_requests WHERE senderPubkey = :senderPubkey")
    suspend fun deleteByPubkey(senderPubkey: String)

    /** Consume only the request actually reviewed, not a newer replacement from this sender. */
    @Query("DELETE FROM pending_requests WHERE senderPubkey = :senderPubkey AND senderName = :senderName " +
        "AND senderRelayUrl = :senderRelayUrl AND isRoleChange = :isRoleChange " +
        "AND requestedRole IS :requestedRole AND receivedAt = :receivedAt")
    suspend fun deleteMatching(
        senderPubkey: String, senderName: String, senderRelayUrl: String,
        isRoleChange: Boolean, requestedRole: String?, receivedAt: Long
    ): Int

    suspend fun deleteIfUnchanged(request: PendingRequestEntity): Int = deleteMatching(
        request.senderPubkey, request.senderName, request.senderRelayUrl,
        request.isRoleChange, request.requestedRole, request.receivedAt
    )

    /** Age out stale incoming requests so a stranger cycling keys can't grow this table
     *  without bound (each request is a notification + a row). */
    @Query("DELETE FROM pending_requests WHERE receivedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("SELECT COUNT(*) FROM pending_requests")
    fun observeCount(): Flow<Int>

    /** Point-in-time count, for the incoming-request flood cap (see HeartbeatReceiver). */
    @Query("SELECT COUNT(*) FROM pending_requests")
    suspend fun count(): Int
}
