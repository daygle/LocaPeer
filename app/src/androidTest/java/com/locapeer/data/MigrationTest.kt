package com.locapeer.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locapeer.di.DatabaseModule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrateAll() {
        // Create earliest version of the database (version 2 is our oldest committed schema)
        helper.createDatabase(TEST_DB, 2).apply {
            close()
        }

        // Open it with the latest version and all migrations, then validate
        helper.runMigrationsAndValidate(TEST_DB, 5, true, *DatabaseModule.ALL_MIGRATIONS)
    }

    @Test
    @Throws(IOException::class)
    fun migrate4To5() {
        // Create version 4
        helper.createDatabase(TEST_DB, 4).apply {
            close()
        }

        // Upgrade to 5 and validate
        helper.runMigrationsAndValidate(TEST_DB, 5, true, *DatabaseModule.ALL_MIGRATIONS)
    }
}
