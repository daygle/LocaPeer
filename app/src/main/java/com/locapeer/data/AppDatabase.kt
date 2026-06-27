package com.locapeer.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.locapeer.data.dao.*
import com.locapeer.data.entity.*

@Database(
    entities = [
        PeerEntity::class,
        HeartbeatEntity::class,
        MessageEntity::class,
        GeofenceEntity::class,
        ProximityAlertEntity::class,
        PeerSharingConfig::class,
        PendingMessageEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun heartbeatDao(): HeartbeatDao
    abstract fun messageDao(): MessageDao
    abstract fun geofenceDao(): GeofenceDao
    abstract fun proximityAlertDao(): ProximityAlertDao
    abstract fun peerSharingConfigDao(): PeerSharingConfigDao
    abstract fun pendingMessageDao(): PendingMessageDao
}
