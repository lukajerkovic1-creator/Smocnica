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

    @Test
    fun migration2To3BackfillsCanonicalCategoryId() {
        helper.createDatabase(CATEGORY_DB, 2).apply {
            execSQL("INSERT INTO categories (id, pantryId, name, sortOrder, isDefault, revision, deletedAt, purgeAfter, syncState) VALUES ('c1', 'p1', 'Ostalo', 9, 1, 1, NULL, NULL, 'SYNCED')")
            execSQL(
                """
                INSERT INTO products (id, pantryId, name, normalizedName, barcode, description, category,
                    photoUri, photoSource, minimumQuantity, autoShopping, revision, createdAt, updatedAt,
                    deletedAt, purgeAfter, syncState)
                VALUES ('a', 'p1', 'Test', 'test', NULL, '', 'Ostalo', NULL, 'NONE', 0, 1, 1, 1, 1, NULL, NULL, 'SYNCED')
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(CATEGORY_DB, 3, true, MIGRATION_2_3).use { database ->
            database.query("SELECT category, categoryId FROM products WHERE id = 'a'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Ostalo", cursor.getString(0))
                assertEquals("c1", cursor.getString(1))
            }
        }
    }

    @Test
    fun migration3To4PreservesPantryAndAddsAccessQuarantine() {
        helper.createDatabase(ACCESS_DB, 3).apply {
            execSQL(
                "INSERT INTO pantries (id, name, ownerUid, revision, createdAt, updatedAt, deletedAt, purgeAfter, syncState) " +
                    "VALUES ('p1', 'Test', 'u1', 1, 1, 1, NULL, NULL, 'SYNCED')",
            )
            close()
        }

        helper.runMigrationsAndValidate(ACCESS_DB, 4, true, MIGRATION_3_4).use { database ->
            database.query("SELECT name, accessRevokedAt FROM pantries WHERE id = 'p1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Test", cursor.getString(0))
                assertNull(cursor.getString(1))
            }
        }
    }

    private companion object {
        const val TEST_DB = "activity-migration-test"
        const val CATEGORY_DB = "category-migration-test"
        const val ACCESS_DB = "access-migration-test"
    }
}
