package hr.smocnica.core.domain

import hr.smocnica.core.model.Activity
import hr.smocnica.core.model.Category
import hr.smocnica.core.model.InventorySession
import hr.smocnica.core.model.Member
import hr.smocnica.core.model.Pantry
import hr.smocnica.core.model.PantrySnapshot
import hr.smocnica.core.model.Product
import hr.smocnica.core.model.ProductFilter
import hr.smocnica.core.model.ProductWithStock
import hr.smocnica.core.model.Shelf
import hr.smocnica.core.model.ShoppingItem
import hr.smocnica.core.model.SyncSummary
import hr.smocnica.core.model.TrashItem
import hr.smocnica.core.model.UserSession
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    val session: Flow<UserSession?>
    suspend fun signInWithGoogleIdToken(idToken: String): Result<UserSession>
    suspend fun signOut()
}

interface PantryRepository {
    fun observePantries(): Flow<List<Pantry>>
    fun observeMembers(pantryId: String): Flow<List<Member>>
    suspend fun refreshPantries()
    suspend fun createPantry(name: String, deviceId: String): Pantry
    suspend fun createInvitation(pantryId: String): Invitation
    suspend fun joinPantry(code: String, deviceId: String): Pantry
    suspend fun removeMember(pantryId: String, uid: String, deviceId: String, deviceName: String)
    suspend fun transferOwnership(pantryId: String, uid: String, deviceId: String, deviceName: String)
    suspend fun deletePantry(pantryId: String, deviceId: String, deviceName: String)
}

data class Invitation(val code: String, val expiresAt: Long)

interface InventoryRepository {
    fun observeShelves(pantryId: String): Flow<List<Shelf>>
    fun observeProducts(pantryId: String, filter: ProductFilter = ProductFilter()): Flow<List<ProductWithStock>>
    fun observeDeletedProducts(pantryId: String): Flow<List<ProductWithStock>>
    fun observeCategories(pantryId: String): Flow<List<Category>>
    fun observeShopping(pantryId: String): Flow<List<ShoppingItem>>
    fun observeActivities(pantryId: String, since: Long): Flow<List<Activity>>
    fun observeInventoryDraft(pantryId: String): Flow<InventorySession?>
    fun observeSyncSummary(pantryId: String): Flow<SyncSummary>
    suspend fun createShelf(pantryId: String, name: String, actorUid: String, deviceName: String): Shelf
    suspend fun renameShelf(shelf: Shelf, name: String, actorUid: String, deviceName: String)
    suspend fun reorderShelves(pantryId: String, orderedIds: List<String>, baseRevision: Long, actorUid: String, deviceName: String)
    suspend fun deleteShelf(shelf: Shelf, actorUid: String, deviceName: String)
    suspend fun upsertCategory(category: Category, actorUid: String, deviceName: String): Category
    suspend fun reorderCategories(pantryId: String, orderedIds: List<String>, baseRevision: Long, actorUid: String, deviceName: String)
    suspend fun deleteCategory(category: Category, replacementCategoryId: String, actorUid: String, deviceName: String)
    suspend fun upsertProduct(product: Product, actorUid: String, deviceName: String): Product
    suspend fun deleteProduct(product: Product, actorUid: String, deviceName: String)
    suspend fun adjustStock(productId: String, shelfId: String, delta: Int, actorUid: String, deviceName: String)
    suspend fun restoreProductAndAdjustStock(pantryId: String, productId: String, shelfId: String, quantity: Int, actorUid: String, deviceName: String)
    suspend fun moveStock(productId: String, fromShelfId: String, toShelfId: String, quantity: Int, actorUid: String, deviceName: String)
    suspend fun addManualShoppingItem(pantryId: String, name: String, category: String, quantity: Int, actorUid: String, deviceName: String, checked: Boolean = false): ShoppingItem
    suspend fun updateManualShoppingItem(item: ShoppingItem, name: String, category: String, quantity: Int, actorUid: String, deviceName: String)
    suspend fun deleteManualShoppingItem(item: ShoppingItem, actorUid: String, deviceName: String): ShoppingItem
    suspend fun setShoppingChecked(item: ShoppingItem, checked: Boolean, actorUid: String, deviceName: String)
    suspend fun previewInventory(pantryId: String, shelfId: String, counts: Map<String, Int>, actorUid: String, deviceName: String): InventorySession
    suspend fun applyInventory(session: InventorySession, actorUid: String, deviceName: String)
    suspend fun discardInventoryDraft(id: String)
    suspend fun restore(pantryId: String, type: String, id: String, actorUid: String, deviceName: String)
}

interface SyncRepository {
    fun startRealtime(pantryId: String)
    fun stopRealtime()
    fun observeConflicts(pantryId: String): Flow<List<SyncConflict>>
    suspend fun synchronize(): SyncResult
    suspend fun resolveConflict(operationId: String, keepLocal: Boolean)
    suspend fun hasUnsyncedChanges(): Boolean
}

data class SyncConflict(
    val operationId: String,
    val aggregateType: String,
    val aggregateId: String,
    val description: String,
    val createdAt: Long,
    val isRevisionConflict: Boolean,
    val errorCode: String?,
)

data class SyncResult(val applied: Int, val conflicts: Int, val failed: Int)

interface BackupRepository {
    suspend fun exportJson(pantryId: String): ByteArray
    suspend fun exportCsv(pantryId: String): ByteArray
    suspend fun previewImport(bytes: ByteArray): ImportPreview
    suspend fun import(preview: ImportPreview, strategy: ImportStrategy, targetPantryId: String, actorUid: String, deviceName: String)
}

enum class ImportStrategy { MERGE, REPLACE }

data class ImportPreview(
    val schemaVersion: Int,
    val pantryName: String,
    val shelfCount: Int,
    val productCount: Int,
    val shoppingCount: Int,
    val snapshot: PantrySnapshot,
    val conflicts: List<String> = emptyList(),
)

interface ProductCatalogRepository {
    suspend fun findByBarcode(barcode: String): CatalogProduct?
}

interface ProductPhotoRepository {
    suspend fun uploadJpeg(pantryId: String, productId: String, jpegPath: String): String
    suspend fun deleteMainPhoto(pantryId: String, productId: String)
}

data class CatalogProduct(
    val barcode: String,
    val name: String,
    val description: String,
    val category: String,
    val imageUrl: String?,
)

interface UpdateRepository {
    suspend fun check(currentVersionCode: Long): AppUpdate?
    suspend fun downloadAndVerify(update: AppUpdate): VerifiedApk
}

interface TrashRepository {
    fun observe(pantryId: String): Flow<List<TrashItem>>
    suspend fun purgeNow(pantryId: String, item: TrashItem)
}

data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val minimumSupportedVersionCode: Long,
    val apkUrl: String,
    val sha256: String,
    val releaseNotes: String,
    val sizeBytes: Long = 0,
    val forceUpdate: Boolean = false,
) {
    fun isMandatory(currentVersionCode: Long): Boolean = forceUpdate || currentVersionCode < minimumSupportedVersionCode
}

data class VerifiedApk(val contentUri: String, val versionCode: Long)
