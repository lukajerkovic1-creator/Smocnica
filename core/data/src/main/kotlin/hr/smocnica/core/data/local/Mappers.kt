package hr.smocnica.core.data.local

import hr.smocnica.core.model.Activity
import hr.smocnica.core.model.ActivityType
import hr.smocnica.core.model.Category
import hr.smocnica.core.model.InventoryCount
import hr.smocnica.core.model.InventoryDifference
import hr.smocnica.core.model.InventorySession
import hr.smocnica.core.model.InventoryStatus
import hr.smocnica.core.model.Member
import hr.smocnica.core.model.MemberRole
import hr.smocnica.core.model.Pantry
import hr.smocnica.core.model.PhotoSource
import hr.smocnica.core.model.Product
import hr.smocnica.core.model.Shelf
import hr.smocnica.core.model.ShoppingItem
import hr.smocnica.core.model.Stock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.Normalizer

internal fun String.searchKey(): String = Normalizer.normalize(trim().lowercase(), Normalizer.Form.NFD)
    .replace("\\p{Mn}+".toRegex(), "")

internal fun PantryEntity.model() = Pantry(id, name, ownerUid, revision, createdAt, updatedAt, deletedAt, purgeAfter, syncState)
internal fun Pantry.entity() = PantryEntity(id, name, ownerUid, revision, createdAt, updatedAt, deletedAt, purgeAfter, syncState)

internal fun MemberEntity.model() = Member(pantryId, uid, displayName, photoUrl, MemberRole.valueOf(role), joinedAt, active)
internal fun Member.entity() = MemberEntity(pantryId, uid, displayName, photoUrl, role.name, joinedAt, active)

internal fun ShelfEntity.model() = Shelf(id, pantryId, name, sortOrder, revision, createdAt, updatedAt, deletedAt, purgeAfter, syncState)
internal fun Shelf.entity() = ShelfEntity(id, pantryId, name, sortOrder, revision, createdAt, updatedAt, deletedAt, purgeAfter, syncState)

internal fun CategoryEntity.model() = Category(id, pantryId, name, sortOrder, isDefault, revision, deletedAt, purgeAfter, syncState)
internal fun Category.entity() = CategoryEntity(id, pantryId, name, sortOrder, isDefault, revision, deletedAt, purgeAfter, syncState)

internal fun ProductEntity.model() = Product(
    id = id,
    pantryId = pantryId,
    name = name,
    barcode = barcode,
    description = description,
    category = category,
    photoUri = photoUri,
    photoSource = PhotoSource.valueOf(photoSource),
    minimumQuantity = minimumQuantity,
    autoShopping = autoShopping,
    revision = revision,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    purgeAfter = purgeAfter,
    syncState = syncState,
)

internal fun Product.entity() = ProductEntity(
    id = id,
    pantryId = pantryId,
    name = name,
    normalizedName = name.searchKey(),
    barcode = barcode,
    description = description,
    category = category,
    photoUri = photoUri,
    photoSource = photoSource.name,
    minimumQuantity = minimumQuantity,
    autoShopping = autoShopping,
    revision = revision,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    purgeAfter = purgeAfter,
    syncState = syncState,
)

internal fun StockEntity.model() = Stock(pantryId, productId, shelfId, quantity, revision, updatedAt, syncState)
internal fun Stock.entity() = StockEntity(pantryId, productId, shelfId, quantity, revision, updatedAt, syncState)

internal fun ShoppingEntity.model() = ShoppingItem(
    id, pantryId, productId, name, category, requiredQuantity, checked, manual,
    revision, createdAt, updatedAt, deletedAt, syncState,
)
internal fun ShoppingItem.entity() = ShoppingEntity(
    id, pantryId, productId, name, category, requiredQuantity, checked, manual,
    revision, createdAt, updatedAt, deletedAt, syncState,
)

internal fun ActivityEntity.model() = Activity(
    id = id,
    pantryId = pantryId,
    type = runCatching { ActivityType.valueOf(type) }.getOrDefault(ActivityType.UNKNOWN),
    aggregateId = aggregateId,
    displayLabel = displayLabel,
    quantityDelta = quantityDelta,
    actorUid = actorUid,
    deviceId = deviceId,
    deviceName = deviceName,
    oldValue = oldValue,
    newValue = newValue,
    createdAt = createdAt,
)

internal fun Activity.entity() = ActivityEntity(
    id, pantryId, type.name, aggregateId, displayLabel, quantityDelta, actorUid,
    deviceId, deviceName, oldValue, newValue, createdAt,
)

internal fun InventorySession.entity(json: Json) = InventoryEntity(
    id = id,
    pantryId = pantryId,
    shelfId = shelfId,
    expectedRevision = expectedRevision,
    status = status.name,
    countsJson = json.encodeToString(ListSerializer(InventoryCount.serializer()), counts),
    differencesJson = json.encodeToString(ListSerializer(InventoryDifference.serializer()), differences),
    createdBy = createdBy,
    deviceName = deviceName,
    createdAt = createdAt,
    appliedAt = appliedAt,
)

internal fun InventoryEntity.model(json: Json) = InventorySession(
    id = id,
    pantryId = pantryId,
    shelfId = shelfId,
    expectedRevision = expectedRevision,
    status = InventoryStatus.valueOf(status),
    counts = json.decodeFromString(ListSerializer(InventoryCount.serializer()), countsJson),
    differences = json.decodeFromString(ListSerializer(InventoryDifference.serializer()), differencesJson),
    createdBy = createdBy,
    deviceName = deviceName,
    createdAt = createdAt,
    appliedAt = appliedAt,
)
