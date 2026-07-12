package com.locapeer.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.locapeer.data.AppDatabase
import com.locapeer.data.dao.CircleDao
import com.locapeer.data.dao.GeofenceAssignmentDao
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

    /** v3: record GPS altitude for each heartbeat. */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE heartbeats ADD COLUMN altitude REAL NOT NULL DEFAULT 0"
            )
        }
    }

    /**
     * v4: geofences become shareable areas. The per-contact tracking (device + trigger +
     * active) moves into a new geofence_assignments table so one area can watch many
     * contacts. Each existing geofence is split into an area row plus a single assignment
     * that preserves its previous behaviour.
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS geofence_assignments (
                    id TEXT NOT NULL PRIMARY KEY,
                    geofenceId TEXT NOT NULL,
                    trackedDeviceId TEXT NOT NULL,
                    triggerOn TEXT NOT NULL,
                    active INTEGER NOT NULL,
                    scheduleRules TEXT NOT NULL DEFAULT '[]',
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_geofence_assignments_geofenceId_trackedDeviceId " +
                    "ON geofence_assignments (geofenceId, trackedDeviceId)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_geofence_assignments_trackedDeviceId " +
                    "ON geofence_assignments (trackedDeviceId)"
            )
            // One assignment per existing geofence, referencing the (soon-to-be-trimmed) area.
            db.execSQL(
                """
                INSERT INTO geofence_assignments (id, geofenceId, trackedDeviceId, triggerOn, active, scheduleRules, createdAt)
                SELECT id || ':a', id, trackedDeviceId, triggerOn, active, '[]', createdAt FROM geofences
                """.trimIndent()
            )
            // Rebuild geofences without the moved columns.
            db.execSQL(
                """
                CREATE TABLE geofences_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    lat REAL NOT NULL,
                    lng REAL NOT NULL,
                    radiusMetres INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO geofences_new (id, name, lat, lng, radiusMetres, createdAt) " +
                    "SELECT id, name, lat, lng, radiusMetres, createdAt FROM geofences"
            )
            db.execSQL("DROP TABLE geofences")
            db.execSQL("ALTER TABLE geofences_new RENAME TO geofences")

            // v4 also added scheduleRules to proximity alerts.
            db.execSQL("ALTER TABLE proximity_alerts ADD COLUMN scheduleRules TEXT NOT NULL DEFAULT '[]'")
        }
    }

    /** v5: add missing scheduleRules to geofences and proximity alerts. */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // This handles both the "broken" v4 (from an upgrade missing the columns)
            // and the "intermediate" v4 (from a fresh install of the code that
            // changed entities but forgot to bump version).
            val cursor1 = db.query("PRAGMA table_info(geofence_assignments)")
            var hasGeofenceRules = false
            while (cursor1.moveToNext()) {
                if (cursor1.getString(cursor1.getColumnIndexOrThrow("name")) == "scheduleRules") {
                    hasGeofenceRules = true
                    break
                }
            }
            cursor1.close()
            if (!hasGeofenceRules) {
                db.execSQL("ALTER TABLE geofence_assignments ADD COLUMN scheduleRules TEXT NOT NULL DEFAULT '[]'")
            }

            val cursor2 = db.query("PRAGMA table_info(proximity_alerts)")
            var hasProximityRules = false
            while (cursor2.moveToNext()) {
                if (cursor2.getString(cursor2.getColumnIndexOrThrow("name")) == "scheduleRules") {
                    hasProximityRules = true
                    break
                }
            }
            cursor2.close()
            if (!hasProximityRules) {
                db.execSQL("ALTER TABLE proximity_alerts ADD COLUMN scheduleRules TEXT NOT NULL DEFAULT '[]'")
            }
        }
    }

    /**
     * v6: per-peer one-off temporary share. Adds [PeerSharingConfig.temporaryShareEndsAtEpochSeconds]
     * (INTEGER, nullable). A null column is the correct initial state for both fresh installs
     * ("no temp share ever set") and upgraded installs ("never supported temp share"). The
     * per-peer temp-share expiry worker reads this column on its tick.
     */
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE peer_sharing_config ADD COLUMN temporaryShareEndsAtEpochSeconds INTEGER DEFAULT NULL"
            )
        }
    }

    /**
     * v7: client-side circles (groups). Adds a nullable groupId marker to messages (thread key
     * stays in peerId) and two new tables holding circles and their membership. All additive and
     * non-destructive: existing 1:1 messages have groupId = NULL.
     */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN groupId TEXT DEFAULT NULL")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_groupId ON messages (groupId)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS circles (" +
                    "id TEXT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
                    "creatorPubkey TEXT NOT NULL DEFAULT '', PRIMARY KEY(id))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS circle_members (" +
                    "circleId TEXT NOT NULL, memberPubkey TEXT NOT NULL, " +
                    "PRIMARY KEY(circleId, memberPubkey))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_circle_members_circleId ON circle_members (circleId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_circle_members_memberPubkey ON circle_members (memberPubkey)")
        }
    }

    /**
     * v8: inline media messages (image/voice). Adds contentType (defaults to 'TEXT' for every
     * existing row) plus nullable mediaBase64 and mediaDurationMs to messages. All additive.
     */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN contentType TEXT NOT NULL DEFAULT 'TEXT'")
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaBase64 TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaDurationMs INTEGER DEFAULT NULL")
        }
    }

    /**
     * v9: per-fanout NIP-09 deletion for circle messages. Adds nostrEventIdsByMember to messages,
     * a CSV string of `memberPubHex:eventIdHex` pairs captured at send time. Empty default -
     * existing rows (1:1 messages and circle messages sent before this version, where we never
     * tracked per-recipient ids) take the empty map and remain ineligible for remote delete.
     */
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE messages ADD COLUMN nostrEventIdsByMember TEXT NOT NULL DEFAULT ''"
            )
        }
    }

    val ALL_MIGRATIONS = arrayOf(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
        MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9
    )

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "locapeer.db")
            // The app is live with testers, so every schema change from here on
            // must bump the version and ship a Migration - in-place edits to an
            // existing version leave installed devices with a mismatched schema
            // that crashes Room's validation on launch. There is deliberately no
            // destructive upgrade fallback: a missing migration must crash in
            // testing, not silently wipe user data. Downgrades (installing an
            // older build over a newer database) have no migration path, so that
            // direction still rebuilds destructively rather than crash-looping.
            .addMigrations(*ALL_MIGRATIONS)
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .build()

    @Provides fun providePeerDao(db: AppDatabase): PeerDao = db.peerDao()
    @Provides fun provideHeartbeatDao(db: AppDatabase): HeartbeatDao = db.heartbeatDao()
    @Provides fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()
    @Provides fun provideGeofenceDao(db: AppDatabase): GeofenceDao = db.geofenceDao()
    @Provides fun provideGeofenceAssignmentDao(db: AppDatabase): GeofenceAssignmentDao = db.geofenceAssignmentDao()
    @Provides fun provideProximityAlertDao(db: AppDatabase): ProximityAlertDao = db.proximityAlertDao()
    @Provides fun providePeerSharingConfigDao(db: AppDatabase): PeerSharingConfigDao = db.peerSharingConfigDao()
    @Provides fun providePendingMessageDao(db: AppDatabase): PendingMessageDao = db.pendingMessageDao()
    @Provides fun providePendingRequestDao(db: AppDatabase): PendingRequestDao = db.pendingRequestDao()
    @Provides fun provideCircleDao(db: AppDatabase): CircleDao = db.circleDao()
}
