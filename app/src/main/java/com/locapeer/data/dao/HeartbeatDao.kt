package com.locapeer.data.dao

import androidx.room.*
import com.locapeer.data.entity.HeartbeatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HeartbeatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(heartbeat: HeartbeatEntity)

    /**
     * Insert a batch of heartbeats in a single transaction. Room fires one table
     * invalidation per transaction, so batching a catch-up burst here collapses what
     * would be hundreds of per-row UI refreshes into a handful.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(heartbeats: List<HeartbeatEntity>)

    @Query("SELECT * FROM heartbeats WHERE deviceId = :deviceId AND timestamp >= :dayStart AND timestamp < :dayEnd ORDER BY timestamp ASC")
    fun getHeartbeatsForDay(deviceId: String, dayStart: Long, dayEnd: Long): Flow<List<HeartbeatEntity>>

    @Query("SELECT * FROM heartbeats WHERE deviceId = :deviceId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestHeartbeat(deviceId: String): HeartbeatEntity?

    @Query("SELECT * FROM heartbeats WHERE deviceId = :deviceId ORDER BY receivedAt DESC, timestamp DESC LIMIT 1")
    suspend fun getLatestReceivedHeartbeat(deviceId: String): HeartbeatEntity?

    @Query("SELECT * FROM heartbeats WHERE deviceId = :deviceId ORDER BY timestamp DESC LIMIT 1")
    fun observeLatestHeartbeat(deviceId: String): Flow<HeartbeatEntity?>

    @Query("SELECT COUNT(*) FROM heartbeats WHERE deviceId = :deviceId AND timestamp = :timestamp")
    suspend fun countByDeviceAndTimestamp(deviceId: String, timestamp: Long): Int

    // Keyed on MAX(timestamp) (the sender's fix time, unique per device-ping) rather than
    // receivedAt: during a catch-up burst a relay replays stored pings out of order, so the
    // last-inserted row (max receivedAt) can be an older location. timestamp always picks the
    // newest actual fix and can't tie, so the join never returns duplicate rows per device.
    // The chosen row still carries its own receivedAt for the clock-skew-safe overdue check.
    @Query(
        "SELECT h.* FROM heartbeats h INNER JOIN (" +
            "SELECT deviceId, MAX(timestamp) AS maxTs FROM heartbeats GROUP BY deviceId" +
            ") latest ON h.deviceId = latest.deviceId AND h.timestamp = latest.maxTs"
    )
    fun getLatestHeartbeatPerDevice(): Flow<List<HeartbeatEntity>>

    @Query("DELETE FROM heartbeats WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM heartbeats WHERE deviceId = :deviceId")
    suspend fun deleteAllForDevice(deviceId: String)

    @Query("DELETE FROM heartbeats WHERE deviceId = :deviceId AND timestamp < :before")
    suspend fun deleteOlderThanForDevice(deviceId: String, before: Long)
}
