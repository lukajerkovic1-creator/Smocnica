package hr.smocnica.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PantryEntity::class,
        MemberEntity::class,
        ShelfEntity::class,
        CategoryEntity::class,
        ProductEntity::class,
        ProductVariantEntity::class,
        StockEntity::class,
        ShoppingEntity::class,
        SynonymRuleEntity::class,
        ActivityEntity::class,
        InventoryEntity::class,
        PendingOperationEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class SmocnicaDatabase : RoomDatabase() {
    abstract fun pantryDao(): PantryDao
    abstract fun memberDao(): MemberDao
    abstract fun shelfDao(): ShelfDao
    abstract fun categoryDao(): CategoryDao
    abstract fun productDao(): ProductDao
    abstract fun productVariantDao(): ProductVariantDao
    abstract fun stockDao(): StockDao
    abstract fun shoppingDao(): ShoppingDao
    abstract fun synonymRuleDao(): SynonymRuleDao
    abstract fun activityDao(): ActivityDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun operationDao(): OperationDao
}
