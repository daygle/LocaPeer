package com.locapeer.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
        PendingMessageEntity::class,
        PendingRequestEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE heartbeats ADD COLUMN speed REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE heartbeats ADD COLUMN bearing REAL NOT NULL DEFAULT 0")
            }
        }
    }
    abstract fun peerDao(): PeerDao
    abstract fun heartbeatDao(): HeartbeatDao
    abstract fun messageDao(): MessageDao
    abstract fun geofenceDao(): GeofenceDao
    abstract fun proximityAlertDao(): ProximityAlertDao
    abstract fun peerSharingConfigDao(): PeerSharingConfigDao
    abstract fun pendingMessageDao(): PendingMessageDao
    abstract fun pendingRequestDao(): PendingRequestDao
}
