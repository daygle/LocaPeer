package com.locapeer.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.locapeer.data.dao.GeofenceDao
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.MessageDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.GeofenceEntity
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.MessageEntity
import com.locapeer.data.entity.PeerEntity

@Database(
    entities = [
        PeerEntity::class,
        HeartbeatEntity::class,
        MessageEntity::class,
        GeofenceEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun heartbeatDao(): HeartbeatDao
    abstract fun messageDao(): MessageDao
    abstract fun geofenceDao(): GeofenceDao
}
