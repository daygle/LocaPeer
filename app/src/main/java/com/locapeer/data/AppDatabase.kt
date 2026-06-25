package com.locapeer.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.locapeer.data.dao.GeofenceDao
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.MessageDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.ProximityAlertDao
import com.locapeer.data.entity.GeofenceEntity
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.MessageEntity
import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.ProximityAlertEntity

@Database(
    entities = [
        PeerEntity::class,
        HeartbeatEntity::class,
        MessageEntity::class,
        GeofenceEntity::class,
        ProximityAlertEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun heartbeatDao(): HeartbeatDao
    abstract fun messageDao(): MessageDao
    abstract fun geofenceDao(): GeofenceDao
    abstract fun proximityAlertDao(): ProximityAlertDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS proximity_alerts (
                peerDeviceId TEXT NOT NULL PRIMARY KEY,
                radiusMetres INTEGER NOT NULL DEFAULT 500,
                active INTEGER NOT NULL DEFAULT 1
            )"""
        )
    }
}
