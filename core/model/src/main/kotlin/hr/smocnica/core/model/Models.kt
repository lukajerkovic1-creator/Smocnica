package hr.smocnica.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

typealias PantryId = String
typealias ProductId = String
typealias VariantId = String
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
    val contentSchemaVersion: Int = 2,
    val groupingReviewCompletedAt: Long? = null,
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
    val minimumMode: MinimumMode = MinimumMode.PACKAGES,
    val minimumAmountBase: Long = minimumQuantity.toLong(),
    val preferredVariantId: VariantId? = null,
    val doNotGroup: Boolean = false,
    val groupingRevision: Long = 0,
)

@Serializable
enum class PhotoSource { NONE, OPEN_FOOD_FACTS, CAMERA, GALLERY }

@Serializable
enum class MeasurementKind { MASS, VOLUME, COUNT, UNKNOWN }

@Serializable
enum class PackageUnit(val kind: MeasurementKind, val multiplierToBase: Long) {
    MG(MeasurementKind.MASS, 1),
    G(MeasurementKind.MASS, 1_000),
    KG(MeasurementKind.MASS, 1_000_000),
    ML(MeasurementKind.VOLUME, 1),
    L(MeasurementKind.VOLUME, 1_000),
    PIECE(MeasurementKind.COUNT, 1),
    ROLL(MeasurementKind.COUNT, 1),
    BAG(MeasurementKind.COUNT, 1),
    CAPSULE(MeasurementKind.COUNT, 1),
    UNKNOWN(MeasurementKind.UNKNOWN, 0),
}

@Serializable
enum class MinimumMode { PACKAGES, MASS_MG, VOLUME_ML, COUNT }

@Serializable
data class ProductVariant(
    val id: VariantId,
    val pantryId: PantryId,
    val productId: ProductId,
    val displayName: String,
    val manufacturer: String = "",
    val barcode: String? = null,
    /** Amount of one package expressed in integer base units: mg, ml or count. */
    val packageAmountBase: Long? = null,
    val packageUnit: PackageUnit = PackageUnit.UNKNOWN,
    val packageLabel: String = "",
    val description: String = "",
    val photoUri: String? = null,
    val photoSource: PhotoSource = PhotoSource.NONE,
    val minimumPackages: Int? = null,
    val purchaseCount: Long = 0,
    val revision: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val purgeAfter: Long? = null,
    val syncState: SyncState = SyncState.SYNCED,
)

@Serializable
data class SynonymRule(
    val id: String,
    val pantryId: PantryId,
    val sourceNormalized: String,
    val genericName: String,
    val genericNameNormalized: String,
    val productId: ProductId? = null,
    val ownerConfirmed: Boolean = false,
    val revision: Long = 0,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncState: SyncState = SyncState.SYNCED,
)

@Serializable
data class GroupingSuggestion(
    val sourceName: String,
    val suggestedGenericName: String,
    val existingProductId: ProductId? = null,
    val confidencePercent: Int,
    val matchedRuleId: String? = null,
)

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
    val variantId: VariantId = productId,
)

@Serializable
data class ProductWithStock(
    val product: Product,
    val stocks: List<Stock>,
    val variants: List<ProductVariant> = emptyList(),
    /** Variant that matched the active search query; never persisted. */
    val matchedVariantId: VariantId? = null,
) {
    val totalQuantity: Int get() = stocks.sumOf(Stock::quantity)
    val shortfall: Int get() = if (product.minimumMode == MinimumMode.PACKAGES) {
        (product.minimumAmountBase.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() - totalQuantity).coerceAtLeast(0)
    } else 0
    val isBelowMinimum: Boolean get() = minimumCurrentBase < product.minimumAmountBase
    val hasUnknownPackageSize: Boolean get() = variants.any { variant ->
        stocks.any { it.variantId == variant.id && it.quantity > 0 } && variant.packageAmountBase == null
    }
    val measurementKind: MeasurementKind get() = when (product.minimumMode) {
        MinimumMode.MASS_MG -> MeasurementKind.MASS
        MinimumMode.VOLUME_ML -> MeasurementKind.VOLUME
        MinimumMode.COUNT -> MeasurementKind.COUNT
        MinimumMode.PACKAGES -> variants.map { it.packageUnit.kind }.filter { it != MeasurementKind.UNKNOWN }.distinct().singleOrNull()
            ?: MeasurementKind.UNKNOWN
    }
    val knownAmountBase: Long get() = stocks.fold(0L) { total, stock ->
        val amount = variants.firstOrNull { it.id == stock.variantId }?.packageAmountBase ?: 0L
        val row = if (amount <= 0L || stock.quantity <= 0) 0L else {
            runCatching { Math.multiplyExact(amount, stock.quantity.toLong()) }.getOrDefault(Long.MAX_VALUE)
        }
        runCatching { Math.addExact(total, row) }.getOrDefault(Long.MAX_VALUE)
    }
    val minimumCurrentBase: Long get() = when (product.minimumMode) {
        MinimumMode.PACKAGES -> totalQuantity.toLong()
        MinimumMode.MASS_MG -> if (measurementKind == MeasurementKind.MASS) knownAmountBase else 0
        MinimumMode.VOLUME_ML -> if (measurementKind == MeasurementKind.VOLUME) knownAmountBase else 0
        MinimumMode.COUNT -> if (measurementKind == MeasurementKind.COUNT) knownAmountBase else 0
    }
    val representativeVariant: ProductVariant? get() {
        matchedVariantId?.let { id -> variants.firstOrNull { it.id == id }?.let { return it } }
        val totals = stocks.groupBy(Stock::variantId).mapValues { (_, rows) -> rows.sumOf(Stock::quantity) }
        return variants.maxWithOrNull(compareBy<ProductVariant> { totals[it.id] ?: 0 }.thenBy { it.purchaseCount }.thenBy { it.id })
    }
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
    val preferredVariantId: VariantId? = null,
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
    VARIANT_CREATED,
    VARIANT_UPDATED,
    VARIANT_GROUPED,
    VARIANT_UNGROUPED,
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
    val variantId: VariantId = productId,
)

@Serializable
data class InventoryDifference(
    val productId: ProductId,
    val productName: String,
    val expectedQuantity: Int,
    val actualQuantity: Int,
    val type: InventoryDifferenceType,
    val variantId: VariantId = productId,
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
enum class AggregateType { PANTRY, SHELF, CATEGORY, PRODUCT, VARIANT, STOCK, SHOPPING, INVENTORY, MEMBER, DICTIONARY }

@Serializable
enum class OperationState { PENDING, IN_FLIGHT, CONFLICT, PERMANENT_FAILURE }

@Serializable
data class BulkStockMove(
    val productId: ProductId,
    val fromShelfId: ShelfId,
    val toShelfId: ShelfId,
    val quantity: Int,
    val variantId: VariantId = productId,
)

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
    data class UpsertProduct(
        val product: Product,
        val initialVariant: ProductVariant? = null,
    ) : OperationPayload

    @Serializable
    @SerialName("upsert_variant")
    data class UpsertVariant(val variant: ProductVariant) : OperationPayload

    @Serializable
    @SerialName("move_variant")
    data class MoveVariant(
        val variantId: VariantId,
        val fromProductId: ProductId,
        val toProductId: ProductId,
        val expectedGroupingRevision: Long,
    ) : OperationPayload

    @Serializable
    @SerialName("split_variant")
    data class SplitVariant(
        val variantId: VariantId,
        val fromProductId: ProductId,
        val newProduct: Product,
        val expectedGroupingRevision: Long,
    ) : OperationPayload

    @Serializable
    @SerialName("upsert_synonym_rule")
    data class UpsertSynonymRule(val rule: SynonymRule) : OperationPayload

    @Serializable
    @SerialName("set_do_not_group")
    data class SetDoNotGroup(
        val productId: ProductId,
        val doNotGroup: Boolean,
        val expectedGroupingRevision: Long,
    ) : OperationPayload

    @Serializable
    @SerialName("adjust_stock")
    data class AdjustStock(
        val productId: ProductId,
        val shelfId: ShelfId,
        val delta: Int,
        val variantId: VariantId = productId,
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
        val variantId: VariantId = productId,
        val productName: String = "",
        val fromShelfName: String = "",
        val toShelfName: String = "",
    ) : OperationPayload

    @Serializable
    @SerialName("upsert_shopping")
    data class UpsertShopping(
        val item: ShoppingItem,
        /** Positive, idempotent addition. Null means an absolute edit/check-state update. */
        val quantityDelta: Int? = null,
    ) : OperationPayload

    @Serializable
    @SerialName("bulk_change_product_category")
    data class BulkChangeProductCategory(
        val productIds: List<ProductId>,
        val categoryId: CategoryId,
    ) : OperationPayload

    @Serializable
    @SerialName("bulk_delete_products")
    data class BulkDeleteProducts(val productIds: List<ProductId>) : OperationPayload

    @Serializable
    @SerialName("bulk_move_stock")
    data class BulkMoveStock(val moves: List<BulkStockMove>) : OperationPayload

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
    val variants: List<ProductVariant> = emptyList(),
    val synonymRules: List<SynonymRule> = emptyList(),
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
