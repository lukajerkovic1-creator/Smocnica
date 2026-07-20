package hr.smocnica.core.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
