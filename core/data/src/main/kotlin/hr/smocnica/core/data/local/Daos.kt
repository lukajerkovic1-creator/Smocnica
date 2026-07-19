package hr.smocnica.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import hr.smocnica.core.model.OperationState
import kotlinx.coroutines.flow.Flow

@Dao
interface PantryDao {
    @Query("SELECT * FROM pantries WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeActive(): Flow<List<PantryEntity>>

    @Query("SELECT * FROM pantries WHERE id = :id")
    suspend fun get(id: String): PantryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PantryEntity)

    @Query("DELETE FROM pantries WHERE id = :pantryId")
    suspend fun deleteLocal(pantryId: String)

    @Query("UPDATE pantries SET deletedAt = :now WHERE id NOT IN (:activeIds)")
    suspend fun hideExcept(activeIds: List<String>, now: Long)

    @Query("UPDATE pantries SET deletedAt = :now")
    suspend fun hideAll(now: Long)
}

@Dao
interface MemberDao {
    @Query("SELECT * FROM members WHERE pantryId = :pantryId AND active = 1 ORDER BY role, displayName")
    fun observe(pantryId: String): Flow<List<MemberEntity>>

    @Query("SELECT * FROM members WHERE pantryId = :pantryId AND active = 1 ORDER BY role, displayName")
    suspend fun listActive(pantryId: String): List<MemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MemberEntity>)

    @Query("DELETE FROM members WHERE pantryId = :pantryId")
    suspend fun deleteForPantry(pantryId: String)

    @Query("UPDATE members SET active = 0 WHERE pantryId = :pantryId AND uid = :uid")
    suspend fun deactivate(pantryId: String, uid: String)

    @Query("UPDATE members SET role = CASE WHEN uid = :newOwnerUid THEN 'OWNER' ELSE 'MEMBER' END WHERE pantryId = :pantryId AND active = 1")
    suspend fun transferOwnership(pantryId: String, newOwnerUid: String)
}

@Dao
interface ShelfDao {
    @Query("SELECT * FROM shelves WHERE pantryId = :pantryId AND deletedAt IS NULL ORDER BY sortOrder, name")
    fun observeActive(pantryId: String): Flow<List<ShelfEntity>>

    @Query("SELECT * FROM shelves WHERE pantryId = :pantryId AND deletedAt IS NULL ORDER BY sortOrder, name")
    suspend fun listActive(pantryId: String): List<ShelfEntity>

    @Query("SELECT * FROM shelves WHERE pantryId = :pantryId")
    suspend fun listAll(pantryId: String): List<ShelfEntity>

    @Query("SELECT * FROM shelves WHERE pantryId = :pantryId AND deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeDeleted(pantryId: String): Flow<List<ShelfEntity>>

    @Query("DELETE FROM shelves WHERE id = :id")
    suspend fun deleteHard(id: String)

    @Query("SELECT * FROM shelves WHERE id = :id")
    suspend fun get(id: String): ShelfEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM shelves WHERE pantryId = :pantryId AND deletedAt IS NULL")
    suspend fun nextSortOrder(pantryId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ShelfEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ShelfEntity>)

    @Query("UPDATE shelves SET revision = :revision, syncState = 'SYNCED' WHERE id = :id")
    suspend fun markSynced(id: String, revision: Long)

    @Query("UPDATE shelves SET syncState = 'SYNCED' WHERE id = :id")
    suspend fun allowRemote(id: String)

    @Query("UPDATE shelves SET syncState = 'SYNCED' WHERE pantryId = :pantryId")
    suspend fun allowRemoteForPantry(pantryId: String)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE pantryId = :pantryId AND deletedAt IS NULL ORDER BY sortOrder, name")
    fun observeActive(pantryId: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE pantryId = :pantryId AND deletedAt IS NULL ORDER BY sortOrder, name")
    suspend fun listActive(pantryId: String): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE pantryId = :pantryId")
    suspend fun listAll(pantryId: String): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE pantryId = :pantryId AND deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeDeleted(pantryId: String): Flow<List<CategoryEntity>>

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteHard(id: String)

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun get(id: String): CategoryEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM categories WHERE pantryId = :pantryId AND deletedAt IS NULL")
    suspend fun nextSortOrder(pantryId: String): Int

    @Query("""
        UPDATE products
        SET category = :replacementName, categoryId = :replacementId, updatedAt = :updatedAt
        WHERE pantryId = :pantryId
          AND (categoryId = :oldId OR (categoryId IS NULL AND category = :oldName))
          AND deletedAt IS NULL
    """)
    suspend fun reassignProducts(
        pantryId: String,
        oldId: String,
        oldName: String,
        replacementId: String,
        replacementName: String,
        updatedAt: Long,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CategoryEntity>)

    @Query("UPDATE categories SET revision = :revision, syncState = 'SYNCED' WHERE id = :id")
    suspend fun markSynced(id: String, revision: Long)

    @Query("UPDATE categories SET syncState = 'SYNCED' WHERE id = :id")
    suspend fun allowRemote(id: String)

    @Query("UPDATE categories SET syncState = 'SYNCED' WHERE pantryId = :pantryId")
    suspend fun allowRemoteForPantry(pantryId: String)
}

@Dao
interface ProductDao {
    @Query("SELECT * FROM products WHERE pantryId = :pantryId AND deletedAt IS NULL ORDER BY normalizedName")
    fun observeActive(pantryId: String): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE pantryId = :pantryId AND deletedAt IS NULL ORDER BY normalizedName")
    suspend fun listActive(pantryId: String): List<ProductEntity>

    @Query("SELECT * FROM products WHERE pantryId = :pantryId")
    suspend fun listAll(pantryId: String): List<ProductEntity>

    @Query("SELECT * FROM products WHERE pantryId = :pantryId AND deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeDeleted(pantryId: String): Flow<List<ProductEntity>>

    @Query("DELETE FROM products WHERE id = :id")
    suspend fun deleteHard(id: String)

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun get(id: String): ProductEntity?

    @Query("SELECT * FROM products WHERE pantryId = :pantryId AND barcode = :barcode AND deletedAt IS NULL LIMIT 1")
    suspend fun findBarcode(pantryId: String, barcode: String): ProductEntity?

    @Query("SELECT * FROM products WHERE pantryId = :pantryId AND barcode = :barcode LIMIT 1")
    suspend fun findAnyBarcode(pantryId: String, barcode: String): ProductEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProductEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ProductEntity>)

    @Query("UPDATE products SET revision = :revision, syncState = 'SYNCED' WHERE id = :id")
    suspend fun markSynced(id: String, revision: Long)

    @Query("UPDATE products SET syncState = 'SYNCED' WHERE id = :id")
    suspend fun allowRemote(id: String)

    @Query("UPDATE products SET syncState = 'SYNCED' WHERE pantryId = :pantryId")
    suspend fun allowRemoteForPantry(pantryId: String)
}

@Dao
interface StockDao {
    @Query("SELECT * FROM stocks WHERE pantryId = :pantryId")
    fun observeForPantry(pantryId: String): Flow<List<StockEntity>>

    @Query("SELECT * FROM stocks WHERE productId = :productId AND shelfId = :shelfId")
    suspend fun get(productId: String, shelfId: String): StockEntity?

    @Query("SELECT COALESCE(SUM(quantity), 0) FROM stocks WHERE productId = :productId")
    suspend fun total(productId: String): Int

    @Query("SELECT quantity FROM stocks WHERE shelfId = :shelfId")
    suspend fun quantitiesOnShelf(shelfId: String): List<Int>

    @Query("SELECT * FROM stocks WHERE shelfId = :shelfId")
    suspend fun forShelf(shelfId: String): List<StockEntity>

    @Query("SELECT * FROM stocks WHERE pantryId = :pantryId")
    suspend fun listForPantry(pantryId: String): List<StockEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StockEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<StockEntity>)

    @Query("DELETE FROM stocks WHERE pantryId = :pantryId AND productId = :productId AND shelfId = :shelfId")
    suspend fun deleteHard(pantryId: String, productId: String, shelfId: String)

    @Query("UPDATE stocks SET revision = :revision, syncState = 'SYNCED' WHERE productId = :productId AND shelfId = :shelfId")
    suspend fun markSynced(productId: String, shelfId: String, revision: Long)

    @Query("UPDATE stocks SET syncState = 'SYNCED' WHERE productId = :productId AND shelfId = :shelfId")
    suspend fun allowRemote(productId: String, shelfId: String)

    @Query("UPDATE stocks SET syncState = 'SYNCED' WHERE pantryId = :pantryId")
    suspend fun allowRemoteForPantry(pantryId: String)
}

@Dao
interface ShoppingDao {
    @Query("SELECT * FROM shopping_items WHERE pantryId = :pantryId AND deletedAt IS NULL ORDER BY category, checked, name")
    fun observeActive(pantryId: String): Flow<List<ShoppingEntity>>

    @Query("SELECT * FROM shopping_items WHERE pantryId = :pantryId AND deletedAt IS NULL ORDER BY category, name")
    suspend fun listActive(pantryId: String): List<ShoppingEntity>

    @Query("SELECT * FROM shopping_items WHERE pantryId = :pantryId")
    suspend fun listAll(pantryId: String): List<ShoppingEntity>

    @Query("SELECT * FROM shopping_items WHERE id = :id")
    suspend fun get(id: String): ShoppingEntity?

    @Query("SELECT * FROM shopping_items WHERE pantryId = :pantryId AND productId = :productId LIMIT 1")
    suspend fun forProduct(pantryId: String, productId: String): ShoppingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ShoppingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ShoppingEntity>)

    @Query("DELETE FROM shopping_items WHERE id = :id")
    suspend fun deleteHard(id: String)

    @Query("UPDATE shopping_items SET category = :replacementName, updatedAt = :updatedAt WHERE pantryId = :pantryId AND category = :oldName AND deletedAt IS NULL")
    suspend fun reassignCategory(pantryId: String, oldName: String, replacementName: String, updatedAt: Long)

    @Query("UPDATE shopping_items SET revision = :revision, syncState = 'SYNCED' WHERE id = :id")
    suspend fun markSynced(id: String, revision: Long)

    @Query("UPDATE shopping_items SET syncState = 'SYNCED' WHERE id = :id")
    suspend fun allowRemote(id: String)

    @Query("UPDATE shopping_items SET syncState = 'SYNCED' WHERE pantryId = :pantryId")
    suspend fun allowRemoteForPantry(pantryId: String)
}

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activities WHERE pantryId = :pantryId AND createdAt >= :since ORDER BY createdAt DESC")
    fun observeSince(pantryId: String, since: Long): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE pantryId = :pantryId AND createdAt >= :since ORDER BY createdAt DESC")
    suspend fun listSince(pantryId: String, since: Long): List<ActivityEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ActivityEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<ActivityEntity>)

    @Query("DELETE FROM activities WHERE createdAt < :before")
    suspend fun deleteBefore(before: Long)
}

@Dao
interface InventoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: InventoryEntity)

    @Query("SELECT * FROM inventory_sessions WHERE id = :id")
    suspend fun get(id: String): InventoryEntity?

    @Query("SELECT * FROM inventory_sessions WHERE pantryId = :pantryId AND status = 'DRAFT' ORDER BY createdAt DESC LIMIT 1")
    fun observeLatestDraft(pantryId: String): Flow<InventoryEntity?>

    @Query("SELECT * FROM inventory_sessions WHERE pantryId = :pantryId AND status = 'DRAFT' ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestDraft(pantryId: String): InventoryEntity?

    @Query("DELETE FROM inventory_sessions WHERE id = :id AND status = 'DRAFT'")
    suspend fun deleteDraft(id: String)
}

data class OperationStateCount(val state: OperationState, val count: Int)

@Dao
interface OperationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: PendingOperationEntity)

    @Query("SELECT * FROM pending_operations WHERE state IN ('PENDING', 'IN_FLIGHT') ORDER BY createdAt, rowid LIMIT :limit")
    suspend fun next(limit: Int = 50): List<PendingOperationEntity>

    @Query("SELECT * FROM pending_operations WHERE operationId = :id")
    suspend fun get(id: String): PendingOperationEntity?

    @Query("SELECT * FROM pending_operations WHERE pantryId = :pantryId AND state IN ('CONFLICT', 'PERMANENT_FAILURE') ORDER BY createdAt")
    fun observeConflicts(pantryId: String): Flow<List<PendingOperationEntity>>

    @Query("UPDATE pending_operations SET state = :state, attempts = attempts + :attemptIncrement, errorCode = :errorCode WHERE operationId = :id")
    suspend fun setState(id: String, state: OperationState, attemptIncrement: Int = 0, errorCode: String? = null)

    @Query("UPDATE pending_operations SET state = 'PENDING', baseRevision = :baseRevision, errorCode = NULL WHERE operationId = :id")
    suspend fun requeue(id: String, baseRevision: Long)

    @Query("UPDATE pending_operations SET state = 'PENDING', baseRevision = :baseRevision, payloadJson = :payloadJson, errorCode = NULL WHERE operationId = :id")
    suspend fun requeue(id: String, baseRevision: Long, payloadJson: String)

    @Query("DELETE FROM pending_operations WHERE operationId = :id")
    suspend fun delete(id: String)

    @Query("SELECT state, COUNT(*) AS count FROM pending_operations WHERE pantryId = :pantryId GROUP BY state")
    fun observeCounts(pantryId: String): Flow<List<OperationStateCount>>

    @Query("SELECT COUNT(*) FROM pending_operations")
    suspend fun countUnsynced(): Int
}
