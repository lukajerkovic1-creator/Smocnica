package hr.smocnica.core.data.local

import androidx.room.Entity
import androidx.room.Index
import hr.smocnica.core.model.OperationState
import hr.smocnica.core.model.SyncState

@Entity(tableName = "pantries", primaryKeys = ["id"])
data class PantryEntity(
    val id: String,
    val name: String,
    val ownerUid: String,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val purgeAfter: Long?,
    val syncState: SyncState,
)

@Entity(tableName = "members", primaryKeys = ["pantryId", "uid"], indices = [Index("pantryId")])
data class MemberEntity(
    val pantryId: String,
    val uid: String,
    val displayName: String,
    val photoUrl: String?,
    val role: String,
    val joinedAt: Long,
    val active: Boolean,
)

@Entity(
    tableName = "shelves",
    primaryKeys = ["id"],
    indices = [Index(value = ["pantryId", "sortOrder"]), Index("deletedAt")],
)
data class ShelfEntity(
    val id: String,
    val pantryId: String,
    val name: String,
    val sortOrder: Int,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val purgeAfter: Long?,
    val syncState: SyncState,
)

@Entity(
    tableName = "categories",
    primaryKeys = ["id"],
    indices = [Index(value = ["pantryId", "sortOrder"]), Index("deletedAt")],
)
data class CategoryEntity(
    val id: String,
    val pantryId: String,
    val name: String,
    val sortOrder: Int,
    val isDefault: Boolean,
    val revision: Long,
    val deletedAt: Long?,
    val purgeAfter: Long?,
    val syncState: SyncState,
)

@Entity(
    tableName = "products",
    primaryKeys = ["id"],
    indices = [Index("pantryId"), Index(value = ["pantryId", "barcode"], unique = true), Index("deletedAt")],
)
data class ProductEntity(
    val id: String,
    val pantryId: String,
    val name: String,
    val normalizedName: String,
    val barcode: String?,
    val description: String,
    val category: String,
    val photoUri: String?,
    val photoSource: String,
    val minimumQuantity: Int,
    val autoShopping: Boolean,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val purgeAfter: Long?,
    val syncState: SyncState,
)

@Entity(
    tableName = "stocks",
    primaryKeys = ["pantryId", "productId", "shelfId"],
    indices = [Index("productId"), Index("shelfId")],
)
data class StockEntity(
    val pantryId: String,
    val productId: String,
    val shelfId: String,
    val quantity: Int,
    val revision: Long,
    val updatedAt: Long,
    val syncState: SyncState,
)

@Entity(
    tableName = "shopping_items",
    primaryKeys = ["id"],
    indices = [Index("pantryId"), Index(value = ["pantryId", "productId"], unique = true), Index("deletedAt")],
)
data class ShoppingEntity(
    val id: String,
    val pantryId: String,
    val productId: String?,
    val name: String,
    val category: String,
    val requiredQuantity: Int,
    val checked: Boolean,
    val manual: Boolean,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val syncState: SyncState,
)

@Entity(tableName = "activities", primaryKeys = ["id"], indices = [Index(value = ["pantryId", "createdAt"])])
data class ActivityEntity(
    val id: String,
    val pantryId: String,
    val type: String,
    val aggregateId: String,
    val displayLabel: String,
    val quantityDelta: Int?,
    val actorUid: String,
    val deviceId: String,
    val deviceName: String,
    val oldValue: String?,
    val newValue: String?,
    val createdAt: Long,
)

@Entity(tableName = "inventory_sessions", primaryKeys = ["id"], indices = [Index("pantryId"), Index("shelfId")])
data class InventoryEntity(
    val id: String,
    val pantryId: String,
    val shelfId: String,
    val expectedRevision: Long,
    val status: String,
    val countsJson: String,
    val differencesJson: String,
    val createdBy: String,
    val deviceName: String,
    val createdAt: Long,
    val appliedAt: Long?,
)

@Entity(
    tableName = "pending_operations",
    primaryKeys = ["operationId"],
    indices = [Index(value = ["pantryId", "createdAt"]), Index("state")],
)
data class PendingOperationEntity(
    val operationId: String,
    val pantryId: String,
    val aggregateType: String,
    val aggregateId: String,
    val baseRevision: Long,
    val payloadJson: String,
    val actorUid: String,
    val deviceId: String,
    val deviceName: String,
    val createdAt: Long,
    val attempts: Int,
    val state: OperationState,
    val errorCode: String?,
)
