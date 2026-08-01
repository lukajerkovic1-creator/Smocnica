package hr.smocnica.core.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import hr.smocnica.core.domain.PackageAmountPolicy
import java.text.Normalizer
import java.util.Locale

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE activities ADD COLUMN productId TEXT")
        db.execSQL("ALTER TABLE activities ADD COLUMN shelfId TEXT")
        db.execSQL("ALTER TABLE activities ADD COLUMN fromShelfId TEXT")
        db.execSQL("ALTER TABLE activities ADD COLUMN toShelfId TEXT")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE products ADD COLUMN categoryId TEXT")
        db.execSQL(
            """
            UPDATE products
            SET categoryId = (
                SELECT categories.id FROM categories
                WHERE categories.pantryId = products.pantryId
                  AND categories.name = products.category
                  AND categories.deletedAt IS NULL
                LIMIT 1
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            UPDATE products
            SET categoryId = (
                SELECT categories.id FROM categories
                WHERE categories.pantryId = products.pantryId
                  AND categories.isDefault = 1
                  AND categories.deletedAt IS NULL
                LIMIT 1
            )
            WHERE categoryId IS NULL
            """.trimIndent(),
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pantries ADD COLUMN accessRevokedAt INTEGER")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE shelves ADD COLUMN normalizedName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE categories ADD COLUMN normalizedName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE shopping_items ADD COLUMN categoryId TEXT")
        backfillNormalizedNames(db, "shelves")
        backfillNormalizedNames(db, "categories")
        db.execSQL(
            """
            UPDATE shopping_items
            SET categoryId = (
                SELECT products.categoryId FROM products
                WHERE products.pantryId = shopping_items.pantryId
                  AND products.id = shopping_items.productId
                LIMIT 1
            )
            WHERE productId IS NOT NULL
            """.trimIndent(),
        )
        db.execSQL(
            """
            UPDATE shopping_items
            SET categoryId = (
                SELECT categories.id FROM categories
                WHERE categories.pantryId = shopping_items.pantryId
                  AND LOWER(TRIM(categories.name)) = LOWER(TRIM(shopping_items.category))
                  AND categories.deletedAt IS NULL
                ORDER BY categories.sortOrder, categories.id
                LIMIT 1
            )
            WHERE categoryId IS NULL
            """.trimIndent(),
        )
        db.execSQL(
            """
            UPDATE shopping_items
            SET categoryId = (
                SELECT categories.id FROM categories
                WHERE categories.pantryId = shopping_items.pantryId
                  AND categories.deletedAt IS NULL
                ORDER BY CASE WHEN categories.isDefault = 1 THEN 0 ELSE 1 END,
                         CASE WHEN LOWER(TRIM(categories.name)) = 'ostalo' THEN 0 ELSE 1 END,
                         categories.sortOrder,
                         categories.id
                LIMIT 1
            )
            WHERE categoryId IS NULL
            """.trimIndent(),
        )
        db.execSQL("UPDATE categories SET isDefault = 0 WHERE deletedAt IS NOT NULL")
        db.execSQL(
            """
            UPDATE categories
            SET isDefault = CASE WHEN id = (
                SELECT candidate.id FROM categories AS candidate
                WHERE candidate.pantryId = categories.pantryId
                  AND candidate.deletedAt IS NULL
                ORDER BY CASE WHEN candidate.isDefault = 1 THEN 0 ELSE 1 END,
                         CASE WHEN LOWER(TRIM(candidate.name)) = 'ostalo' THEN 0 ELSE 1 END,
                         candidate.sortOrder,
                         candidate.id
                LIMIT 1
            ) THEN 1 ELSE 0 END
            WHERE deletedAt IS NULL
            """.trimIndent(),
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pantries ADD COLUMN contentSchemaVersion INTEGER NOT NULL DEFAULT 2")
        db.execSQL("ALTER TABLE pantries ADD COLUMN groupingReviewCompletedAt INTEGER")
        db.execSQL("ALTER TABLE products ADD COLUMN minimumMode TEXT NOT NULL DEFAULT 'PACKAGES'")
        db.execSQL("ALTER TABLE products ADD COLUMN minimumAmountBase INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE products ADD COLUMN preferredVariantId TEXT")
        db.execSQL("ALTER TABLE products ADD COLUMN doNotGroup INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE products ADD COLUMN groupingRevision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE products SET minimumAmountBase = minimumQuantity")
        db.execSQL("ALTER TABLE shopping_items ADD COLUMN preferredVariantId TEXT")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS product_variants (
                id TEXT NOT NULL,
                pantryId TEXT NOT NULL,
                productId TEXT NOT NULL,
                displayName TEXT NOT NULL,
                manufacturer TEXT NOT NULL,
                barcode TEXT,
                packageAmountBase INTEGER,
                packageUnit TEXT NOT NULL,
                packageLabel TEXT NOT NULL,
                description TEXT NOT NULL,
                photoUri TEXT,
                photoSource TEXT NOT NULL,
                minimumPackages INTEGER,
                purchaseCount INTEGER NOT NULL,
                revision INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                deletedAt INTEGER,
                purgeAfter INTEGER,
                syncState TEXT NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_product_variants_pantryId ON product_variants(pantryId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_product_variants_productId ON product_variants(productId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_product_variants_pantryId_barcode ON product_variants(pantryId, barcode)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_product_variants_deletedAt ON product_variants(deletedAt)")

        db.query(
            """
            SELECT id, pantryId, name, barcode, description, photoUri, photoSource, revision,
                   createdAt, updatedAt, deletedAt, purgeAfter, syncState
            FROM products
            """.trimIndent(),
        ).use { cursor ->
            fun index(name: String) = cursor.getColumnIndexOrThrow(name)
            while (cursor.moveToNext()) {
                val description = cursor.getString(index("description"))
                val parsed = PackageAmountPolicy.parse(description)
                val values = arrayOf<Any?>(
                    cursor.getString(index("id")),
                    cursor.getString(index("pantryId")),
                    cursor.getString(index("id")),
                    cursor.getString(index("name")),
                    "",
                    cursor.getString(index("barcode")),
                    parsed?.amountBase,
                    parsed?.unit?.name ?: "UNKNOWN",
                    parsed?.label ?: description,
                    description,
                    cursor.getString(index("photoUri")),
                    cursor.getString(index("photoSource")),
                    null,
                    0L,
                    cursor.getLong(index("revision")),
                    cursor.getLong(index("createdAt")),
                    cursor.getLong(index("updatedAt")),
                    cursor.getLong(index("deletedAt")).takeUnless { cursor.isNull(index("deletedAt")) },
                    cursor.getLong(index("purgeAfter")).takeUnless { cursor.isNull(index("purgeAfter")) },
                    cursor.getString(index("syncState")),
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO product_variants (
                        id, pantryId, productId, displayName, manufacturer, barcode, packageAmountBase,
                        packageUnit, packageLabel, description, photoUri, photoSource, minimumPackages,
                        purchaseCount, revision, createdAt, updatedAt, deletedAt, purgeAfter, syncState
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    values,
                )
            }
        }
        db.execSQL("UPDATE products SET preferredVariantId = id")

        db.execSQL(
            """
            CREATE TABLE stocks_new (
                pantryId TEXT NOT NULL,
                productId TEXT NOT NULL,
                shelfId TEXT NOT NULL,
                quantity INTEGER NOT NULL,
                revision INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                syncState TEXT NOT NULL,
                variantId TEXT NOT NULL,
                PRIMARY KEY(pantryId, variantId, shelfId)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO stocks_new (pantryId, productId, shelfId, quantity, revision, updatedAt, syncState, variantId)
            SELECT pantryId, productId, shelfId, quantity, revision, updatedAt, syncState, productId FROM stocks
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE stocks")
        db.execSQL("ALTER TABLE stocks_new RENAME TO stocks")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_stocks_productId ON stocks(productId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_stocks_variantId ON stocks(variantId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_stocks_shelfId ON stocks(shelfId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS synonym_rules (
                id TEXT NOT NULL,
                pantryId TEXT NOT NULL,
                sourceNormalized TEXT NOT NULL,
                genericName TEXT NOT NULL,
                genericNameNormalized TEXT NOT NULL,
                productId TEXT,
                ownerConfirmed INTEGER NOT NULL,
                revision INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                deletedAt INTEGER,
                syncState TEXT NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_synonym_rules_pantryId ON synonym_rules(pantryId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_synonym_rules_pantryId_sourceNormalized ON synonym_rules(pantryId, sourceNormalized)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_synonym_rules_deletedAt ON synonym_rules(deletedAt)")
    }
}

private fun backfillNormalizedNames(db: SupportSQLiteDatabase, table: String) {
    db.query("SELECT id, name FROM $table").use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow("id")
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        while (cursor.moveToNext()) {
            val normalized = Normalizer.normalize(cursor.getString(nameIndex), Normalizer.Form.NFKC)
                .trim()
                .replace(Regex("\\s+"), " ")
                .lowercase(Locale.forLanguageTag("hr"))
            db.execSQL("UPDATE $table SET normalizedName = ? WHERE id = ?", arrayOf(normalized, cursor.getString(idIndex)))
        }
    }
}
