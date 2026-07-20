package hr.smocnica.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

typealias PantryId = String
typealias ProductId = String
typealias ShelfId = String
typealias CategoryId = String
typealias UserId = String

@Serializable
enum class MemberRole { OWNER, MEMBER }

@Serializable
enum class SyncState { SYNCED, PENDING, SYNCING, CONFLICT, FAILED }

@Serializable
data class Pantry(
    val id: PantryId,
    val name: String,
    val ownerUid: UserId,
    val revision: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val purgeAfter: Long? = null,
    val syncState: SyncState = SyncState.SYNCED,
)

@Serializable
data class Member(
    val pantryId: PantryId,
    val uid: UserId,
    val displayName: String,
    val photoUrl: String? = null,
    val role: MemberRole,
    val joinedAt: Long,
    val active: Boolean = true,
)

@Serializable
data class Shelf(
    val id: ShelfId,
    val pantryId: PantryId,
    val name: String,
    val sortOrder: Int,
    val revision: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val purgeAfter: Long? = null,
    val syncState: SyncState = SyncState.SYNCED,
)

@Serializable
data class Product(
    val id: ProductId,
    val pantryId: PantryId,
    val name: String,
    val barcode: String? = null,
    val description: String = "",
    val category: String = "Ostalo",
    val photoUri: String? = null,
    val photoSource: PhotoSource = PhotoSource.NONE,
    val minimumQuantity: Int = 0,
    val autoShopping: Boolean = true,
    val revision: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val purgeAfter: Long? = null,
    val syncState: SyncState = SyncState.SYNCED,
    val categoryId: String = "",
)

@Serializable
enum class PhotoSource { NONE, OPEN_FOOD_FACTS, CAMERA, GALLERY }

@Serializable
data class Category(
    val id: String,
    val pantryId: PantryId,
    val name: String,
    val sortOrder: Int,
    val isDefault: Boolean = false,
    val revision: Long = 0,
    val deletedAt: Long? = null,
    val purgeAfter: Long? = null,
    val syncState: SyncState = SyncState.SYNCED,
)

@Serializable
data class Stock(
    val pantryId: PantryId,
    val productId: ProductId,
    val shelfId: ShelfId,
    val quantity: Int,
    val revision: Long = 0,
    val updatedAt: Long,
    val syncState: SyncState = SyncState.SYNCED,
)

@Serializable
data class ProductWithStock(
    val product: Product,
    val stocks: List<Stock>,
) {
    val totalQuantity: Int get() = stocks.sumOf(Stock::quantity)
    val shortfall: Int get() = (product.minimumQuantity - totalQuantity).coerceAtLeast(0)
    val isBelowMinimum: Boolean get() = totalQuantity < product.minimumQuantity
}

@Serializable
data class ShoppingItem(
    val id: String,
    val pantryId: PantryId,
    val productId: ProductId? = null,
    val name: String,
    val category: String = "Ostalo",
    val requiredQuantity: Int,
    val checked: Boolean = false,
    val manual: Boolean,
    val revision: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncState: SyncState = SyncState.SYNCED,
    val categoryId: String = "",
)

@Serializable
enum class ActivityType {
    PANTRY_CREATED,
    MEMBER_JOINED,
    MEMBER_REMOVED,
    OWNERSHIP_TRANSFERRED,
    SHELF_CREATED,
    SHELF_RENAMED,
    SHELF_REORDERED,
    SHELF_DELETED,
    CATEGORY_CREATED,
    CATEGORY_UPDATED,
    CATEGORY_REORDERED,
    CATEGORY_DELETED,
    PRODUCT_CREATED,
    PRODUCT_UPDATED,
    STOCK_ADDED,
    STOCK_REMOVED,
    STOCK_MOVED,
    INVENTORY_APPLIED,
    ITEM_DELETED,
    ITEM_RESTORED,
    SHOPPING_UPDATED,
    IMPORT_APPLIED,
    UNKNOWN,
}

@Serializable
data class Activity(
    val id: String,
    val pantryId: PantryId,
    val type: ActivityType,
    val aggregateId: String,
    val displayLabel: String,
    val quantityDelta: Int? = null,
    val actorUid: UserId,
    val deviceId: String,
    val deviceName: String,
    val oldValue: String? = null,
    val newValue: String? = null,
    val createdAt: Long,
    val productId: ProductId? = null,
    val shelfId: ShelfId? = null,
    val fromShelfId: ShelfId? = null,
    val toShelfId: ShelfId? = null,
)

@Serializable
enum class InventoryStatus { DRAFT, APPLIED, CANCELLED }

@Serializable
enum class InventoryDifferenceType { MISSING, UNEXPECTED, QUANTITY }

@Serializable
data class InventoryCount(
    val productId: ProductId,
    val actualQuantity: Int,
)

@Serializable
data class InventoryDifference(
    val productId: ProductId,
    val productName: String,
    val expectedQuantity: Int,
    val actualQuantity: Int,
    val type: InventoryDifferenceType,
)

@Serializable
data class InventorySession(
    val id: String,
    val pantryId: PantryId,
    val shelfId: ShelfId,
    val expectedRevision: Long,
    val status: InventoryStatus,
    val counts: List<InventoryCount>,
    val differences: List<InventoryDifference>,
    val createdBy: UserId,
    val deviceName: String,
    val createdAt: Long,
    val appliedAt: Long? = null,
)

@Serializable
enum class AggregateType { PANTRY, SHELF, CATEGORY, PRODUCT, STOCK, SHOPPING, INVENTORY, MEMBER }

@Serializable
enum class OperationState { PENDING, IN_FLIGHT, CONFLICT, PERMANENT_FAILURE }

@Serializable
data class PendingOperation(
    val operationId: String,
    val pantryId: PantryId,
    val aggregateType: AggregateType,
    val aggregateId: String,
    val baseRevision: Long,
    val payload: OperationPayload,
    val actorUid: UserId,
    val deviceId: String,
    val deviceName: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val state: OperationState = OperationState.PENDING,
    val errorCode: String? = null,
)

@Serializable
sealed interface OperationPayload {
    @Serializable
    @SerialName("create_shelf")
    data class CreateShelf(val shelf: Shelf) : OperationPayload

    @Serializable
    @SerialName("rename_shelf")
    data class RenameShelf(val shelfId: ShelfId, val name: String) : OperationPayload

    @Serializable
    @SerialName("reorder_shelves")
    data class ReorderShelves(val orderedShelfIds: List<ShelfId>) : OperationPayload

    @Serializable
    @SerialName("reorder_categories")
    data class ReorderCategories(val orderedCategoryIds: List<String>) : OperationPayload

    @Serializable
    @SerialName("delete_shelf")
    data class DeleteShelf(val shelfId: ShelfId) : OperationPayload

    @Serializable
    @SerialName("upsert_category")
    data class UpsertCategory(val category: Category) : OperationPayload

    @Serializable
    @SerialName("delete_category")
    data class DeleteCategory(
        val categoryId: String,
        val replacementCategoryId: String,
    ) : OperationPayload

    @Serializable
    @SerialName("upsert_product")
    data class UpsertProduct(val product: Product) : OperationPayload

    @Serializable
    @SerialName("adjust_stock")
    data class AdjustStock(
        val productId: ProductId,
        val shelfId: ShelfId,
        val delta: Int,
        val productName: String = "",
        val shelfName: String = "",
    ) : OperationPayload

    @Serializable
    @SerialName("move_stock")
    data class MoveStock(
        val productId: ProductId,
        val fromShelfId: ShelfId,
        val toShelfId: ShelfId,
        val quantity: Int,
        val productName: String = "",
        val fromShelfName: String = "",
        val toShelfName: String = "",
    ) : OperationPayload

    @Serializable
    @SerialName("upsert_shopping")
    data class UpsertShopping(val item: ShoppingItem) : OperationPayload

    @Serializable
    @SerialName("delete_shopping")
    data class DeleteShopping(val itemId: String) : OperationPayload

    @Serializable
    @SerialName("apply_inventory")
    data class ApplyInventory(val session: InventorySession) : OperationPayload

    @Serializable
    @SerialName("import_snapshot")
    data class ImportSnapshot(
        val snapshot: PantrySnapshot,
        val replaceExisting: Boolean,
    ) : OperationPayload

    @Serializable
    @SerialName("soft_delete")
    data class SoftDelete(val targetType: AggregateType, val id: String) : OperationPayload

    @Serializable
    @SerialName("restore")
    data class Restore(val targetType: AggregateType, val id: String) : OperationPayload
}

@Serializable
data class PantrySnapshot(
    val pantry: Pantry,
    val members: List<Member>,
    val shelves: List<Shelf>,
    val categories: List<Category>,
    val products: List<Product>,
    val stocks: List<Stock>,
    val shoppingItems: List<ShoppingItem>,
    val activities: List<Activity>,
)

@Serializable
data class ProductFilter(
    val query: String = "",
    val shelfIds: Set<ShelfId> = emptySet(),
    val categoryIds: Set<CategoryId> = emptySet(),
    val quantityAtMost: Int? = null,
    val belowMinimumOnly: Boolean = false,
    val onShoppingListOnly: Boolean = false,
)

@Serializable
data class UserSession(
    val uid: UserId,
    val displayName: String,
    val email: String,
    val photoUrl: String? = null,
)

@Serializable
data class SyncSummary(
    val pending: Int = 0,
    val syncing: Int = 0,
    val conflicts: Int = 0,
    val failed: Int = 0,
    val lastSuccessfulSyncAt: Long? = null,
) {
    val isFullySynced: Boolean get() = pending + syncing + conflicts + failed == 0
}

@Serializable
data class TrashItem(
    val type: AggregateType,
    val id: String,
    val pantryId: PantryId,
    val label: String,
    val deletedAt: Long,
    val purgeAfter: Long,
)
