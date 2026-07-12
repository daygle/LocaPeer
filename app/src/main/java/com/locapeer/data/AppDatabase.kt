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
        GeofenceAssignmentEntity::class,
        ProximityAlertEntity::class,
        PeerSharingConfig::class,
        PendingMessageEntity::class,
        PendingRequestEntity::class,
        CircleEntity::class,
        CircleMemberEntity::class
    ],
    version = 11,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun peerDao(): PeerDao
    abstract fun heartbeatDao(): HeartbeatDao
    abstract fun messageDao(): MessageDao
    abstract fun geofenceDao(): GeofenceDao
    abstract fun geofenceAssignmentDao(): GeofenceAssignmentDao
    abstract fun proximityAlertDao(): ProximityAlertDao
    abstract fun peerSharingConfigDao(): PeerSharingConfigDao
    abstract fun pendingMessageDao(): PendingMessageDao
    abstract fun pendingRequestDao(): PendingRequestDao
    abstract fun circleDao(): CircleDao
}
