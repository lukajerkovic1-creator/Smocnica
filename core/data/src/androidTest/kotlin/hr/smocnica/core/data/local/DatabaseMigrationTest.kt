package hr.smocnica.core.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SmocnicaDatabase::class.java,
    )

    @Test
    fun migration1To2PreservesActivitiesAndAddsStructuredReferences() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO activities (
                    id, pantryId, type, aggregateId, displayLabel, quantityDelta,
                    actorUid, deviceId, deviceName, oldValue, newValue, createdAt
                ) VALUES ('a1', 'p1', 'STOCK_MOVED', 'product1', 'Riža', 2,
                    'u1', 'device1', 'Telefon', 'Polica 1', 'Polica 2', 1234)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).use { database ->
            database.query(
                "SELECT displayLabel, productId, shelfId, fromShelfId, toShelfId FROM activities WHERE id = 'a1'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("Riža", cursor.getString(0))
                assertNull(cursor.getString(1))
                assertNull(cursor.getString(2))
                assertNull(cursor.getString(3))
                assertNull(cursor.getString(4))
            }
        }
    }

    private companion object {
        const val TEST_DB = "activity-migration-test"
    }
}
