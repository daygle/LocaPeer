package com.locapeer.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.locapeer.data.AppDatabase
import com.locapeer.data.dao.GeofenceDao
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.MessageDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.data.dao.PendingMessageDao
import com.locapeer.data.dao.ProximityAlertDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * v6 → v7: add per-peer retention columns to peer_sharing_config.
     * Default values mirror the previously-global defaults so existing rows
     * get sane values without an extra DataStore read at startup.
     */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE peer_sharing_config ADD COLUMN retentionDaysLocation INTEGER NOT NULL DEFAULT 30"
            )
            db.execSQL(
                "ALTER TABLE peer_sharing_config ADD COLUMN retentionDaysMessages INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    /**
     * v7 → v8: rename role string values in the peers table.
     * BROADCASTER → RECEIVE, SUBSCRIBER → SEND, MUTUAL → SEND_RECEIVE.
     */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE peers SET role = 'RECEIVE' WHERE role = 'BROADCASTER'")
            db.execSQL("UPDATE peers SET role = 'SEND' WHERE role = 'SUBSCRIBER'")
            db.execSQL("UPDATE peers SET role = 'SEND_RECEIVE' WHERE role = 'MUTUAL'")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "locapeer.db")
            .addMigrations(MIGRATION_6_7, MIGRATION_7_8)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun providePeerDao(db: AppDatabase): PeerDao = db.peerDao()
    @Provides fun provideHeartbeatDao(db: AppDatabase): HeartbeatDao = db.heartbeatDao()
    @Provides fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()
    @Provides fun provideGeofenceDao(db: AppDatabase): GeofenceDao = db.geofenceDao()
    @Provides fun provideProximityAlertDao(db: AppDatabase): ProximityAlertDao = db.proximityAlertDao()
    @Provides fun providePeerSharingConfigDao(db: AppDatabase): PeerSharingConfigDao = db.peerSharingConfigDao()
    @Provides fun providePendingMessageDao(db: AppDatabase): PendingMessageDao = db.pendingMessageDao()
}
