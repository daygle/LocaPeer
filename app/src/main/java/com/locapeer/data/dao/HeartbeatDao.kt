package com.locapeer.data.dao

import androidx.room.*
import com.locapeer.data.entity.HeartbeatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HeartbeatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(heartbeat: HeartbeatEntity)

    @Query("SELECT * FROM heartbeats WHERE deviceId = :deviceId AND timestamp >= :dayStart AND timestamp < :dayEnd ORDER BY timestamp ASC")
    fun getHeartbeatsForDay(deviceId: String, dayStart: Long, dayEnd: Long): Flow<List<HeartbeatEntity>>

    @Query("SELECT * FROM heartbeats WHERE deviceId = :deviceId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestHeartbeat(deviceId: String): HeartbeatEntity?

    @Query("SELECT * FROM heartbeats WHERE deviceId = :deviceId ORDER BY timestamp DESC LIMIT 1")
    fun observeLatestHeartbeat(deviceId: String): Flow<HeartbeatEntity?>

    @Query("SELECT COUNT(*) FROM heartbeats WHERE deviceId = :deviceId AND timestamp = :timestamp")
    suspend fun countByDeviceAndTimestamp(deviceId: String, timestamp: Long): Int

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
