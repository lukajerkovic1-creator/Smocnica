package hr.smocnica.core.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun migration4To5BackfillsCanonicalNamesShoppingCategoryAndSingleDefault() {
        helper.createDatabase(CANONICAL_NAMES_DB, 4).apply {
            execSQL("INSERT INTO shelves (id, pantryId, name, sortOrder, revision, createdAt, updatedAt, deletedAt, purgeAfter, syncState) VALUES ('s1', 'p1', '  POLICA   Čaj ', 0, 1, 1, 1, NULL, NULL, 'SYNCED')")
            execSQL("INSERT INTO categories (id, pantryId, name, sortOrder, isDefault, revision, deletedAt, purgeAfter, syncState) VALUES ('c1', 'p1', 'Namirnice', 0, 1, 1, NULL, NULL, 'SYNCED')")
            execSQL("INSERT INTO categories (id, pantryId, name, sortOrder, isDefault, revision, deletedAt, purgeAfter, syncState) VALUES ('c2', 'p1', 'Ostalo', 1, 1, 1, NULL, NULL, 'SYNCED')")
            execSQL("INSERT INTO shopping_items (id, pantryId, productId, name, category, requiredQuantity, checked, manual, revision, createdAt, updatedAt, deletedAt, syncState) VALUES ('m1', 'p1', NULL, 'Kruh', 'Namirnice', 1, 0, 1, 1, 1, 1, NULL, 'SYNCED')")
            close()
        }

        helper.runMigrationsAndValidate(CANONICAL_NAMES_DB, 5, true, MIGRATION_4_5).use { database ->
            database.query("SELECT normalizedName FROM shelves WHERE id = 's1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("polica čaj", cursor.getString(0))
            }
            database.query("SELECT categoryId FROM shopping_items WHERE id = 'm1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("c1", cursor.getString(0))
            }
            database.query("SELECT COUNT(*) FROM categories WHERE pantryId = 'p1' AND deletedAt IS NULL AND isDefault = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    @Test
    fun rc9SchemaMigratesDirectlyToCurrentWithoutLosingOfflinePantryData() {
        helper.createDatabase(RC9_TO_CURRENT_DB, 1).apply {
            execSQL("INSERT INTO pantries (id, name, ownerUid, revision, createdAt, updatedAt, deletedAt, purgeAfter, syncState) VALUES ('p1', 'Kućna smočnica', 'owner1', 7, 100, 200, NULL, NULL, 'SYNCED')")
            execSQL("INSERT INTO members (pantryId, uid, displayName, photoUrl, role, joinedAt, active) VALUES ('p1', 'owner1', 'Vlasnik', 'https://example.test/avatar.jpg', 'OWNER', 100, 1)")
            execSQL("INSERT INTO shelves (id, pantryId, name, sortOrder, revision, createdAt, updatedAt, deletedAt, purgeAfter, syncState) VALUES ('s1', 'p1', '  HLADNJAK  ', 0, 3, 100, 200, NULL, NULL, 'SYNCED')")
            execSQL("INSERT INTO categories (id, pantryId, name, sortOrder, isDefault, revision, deletedAt, purgeAfter, syncState) VALUES ('c1', 'p1', 'Ostalo', 9, 1, 2, NULL, NULL, 'SYNCED')")
            execSQL(
                """
                INSERT INTO products (id, pantryId, name, normalizedName, barcode, description, category,
                    photoUri, photoSource, minimumQuantity, autoShopping, revision, createdAt, updatedAt,
                    deletedAt, purgeAfter, syncState)
                VALUES ('product1', 'p1', 'Mlijeko', 'mlijeko', '3850000000018', '1 L', 'Ostalo',
                    'content://hr.smocnica.photos/product1.jpg', 'USER', 2, 1, 4, 100, 200,
                    NULL, NULL, 'PENDING')
                """.trimIndent(),
            )
            execSQL("INSERT INTO stocks (pantryId, productId, shelfId, quantity, revision, updatedAt, syncState) VALUES ('p1', 'product1', 's1', 3, 5, 200, 'PENDING')")
            execSQL("INSERT INTO shopping_items (id, pantryId, productId, name, category, requiredQuantity, checked, manual, revision, createdAt, updatedAt, deletedAt, syncState) VALUES ('shop1', 'p1', 'product1', 'Mlijeko', 'Ostalo', 1, 0, 0, 2, 100, 200, NULL, 'PENDING')")
            execSQL("INSERT INTO activities (id, pantryId, type, aggregateId, displayLabel, quantityDelta, actorUid, deviceId, deviceName, oldValue, newValue, createdAt) VALUES ('activity1', 'p1', 'STOCK_ADDED', 'product1', 'Mlijeko', 3, 'owner1', 'device1', 'Telefon', NULL, '3', 200)")
            execSQL("INSERT INTO inventory_sessions (id, pantryId, shelfId, expectedRevision, status, countsJson, differencesJson, createdBy, deviceName, createdAt, appliedAt) VALUES ('inventory1', 'p1', 's1', 5, 'DRAFT', '{\"product1\":3}', '[]', 'owner1', 'Telefon', 200, NULL)")
            execSQL("INSERT INTO pending_operations (operationId, pantryId, aggregateType, aggregateId, baseRevision, payloadJson, actorUid, deviceId, deviceName, createdAt, attempts, state, errorCode) VALUES ('operation1', 'p1', 'STOCK', 'product1', 4, '{\"quantity\":2}', 'owner1', 'device1', 'Telefon', 201, 0, 'PENDING', NULL)")
            close()
        }

        helper.runMigrationsAndValidate(
            RC9_TO_CURRENT_DB,
            5,
            true,
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
        ).use { database ->
            database.query("SELECT name, ownerUid, accessRevokedAt FROM pantries WHERE id = 'p1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Kućna smočnica", cursor.getString(0))
                assertEquals("owner1", cursor.getString(1))
                assertNull(cursor.getString(2))
            }
            database.query("SELECT displayName, role, active FROM members WHERE pantryId = 'p1' AND uid = 'owner1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Vlasnik", cursor.getString(0))
                assertEquals("OWNER", cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
            }
            database.query("SELECT name, normalizedName FROM shelves WHERE id = 's1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("  HLADNJAK  ", cursor.getString(0))
                assertEquals("hladnjak", cursor.getString(1))
            }
            database.query("SELECT normalizedName, isDefault FROM categories WHERE id = 'c1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ostalo", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }
            database.query("SELECT name, categoryId, photoUri, syncState FROM products WHERE id = 'product1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Mlijeko", cursor.getString(0))
                assertEquals("c1", cursor.getString(1))
                assertEquals("content://hr.smocnica.photos/product1.jpg", cursor.getString(2))
                assertEquals("PENDING", cursor.getString(3))
            }
            database.query("SELECT quantity, syncState FROM stocks WHERE pantryId = 'p1' AND productId = 'product1' AND shelfId = 's1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3, cursor.getInt(0))
                assertEquals("PENDING", cursor.getString(1))
            }
            database.query("SELECT categoryId, requiredQuantity, syncState FROM shopping_items WHERE id = 'shop1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("c1", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals("PENDING", cursor.getString(2))
            }
            database.query("SELECT productId, shelfId, fromShelfId, toShelfId FROM activities WHERE id = 'activity1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertNull(cursor.getString(0))
                assertNull(cursor.getString(1))
                assertNull(cursor.getString(2))
                assertNull(cursor.getString(3))
            }
            database.query("SELECT countsJson, status FROM inventory_sessions WHERE id = 'inventory1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("{\"product1\":3}", cursor.getString(0))
                assertEquals("DRAFT", cursor.getString(1))
            }
            database.query("SELECT payloadJson, state, attempts FROM pending_operations WHERE operationId = 'operation1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("{\"quantity\":2}", cursor.getString(0))
                assertEquals("PENDING", cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
            }
        }
    }

    private companion object {
        const val TEST_DB = "activity-migration-test"
        const val CATEGORY_DB = "category-migration-test"
        const val ACCESS_DB = "access-migration-test"
        const val CANONICAL_NAMES_DB = "canonical-names-migration-test"
        const val RC9_TO_CURRENT_DB = "rc9-to-current-migration-test"
    }
}
