package com.locapeer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.locapeer.data.entity.LocationRejectAggregateEntity

@Dao
interface LocationRejectAggregateDao {
    /**
     * REPLACE is intentional: recomputing a bucket from the same source hours yields the same
     * count and median, so a retention run interrupted between the insert and the detail
     * delete converges instead of double-counting.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(aggregates: List<LocationRejectAggregateEntity>)

    @Query("DELETE FROM location_reject_aggregates WHERE hourStartUtcMs < :before")
    suspend fun deleteOlderThan(before: Long)

    /** Part of "clear all stored readings": aggregates are readings data too. */
    @Query("DELETE FROM location_reject_aggregates")
    suspend fun deleteAll()
}
