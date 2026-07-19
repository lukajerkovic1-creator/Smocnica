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
