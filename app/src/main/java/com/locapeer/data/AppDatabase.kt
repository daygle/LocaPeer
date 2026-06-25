package com.locapeer.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.locapeer.data.dao.GeofenceDao
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.MessageDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.data.dao.ProximityAlertDao
import com.locapeer.data.entity.GeofenceEntity
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.MessageEntity
import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.PeerSharingConfig
import com.locapeer.data.entity.ProximityAlertEntity

@Database(
    entities = [
        PeerEntity::class,
        HeartbeatEntity::class,
        MessageEntity::class,
        GeofenceEntity::class,
        ProximityAlertEntity::class,
        PeerSharingConfig::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun heartbeatDao(): HeartbeatDao
    abstract fun messageDao(): MessageDao
    abstract fun geofenceDao(): GeofenceDao
    abstract fun proximityAlertDao(): ProximityAlertDao
    abstract fun peerSharingConfigDao(): PeerSharingConfigDao
}
