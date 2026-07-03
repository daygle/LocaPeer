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
import com.locapeer.data.dao.PendingRequestDao
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

    /** v2: per-contact opt-in for missed-location alerts. */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE peer_sharing_config ADD COLUMN notifyOnMissedHeartbeat INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "locapeer.db")
            // The app is live with testers, so every schema change from here on
            // must bump the version and ship a Migration — in-place edits to an
            // existing version leave installed devices with a mismatched schema
            // that crashes Room's validation on launch. The destructive fallbacks
            // remain only as a last resort for version jumps with no migration
            // path (they wipe local data).
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigration(true)
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .build()

    @Provides fun providePeerDao(db: AppDatabase): PeerDao = db.peerDao()
    @Provides fun provideHeartbeatDao(db: AppDatabase): HeartbeatDao = db.heartbeatDao()
    @Provides fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()
    @Provides fun provideGeofenceDao(db: AppDatabase): GeofenceDao = db.geofenceDao()
    @Provides fun provideProximityAlertDao(db: AppDatabase): ProximityAlertDao = db.proximityAlertDao()
    @Provides fun providePeerSharingConfigDao(db: AppDatabase): PeerSharingConfigDao = db.peerSharingConfigDao()
    @Provides fun providePendingMessageDao(db: AppDatabase): PendingMessageDao = db.pendingMessageDao()
    @Provides fun providePendingRequestDao(db: AppDatabase): PendingRequestDao = db.pendingRequestDao()
}
