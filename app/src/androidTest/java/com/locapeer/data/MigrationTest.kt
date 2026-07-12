package com.locapeer.data

import androidx.room.migration.AutoMigrationSpec
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locapeer.di.DatabaseModule
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
    @Throws(IOException::class)
    fun migrateAll() {
        // Earliest committed schema is v2; run all migrations forward to the current
        // latest (v6), validating the schema after the last one.
        helper.createDatabase(TEST_DB, 2).apply {
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 6, true, *DatabaseModule.ALL_MIGRATIONS)
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
}
