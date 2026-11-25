package com.locapeer.data

import androidx.room.Room
import androidx.room.migration.AutoMigrationSpec
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locapeer.di.DatabaseModule
import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.PendingRequestEntity
import com.locapeer.invite.applyInboundRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val TEST_DB = "migration-test"

    @get:Rule
    // Use the modern MigrationTestHelper signature (Instrumentation + Class<Db> +
    // List<AutoMigrationSpec> + Factory). The (Instrumentation, String, Factory)
    // form is deprecated and additionally surfaced a "Java type mismatch:
    // inferred type is String?, but String was expected" warning because
    // canonicalName is nullable. LocaPeer uses only manual migrations, so the
    // AutoMigrationSpec list is empty here.
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList<AutoMigrationSpec>(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun inboundAcceptAppliesOnlyTheReviewedRequest() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java
        ).build()
        try {
            val request = PendingRequestEntity("peer", "Reviewed", "wss://first", receivedAt = 1)
            val replacement = request.copy(senderName = "Replacement", receivedAt = 2)
            db.pendingRequestDao().upsert(request)
            db.pendingRequestDao().upsert(replacement)
            assertFalse(applyInboundRequest(db, request, PeerEntity.ROLE_SEND, true))
            assertNull(db.peerDao().getPeer("peer"))
            assertEquals(replacement, db.pendingRequestDao().getByPubkey("peer"))
            assertEquals(0, db.pendingRequestDao().deleteIfUnchanged(request))
            assertEquals(replacement, db.pendingRequestDao().getByPubkey("peer"))
            assertTrue(applyInboundRequest(db, replacement, PeerEntity.ROLE_RECEIVE, false))
            assertEquals(PeerEntity.ROLE_RECEIVE, db.peerDao().getPeer("peer")?.locationRole)
            assertNull(db.pendingRequestDao().getByPubkey("peer"))
        } finally {
            db.close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrateAll() {
        // Earliest committed schema is v2; run every migration forward to the current
        // latest (v19), validating the schema after the last one. This is the chain an
        // installed device actually walks, so it also proves no intermediate step leaves
        // a table or index missing.
        helper.createDatabase(TEST_DB, 2).apply {
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 19, true, *DatabaseModule.ALL_MIGRATIONS)
    }

    @Test
    @Throws(IOException::class)
    fun migrate4To5() {
        helper.createDatabase(TEST_DB, 4).apply {
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 5, true, *DatabaseModule.ALL_MIGRATIONS)
    }

    /**
     * v5 -> v6 only: adds the per-peer one-off temporary share column on
     * peer_sharing_config. Confirms the migration runs cleanly.
     *
     * Note: the explicit PRAGMA table_info probe that used helper.openDatabase
     * was removed because (a) the API surface for MigrationTestHelper.openDatabase
     * in the Room version available to this project is not the expected two-arg
     * shape, and (b) runMigrationsAndValidate's `validateDroppedTables = true`
     * already cross-checks the post-migration schema against the KSP-generated
     * schemas/com.locapeer.data.AppDatabase/6.json. If temporaryShareEndsAtEpochSeconds
     * is missing or wrongly typed, that call throws IllegalStateException - which is
     * the same failure mode the PRAGMA probe was guarding against.
     */
    @Test
    @Throws(IOException::class)
    fun migrate5To6() {
        helper.createDatabase(TEST_DB, 5).apply {
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 6, true, *DatabaseModule.ALL_MIGRATIONS)
    }

    /** v14 -> v15: adds contact_add_receipts, validated against schemas/.../15.json. */
    @Test
    @Throws(IOException::class)
    fun migrate14To15() {
        helper.createDatabase(TEST_DB, 14).apply {
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 15, true, *DatabaseModule.ALL_MIGRATIONS)
    }

    /** v15 -> v16: adds the optional polygon ring on geofences, validated against schemas/.../16.json. */
    @Test
    @Throws(IOException::class)
    fun migrate15To16() {
        helper.createDatabase(TEST_DB, 15).apply {
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 16, true, *DatabaseModule.ALL_MIGRATIONS)
    }

    /**
     * v16 -> v17: the reaction columns on `messages` plus the per-member `message_reactions` table
     * and its index, validated against schemas/.../17.json. This is the check that the migration
     * produces exactly the schema Room expects, so opening a v17 database can't crash.
     */
    @Test
    @Throws(IOException::class)
    fun migrate16To17() {
        helper.createDatabase(TEST_DB, 16).apply {
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 17, true, *DatabaseModule.ALL_MIGRATIONS)
    }

    /**
     * v17 -> v18: the ingest-quality columns on `heartbeats` plus the hourly rejected-reading
     * aggregate table, validated against schemas/.../18.json. Existing heartbeats become
     * accepted readings, which is what they were under the old capture-time gate.
     */
    @Test
    @Throws(IOException::class)
    fun migrate17To18() {
        helper.createDatabase(TEST_DB, 17).apply {
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 18, true, *DatabaseModule.ALL_MIGRATIONS)
    }

    /**
     * v18 -> v19: drops the never-read `location_reject_aggregates` table, validated against
     * schemas/.../19.json.
     */
    @Test
    @Throws(IOException::class)
    fun migrate18To19() {
        helper.createDatabase(TEST_DB, 18).apply {
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 19, true, *DatabaseModule.ALL_MIGRATIONS)
    }

    /** v13 -> v14: index changes only, validated against schemas/.../14.json. */
    @Test
    @Throws(IOException::class)
    fun migrate13To14() {
        helper.createDatabase(TEST_DB, 13).apply {
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 14, true, *DatabaseModule.ALL_MIGRATIONS)
    }
}
