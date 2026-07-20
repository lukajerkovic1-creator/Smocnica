package hr.smocnica.core.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
