package hr.smocnica.core.data.repository

import androidx.room.withTransaction
import hr.smocnica.core.data.Clock
import hr.smocnica.core.data.DeviceIdentity
import hr.smocnica.core.data.IdGenerator
import hr.smocnica.core.data.local.ActivityEntity
import hr.smocnica.core.data.local.CategoryEntity
import hr.smocnica.core.data.local.PendingOperationEntity
import hr.smocnica.core.data.local.ShoppingEntity
import hr.smocnica.core.data.local.SmocnicaDatabase
import hr.smocnica.core.data.local.entity
import hr.smocnica.core.data.local.model
import hr.smocnica.core.data.local.searchKey
import hr.smocnica.core.domain.BarcodePolicy
import hr.smocnica.core.domain.InventoryPolicy
import hr.smocnica.core.domain.InventoryRepository
import hr.smocnica.core.domain.ShelfPolicy
import hr.smocnica.core.domain.StockPolicy
import hr.smocnica.core.model.Activity
import hr.smocnica.core.model.ActivityType
import hr.smocnica.core.model.AggregateType
import hr.smocnica.core.model.BulkStockMove
import hr.smocnica.core.model.Category
import hr.smocnica.core.model.InventoryCount
import hr.smocnica.core.model.InventorySession
import hr.smocnica.core.model.InventoryStatus
import hr.smocnica.core.model.OperationPayload
import hr.smocnica.core.model.OperationState
import hr.smocnica.core.model.Product
import hr.smocnica.core.model.ProductFilter
import hr.smocnica.core.model.ProductWithStock
import hr.smocnica.core.model.Shelf
import hr.smocnica.core.model.ShoppingItem
import hr.smocnica.core.model.Stock
import hr.smocnica.core.model.SyncState
import hr.smocnica.core.model.SyncSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalInventoryRepository @Inject constructor(
    private val database: SmocnicaDatabase,
    private val json: Json,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val deviceIdentity: DeviceIdentity,
) : InventoryRepository {
    private val shelves = database.shelfDao()
    private val categories = database.categoryDao()
    private val products = database.productDao()
    private val stocks = database.stockDao()
    private val shopping = database.shoppingDao()
    private val activities = database.activityDao()
    private val inventories = database.inventoryDao()
    private val operations = database.operationDao()

    override fun observeShelves(pantryId: String): Flow<List<Shelf>> =
        shelves.observeActive(pantryId).map { list -> list.map { it.model() } }

    override fun observeCategories(pantryId: String): Flow<List<Category>> =
        categories.observeActive(pantryId).map { list -> list.map { it.model() } }

    override fun observeProducts(pantryId: String, filter: ProductFilter): Flow<List<ProductWithStock>> =
        combine(
            products.observeActive(pantryId),
            stocks.observeForPantry(pantryId),
            shopping.observeActive(pantryId),
        ) { productRows, stockRows, shoppingRows ->
            val stockModels = stockRows.map { it.model() }
            val onShopping = shoppingRows.mapNotNull { it.productId }.toSet()
            val query = filter.query.searchKey()
            val quantityAtMost = filter.quantityAtMost
            productRows.map { row ->
                ProductWithStock(row.model(), stockModels.filter { it.productId == row.id })
            }.filter { item ->
                (query.isBlank() || item.product.name.searchKey().contains(query)) &&
                    (filter.shelfIds.isEmpty() || item.stocks.any { it.shelfId in filter.shelfIds && it.quantity > 0 }) &&
                    (filter.categoryIds.isEmpty() || item.product.categoryId in filter.categoryIds) &&
                    (quantityAtMost == null || item.totalQuantity <= quantityAtMost) &&
                    (!filter.belowMinimumOnly || item.isBelowMinimum) &&
                    (!filter.onShoppingListOnly || item.product.id in onShopping)
            }
        }

    override fun observeDeletedProducts(pantryId: String): Flow<List<ProductWithStock>> =
        combine(products.observeDeleted(pantryId), stocks.observeForPantry(pantryId)) { productRows, stockRows ->
            val stockModels = stockRows.map { it.model() }
            productRows.map { row -> ProductWithStock(row.model(), stockModels.filter { it.productId == row.id }) }
        }

    override fun observeShopping(pantryId: String): Flow<List<ShoppingItem>> =
        shopping.observeActive(pantryId).map { list -> list.map { it.model() } }

    override fun observeActivities(pantryId: String, since: Long): Flow<List<Activity>> =
        activities.observeSince(pantryId, since).map { list -> list.map { it.model() } }

    override fun observeInventoryDraft(pantryId: String): Flow<InventorySession?> =
        inventories.observeLatestDraft(pantryId).map { it?.model(json) }

    override fun observeSyncSummary(pantryId: String): Flow<SyncSummary> =
        operations.observeCounts(pantryId).map { counts ->
            val values = counts.associate { it.state to it.count }
            SyncSummary(
                pending = values[OperationState.PENDING] ?: 0,
                syncing = values[OperationState.IN_FLIGHT] ?: 0,
                conflicts = values[OperationState.CONFLICT] ?: 0,
                failed = values[OperationState.PERMANENT_FAILURE] ?: 0,
            )
        }

    override suspend fun createShelf(
        pantryId: String,
        name: String,
        actorUid: String,
        deviceName: String,
    ): Shelf = database.withTransaction {
        val cleanName = requireName(name, "Naziv police").canonicalName()
        requireUniqueShelfName(pantryId, cleanName)
        val now = clock.now()
        val shelf = Shelf(
            id = ids.next(),
            pantryId = pantryId,
            name = cleanName,
            sortOrder = shelves.nextSortOrder(pantryId),
            createdAt = now,
            updatedAt = now,
            syncState = SyncState.PENDING,
        )
        shelves.upsert(shelf.entity())
        val operationId = enqueue(shelf.pantryId, AggregateType.SHELF, shelf.id, shelf.revision, OperationPayload.CreateShelf(shelf), actorUid, deviceName, now)
        record(operationId, ActivityType.SHELF_CREATED, pantryId, shelf.id, shelf.name, actorUid, deviceName, now = now)
        shelf
    }

    override suspend fun renameShelf(shelf: Shelf, name: String, actorUid: String, deviceName: String) {
        database.withTransaction {
            val current = shelves.get(shelf.id) ?: error("Polica više ne postoji.")
            val cleanName = requireName(name, "Naziv police").canonicalName()
            requireUniqueShelfName(shelf.pantryId, cleanName, shelf.id)
            if (current.name == cleanName) return@withTransaction
            val now = clock.now()
            shelves.upsert(current.copy(name = cleanName, updatedAt = now, syncState = SyncState.PENDING))
            val operationId = enqueue(shelf.pantryId, AggregateType.SHELF, shelf.id, current.revision, OperationPayload.RenameShelf(shelf.id, cleanName), actorUid, deviceName, now)
            record(operationId, ActivityType.SHELF_RENAMED, shelf.pantryId, shelf.id, cleanName, actorUid, deviceName, old = current.name, new = cleanName, now = now)
        }
    }

    override suspend fun reorderShelves(
        pantryId: String,
        orderedIds: List<String>,
        baseRevision: Long,
        actorUid: String,
        deviceName: String,
    ) {
        require(orderedIds.size == orderedIds.distinct().size) { "Polica ne smije biti navedena dvaput." }
        database.withTransaction {
            val current = shelves.observeActive(pantryId)
            orderedIds.forEachIndexed { index, id ->
                val shelf = shelves.get(id) ?: error("Polica $id više ne postoji.")
                require(shelf.pantryId == pantryId && shelf.deletedAt == null) { "Neispravna polica u poretku." }
                shelves.upsert(shelf.copy(sortOrder = index, updatedAt = clock.now(), syncState = SyncState.PENDING))
            }
            val now = clock.now()
            val operationId = enqueue(pantryId, AggregateType.PANTRY, pantryId, baseRevision, OperationPayload.ReorderShelves(orderedIds), actorUid, deviceName, now)
            record(operationId, ActivityType.SHELF_REORDERED, pantryId, pantryId, "Promijenjen redoslijed polica", actorUid, deviceName, now = now)
        }
    }

    override suspend fun deleteShelf(shelf: Shelf, actorUid: String, deviceName: String) {
        database.withTransaction {
            val current = shelves.get(shelf.id) ?: error("Polica više ne postoji.")
            ShelfPolicy.requireCanDelete(stocks.quantitiesOnShelf(shelf.id))
            val now = clock.now()
            shelves.upsert(current.copy(deletedAt = now, purgeAfter = now + THIRTY_DAYS, updatedAt = now, syncState = SyncState.PENDING))
            val operationId = enqueue(shelf.pantryId, AggregateType.SHELF, shelf.id, current.revision, OperationPayload.DeleteShelf(shelf.id), actorUid, deviceName, now)
            record(operationId, ActivityType.ITEM_DELETED, shelf.pantryId, shelf.id, shelf.name, actorUid, deviceName, now = now)
        }
    }

    override suspend fun upsertCategory(category: Category, actorUid: String, deviceName: String): Category =
        database.withTransaction {
            val now = clock.now()
            val existing = category.id.takeIf(String::isNotBlank)?.let { categories.get(it) }
            val active = categories.listActive(category.pantryId)
            val cleanName = requireName(category.name, "Naziv kategorije").canonicalName()
            requireUniqueCategoryName(category.pantryId, cleanName, existing?.id)
            val persistedId = category.id.ifBlank { ids.next() }
            val defaultId = active.filter { it.isDefault }
                .sortedWith(compareBy<CategoryEntity> { it.sortOrder }.thenBy { it.id })
                .firstOrNull()?.id
                ?: active.firstOrNull { it.name.searchKey() == "Ostalo".searchKey() }?.id
                ?: active.sortedWith(compareBy<CategoryEntity> { it.sortOrder }.thenBy { it.id }).firstOrNull()?.id
                ?: persistedId
            val persisted = category.copy(
                id = persistedId,
                name = cleanName,
                sortOrder = if (category.id.isBlank()) categories.nextSortOrder(category.pantryId) else category.sortOrder,
                isDefault = persistedId == defaultId,
                syncState = SyncState.PENDING,
            )
            if (existing != null && existing.name != persisted.name) {
                categories.reassignProducts(category.pantryId, existing.id, persisted.id, persisted.name, now)
                shopping.reassignCategory(category.pantryId, existing.id, persisted.id, persisted.name, now)
            }
            active.filter { it.id != persisted.id && it.isDefault != (it.id == defaultId) }.forEach {
                categories.upsert(it.copy(isDefault = it.id == defaultId))
            }
            categories.upsert(persisted.entity())
            enqueue(persisted.pantryId, AggregateType.CATEGORY, persisted.id, category.revision, OperationPayload.UpsertCategory(persisted), actorUid, deviceName, now)
            persisted
        }

    override suspend fun reorderCategories(
        pantryId: String,
        orderedIds: List<String>,
        baseRevision: Long,
        actorUid: String,
        deviceName: String,
    ) {
        require(orderedIds.size == orderedIds.distinct().size) { "Kategorija ne smije biti navedena dvaput." }
        database.withTransaction {
            val active = categories.listActive(pantryId)
            require(active.map { it.id }.toSet() == orderedIds.toSet()) { "Poredak mora sadržavati sve aktivne kategorije." }
            val now = clock.now()
            orderedIds.forEachIndexed { index, id ->
                val category = categories.get(id) ?: error("Kategorija $id više ne postoji.")
                categories.upsert(category.copy(sortOrder = index, syncState = SyncState.PENDING))
            }
            enqueue(pantryId, AggregateType.PANTRY, pantryId, baseRevision, OperationPayload.ReorderCategories(orderedIds), actorUid, deviceName, now)
        }
    }

    override suspend fun deleteCategory(
        category: Category,
        replacementCategoryId: String,
        actorUid: String,
        deviceName: String,
    ) {
        require(!category.isDefault) { "Zadana kategorija ne može se obrisati." }
        database.withTransaction {
            val current = categories.get(category.id) ?: error("Kategorija više ne postoji.")
            val replacement = categories.get(replacementCategoryId) ?: error("Odaberite postojeću zamjensku kategoriju.")
            require(replacement.pantryId == category.pantryId && replacement.deletedAt == null) { "Zamjenska kategorija nije dostupna." }
            val now = clock.now()
            categories.reassignProducts(category.pantryId, current.id, replacement.id, replacement.name, now)
            shopping.reassignCategory(category.pantryId, current.id, replacement.id, replacement.name, now)
            categories.upsert(current.copy(deletedAt = now, purgeAfter = now + THIRTY_DAYS, syncState = SyncState.PENDING))
            enqueue(category.pantryId, AggregateType.CATEGORY, category.id, current.revision, OperationPayload.DeleteCategory(category.id, replacementCategoryId), actorUid, deviceName, now)
        }
    }

    override suspend fun upsertProduct(product: Product, actorUid: String, deviceName: String): Product =
        database.withTransaction {
            val cleanName = requireName(product.name, "Naziv artikla")
            require(product.minimumQuantity >= 0) { "Minimalna količina ne može biti negativna." }
            require(product.minimumQuantity <= MAX_QUANTITY) { "Minimalna količina je previsoka." }
            require(product.description.length <= 500) { "Opis artikla može imati najviše 500 znakova." }
            val barcode = product.barcode?.takeIf(String::isNotBlank)?.let(BarcodePolicy::requireSupported)
            val duplicate = barcode?.let { products.findAnyBarcode(product.pantryId, it) }
            require(duplicate == null || duplicate.id == product.id) { "Ovaj barkod već je povezan s drugim artiklom." }
            val activeCategories = categories.listActive(product.pantryId)
            val canonicalCategory = product.categoryId.takeIf(String::isNotBlank)
                ?.let { categoryId -> activeCategories.firstOrNull { it.id == categoryId } }
            require(canonicalCategory != null) { "Odaberite postojeću aktivnu kategoriju." }
            val now = clock.now()
            val existing = product.id.takeIf(String::isNotBlank)?.let { products.get(it) }
            val persisted = product.copy(
                id = product.id.ifBlank { ids.next() },
                name = cleanName,
                barcode = barcode,
                category = canonicalCategory.name,
                categoryId = canonicalCategory.id,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                syncState = SyncState.PENDING,
            )
            products.upsert(persisted.entity())
            reconcileAutomaticShopping(persisted, stocks.total(persisted.id), now)
            val operationId = enqueue(persisted.pantryId, AggregateType.PRODUCT, persisted.id, existing?.revision ?: 0, OperationPayload.UpsertProduct(persisted), actorUid, deviceName, now)
            record(operationId, if (existing == null) ActivityType.PRODUCT_CREATED else ActivityType.PRODUCT_UPDATED, persisted.pantryId, persisted.id, persisted.name, actorUid, deviceName, now = now)
            persisted
        }

    override suspend fun deleteProduct(product: Product, actorUid: String, deviceName: String) {
        database.withTransaction {
            val current = products.get(product.id) ?: error("Artikl više ne postoji.")
            val now = clock.now()
            products.upsert(current.copy(deletedAt = now, purgeAfter = now + THIRTY_DAYS, updatedAt = now, syncState = SyncState.PENDING))
            shopping.forProduct(product.pantryId, product.id)?.let {
                shopping.upsert(it.copy(deletedAt = now, updatedAt = now, syncState = SyncState.PENDING))
            }
            val operationId = enqueue(product.pantryId, AggregateType.PRODUCT, product.id, current.revision, OperationPayload.SoftDelete(AggregateType.PRODUCT, product.id), actorUid, deviceName, now)
            record(operationId, ActivityType.ITEM_DELETED, product.pantryId, product.id, product.name, actorUid, deviceName, now = now)
        }
    }

    override suspend fun adjustStock(
        productId: String,
        shelfId: String,
        delta: Int,
        actorUid: String,
        deviceName: String,
    ) {
        require(delta != 0) { "Promjena količine ne može biti nula." }
        require(delta in -MAX_QUANTITY..MAX_QUANTITY) { "Promjena količine je previsoka." }
        database.withTransaction {
            val product = products.get(productId) ?: error("Artikl više ne postoji.")
            val shelf = shelves.get(shelfId) ?: error("Polica više ne postoji.")
            require(product.pantryId == shelf.pantryId && product.deletedAt == null && shelf.deletedAt == null) { "Artikl i polica nisu dostupni." }
            val current = stocks.get(productId, shelfId)
            val onShelf = current?.quantity ?: 0
            val total = stocks.total(productId)
            val outcome = StockPolicy.adjust(onShelf, total, delta, product.minimumQuantity)
            val now = clock.now()
            stocks.upsert(
                (current ?: hr.smocnica.core.data.local.StockEntity(product.pantryId, productId, shelfId, 0, 0, now, SyncState.PENDING))
                    .copy(quantity = onShelf + delta, updatedAt = now, syncState = SyncState.PENDING),
            )
            reconcileAutomaticShopping(product.model(), outcome.newTotal, now)
            val operationId = enqueue(
                product.pantryId,
                AggregateType.STOCK,
                "${productId}_$shelfId",
                current?.revision ?: 0,
                OperationPayload.AdjustStock(productId, shelfId, delta, product.name, shelf.name),
                actorUid,
                deviceName,
                now,
            )
            record(
                operationId,
                if (delta > 0) ActivityType.STOCK_ADDED else ActivityType.STOCK_REMOVED,
                product.pantryId,
                productId,
                product.name,
                actorUid,
                deviceName,
                delta,
                old = shelf.name,
                new = shelf.name,
                productId = productId,
                shelfId = shelfId,
                now = now,
            )
        }
    }

    override suspend fun restoreProductAndAdjustStock(
        pantryId: String,
        productId: String,
        shelfId: String,
        quantity: Int,
        actorUid: String,
        deviceName: String,
    ) {
        require(quantity in 1..MAX_QUANTITY) { "Količina mora biti između 1 i $MAX_QUANTITY." }
        database.withTransaction {
            val product = products.get(productId) ?: error("Artikl nije pronađen u košu.")
            val shelf = shelves.get(shelfId) ?: error("Polica više ne postoji.")
            val now = clock.now()
            require(product.pantryId == pantryId && product.deletedAt != null) { "Artikl nije dostupan za vraćanje." }
            require(product.purgeAfter == null || product.purgeAfter > now) { "Rok za vraćanje artikla je istekao." }
            require(shelf.pantryId == pantryId && shelf.deletedAt == null) { "Odabrana polica nije dostupna." }
            product.barcode?.let { barcode ->
                val activeDuplicate = products.findBarcode(pantryId, barcode)
                require(activeDuplicate == null || activeDuplicate.id == productId) { "Ovaj barkod već pripada aktivnom artiklu." }
            }

            val restored = product.copy(deletedAt = null, purgeAfter = null, updatedAt = now, syncState = SyncState.PENDING)
            val current = stocks.get(productId, shelfId)
            val onShelf = current?.quantity ?: 0
            val total = stocks.total(productId)
            StockPolicy.adjust(onShelf, total, quantity, restored.minimumQuantity)
            products.upsert(restored)
            stocks.upsert(
                (current ?: hr.smocnica.core.data.local.StockEntity(pantryId, productId, shelfId, 0, 0, now, SyncState.PENDING))
                    .copy(quantity = onShelf + quantity, updatedAt = now, syncState = SyncState.PENDING),
            )
            reconcileAutomaticShopping(restored.model(), total + quantity, now)

            val restoreOperationId = enqueue(
                pantryId, AggregateType.PRODUCT, productId, 0,
                OperationPayload.Restore(AggregateType.PRODUCT, productId), actorUid, deviceName, now,
            )
            record(restoreOperationId, ActivityType.ITEM_RESTORED, pantryId, productId, restored.name, actorUid, deviceName, now = now)
            val stockOperationId = enqueue(
                pantryId, AggregateType.STOCK, "${productId}_$shelfId", current?.revision ?: 0,
                OperationPayload.AdjustStock(productId, shelfId, quantity, restored.name, shelf.name), actorUid, deviceName, now,
            )
            record(
                stockOperationId, ActivityType.STOCK_ADDED, pantryId, productId, restored.name,
                actorUid, deviceName, quantity, old = shelf.name, new = shelf.name,
                productId = productId, shelfId = shelfId, now = now,
            )
        }
    }

    override suspend fun moveStock(
        productId: String,
        fromShelfId: String,
        toShelfId: String,
        quantity: Int,
        actorUid: String,
        deviceName: String,
    ) {
        require(fromShelfId != toShelfId) { "Odaberite drugu odredišnu policu." }
        database.withTransaction {
            val product = products.get(productId) ?: error("Artikl više ne postoji.")
            val from = stocks.get(productId, fromShelfId) ?: error("Artikl nije na izvornoj polici.")
            val sourceShelf = shelves.get(fromShelfId) ?: error("Izvorna polica ne postoji.")
            val targetShelf = shelves.get(toShelfId) ?: error("Odredišna polica ne postoji.")
            require(sourceShelf.pantryId == product.pantryId && sourceShelf.deletedAt == null) { "Izvorna polica nije dostupna." }
            require(targetShelf.pantryId == product.pantryId && targetShelf.deletedAt == null) { "Odredišna polica nije dostupna." }
            StockPolicy.move(from.quantity, quantity)
            val now = clock.now()
            val to = stocks.get(productId, toShelfId)
            stocks.upsert(from.copy(quantity = from.quantity - quantity, updatedAt = now, syncState = SyncState.PENDING))
            stocks.upsert(
                (to ?: hr.smocnica.core.data.local.StockEntity(product.pantryId, productId, toShelfId, 0, 0, now, SyncState.PENDING))
                    .copy(quantity = (to?.quantity ?: 0) + quantity, updatedAt = now, syncState = SyncState.PENDING),
            )
            val operationId = enqueue(
                product.pantryId,
                AggregateType.STOCK,
                productId,
                maxOf(from.revision, to?.revision ?: 0),
                OperationPayload.MoveStock(productId, fromShelfId, toShelfId, quantity, product.name, sourceShelf.name, targetShelf.name),
                actorUid,
                deviceName,
                now,
            )
            record(
                operationId, ActivityType.STOCK_MOVED, product.pantryId, productId, product.name,
                actorUid, deviceName, quantity, old = sourceShelf.name, new = targetShelf.name,
                productId = productId, fromShelfId = fromShelfId, toShelfId = toShelfId, now = now,
            )
        }
    }

    override suspend fun changeProductsCategory(
        pantryId: String,
        productIds: List<String>,
        categoryId: String,
        actorUid: String,
        deviceName: String,
    ) {
        database.withTransaction {
            val selected = requireActiveBulkProducts(pantryId, productIds)
            val category = activeCategory(pantryId, categoryId)
            val now = clock.now()
            val operationId = enqueue(
                pantryId,
                AggregateType.PANTRY,
                pantryId,
                0,
                OperationPayload.BulkChangeProductCategory(selected.map { it.id }, category.id),
                actorUid,
                deviceName,
                now,
            )
            selected.forEachIndexed { index, current ->
                val updated = current.copy(
                    category = category.name,
                    categoryId = category.id,
                    updatedAt = now,
                    syncState = SyncState.PENDING,
                )
                products.upsert(updated)
                reconcileAutomaticShopping(updated.model(), stocks.total(updated.id), now)
                record(
                    "${operationId}_$index",
                    ActivityType.PRODUCT_UPDATED,
                    pantryId,
                    current.id,
                    current.name,
                    actorUid,
                    deviceName,
                    old = current.category,
                    new = category.name,
                    productId = current.id,
                    now = now,
                )
            }
        }
    }

    override suspend fun deleteProducts(
        pantryId: String,
        productIds: List<String>,
        actorUid: String,
        deviceName: String,
    ) {
        database.withTransaction {
            val selected = requireActiveBulkProducts(pantryId, productIds)
            val now = clock.now()
            val operationId = enqueue(
                pantryId,
                AggregateType.PANTRY,
                pantryId,
                0,
                OperationPayload.BulkDeleteProducts(selected.map { it.id }),
                actorUid,
                deviceName,
                now,
            )
            selected.forEachIndexed { index, current ->
                products.upsert(
                    current.copy(
                        deletedAt = now,
                        purgeAfter = now + THIRTY_DAYS,
                        updatedAt = now,
                        syncState = SyncState.PENDING,
                    ),
                )
                shopping.forProduct(pantryId, current.id)?.let { item ->
                    shopping.upsert(item.copy(deletedAt = now, updatedAt = now, syncState = SyncState.PENDING))
                }
                record(
                    "${operationId}_$index",
                    ActivityType.ITEM_DELETED,
                    pantryId,
                    current.id,
                    current.name,
                    actorUid,
                    deviceName,
                    productId = current.id,
                    now = now,
                )
            }
        }
    }

    override suspend fun moveProducts(
        pantryId: String,
        productIds: List<String>,
        fromShelfId: String,
        toShelfId: String,
        actorUid: String,
        deviceName: String,
    ) {
        require(fromShelfId != toShelfId) { "Odaberite različite police." }
        database.withTransaction {
            val selected = requireActiveBulkProducts(pantryId, productIds)
            val sourceShelf = shelves.get(fromShelfId)
                ?.takeIf { it.pantryId == pantryId && it.deletedAt == null }
                ?: error("Izvorna polica nije dostupna.")
            val targetShelf = shelves.get(toShelfId)
                ?.takeIf { it.pantryId == pantryId && it.deletedAt == null }
                ?: error("Odredišna polica nije dostupna.")
            val sources = selected.map { product ->
                stocks.get(product.id, fromShelfId)
                    ?.takeIf { it.quantity > 0 }
                    ?: error("Artikl ${product.name} nema zalihu na izvornoj polici.")
            }
            val targets = selected.map { product -> stocks.get(product.id, toShelfId) }
            sources.zip(targets).forEach { (source, target) ->
                StockPolicy.move(source.quantity, source.quantity)
                require((target?.quantity ?: 0).toLong() + source.quantity <= MAX_QUANTITY) {
                    "Količina na odredišnoj polici je previsoka."
                }
            }
            val moves = selected.indices.map { index ->
                BulkStockMove(selected[index].id, fromShelfId, toShelfId, sources[index].quantity)
            }
            val now = clock.now()
            val operationId = enqueue(
                pantryId,
                AggregateType.PANTRY,
                pantryId,
                0,
                OperationPayload.BulkMoveStock(moves),
                actorUid,
                deviceName,
                now,
            )
            selected.forEachIndexed { index, product ->
                val source = sources[index]
                val target = targets[index]
                stocks.upsert(source.copy(quantity = 0, updatedAt = now, syncState = SyncState.PENDING))
                stocks.upsert(
                    (target ?: hr.smocnica.core.data.local.StockEntity(pantryId, product.id, toShelfId, 0, 0, now, SyncState.PENDING))
                        .copy(quantity = (target?.quantity ?: 0) + source.quantity, updatedAt = now, syncState = SyncState.PENDING),
                )
                record(
                    "${operationId}_$index",
                    ActivityType.STOCK_MOVED,
                    pantryId,
                    product.id,
                    product.name,
                    actorUid,
                    deviceName,
                    quantity = source.quantity,
                    old = sourceShelf.name,
                    new = targetShelf.name,
                    productId = product.id,
                    fromShelfId = fromShelfId,
                    toShelfId = toShelfId,
                    now = now,
                )
            }
        }
    }

    override suspend fun addManualShoppingItem(
        pantryId: String,
        name: String,
        categoryId: String,
        quantity: Int,
        actorUid: String,
        deviceName: String,
        checked: Boolean,
    ): ShoppingItem = database.withTransaction {
        require(quantity > 0) { "Količina mora biti veća od nule." }
        require(quantity <= MAX_QUANTITY) { "Količina je previsoka." }
        val now = clock.now()
        val canonicalName = requireName(name, "Naziv stavke").replace(Regex("\\s+"), " ")
        val canonicalCategory = activeCategory(pantryId, categoryId)
        val duplicate = shopping.listActive(pantryId).firstOrNull {
            it.manual && manualShoppingIdentity(it.name, it.categoryId.orEmpty()) == manualShoppingIdentity(canonicalName, canonicalCategory.id)
        }
        if (duplicate != null) {
            val mergedQuantity = duplicate.requiredQuantity.toLong() + quantity
            require(mergedQuantity <= MAX_QUANTITY) { "Ukupna količina je previsoka." }
            val merged = duplicate.copy(
                name = duplicate.name,
                category = canonicalCategory.name,
                categoryId = canonicalCategory.id,
                requiredQuantity = mergedQuantity.toInt(),
                checked = false,
                updatedAt = now,
                syncState = SyncState.PENDING,
            )
            shopping.upsert(merged)
            enqueueOrReplaceShoppingUpsert(merged, actorUid, deviceName, now, quantityDelta = quantity)
            merged.model()
        } else {
            val item = ShoppingItem(
                manualShoppingId(pantryId, canonicalCategory.id, canonicalName), pantryId, null, canonicalName, canonicalCategory.name, quantity,
                checked = checked, manual = true, createdAt = now, updatedAt = now, syncState = SyncState.PENDING,
                categoryId = canonicalCategory.id,
            )
            val entity = item.entity()
            shopping.upsert(entity)
            enqueueOrReplaceShoppingUpsert(entity, actorUid, deviceName, now, quantityDelta = quantity)
            item
        }
    }

    override suspend fun updateManualShoppingItem(
        item: ShoppingItem,
        name: String,
        categoryId: String,
        quantity: Int,
        actorUid: String,
        deviceName: String,
    ) {
        require(item.manual) { "Samo ručna stavka može se uređivati." }
        require(quantity in 1..MAX_QUANTITY) { "Količina mora biti između 1 i $MAX_QUANTITY." }
        database.withTransaction {
            val current = shopping.get(item.id) ?: error("Stavka više ne postoji.")
            require(current.manual && current.deletedAt == null) { "Stavka nije dostupna za uređivanje." }
            val now = clock.now()
            val canonicalName = requireName(name, "Naziv stavke").replace(Regex("\\s+"), " ")
            val canonicalCategory = activeCategory(item.pantryId, categoryId)
            val duplicate = shopping.listActive(item.pantryId).firstOrNull {
                it.manual && it.id != item.id &&
                    manualShoppingIdentity(it.name, it.categoryId.orEmpty()) == manualShoppingIdentity(canonicalName, canonicalCategory.id)
            }
            if (duplicate != null) {
                val mergedQuantity = duplicate.requiredQuantity.toLong() + quantity
                require(mergedQuantity <= MAX_QUANTITY) { "Ukupna količina je previsoka." }
                val merged = duplicate.copy(
                    name = duplicate.name,
                    category = canonicalCategory.name,
                    categoryId = canonicalCategory.id,
                    requiredQuantity = mergedQuantity.toInt(),
                    checked = false,
                    updatedAt = now,
                    syncState = SyncState.PENDING,
                )
                shopping.upsert(merged)
                enqueueOrReplaceShoppingUpsert(merged, actorUid, deviceName, now)
                stageManualShoppingDeletion(current, actorUid, deviceName, now)
                return@withTransaction
            }
            val updated = current.copy(
                name = canonicalName,
                category = canonicalCategory.name,
                categoryId = canonicalCategory.id,
                requiredQuantity = quantity,
                updatedAt = now,
                syncState = SyncState.PENDING,
            )
            shopping.upsert(updated)
            enqueueOrReplaceShoppingUpsert(updated, actorUid, deviceName, now)
        }
    }

    override suspend fun deleteManualShoppingItem(
        item: ShoppingItem,
        actorUid: String,
        deviceName: String,
    ): ShoppingItem = database.withTransaction {
        val current = shopping.get(item.id) ?: error("Stavka više ne postoji.")
        require(current.manual && current.deletedAt == null) { "Samo aktivna ručna stavka može se obrisati." }
        stageManualShoppingDeletion(current, actorUid, deviceName, clock.now())
        current.model()
    }

    override suspend fun setShoppingChecked(item: ShoppingItem, checked: Boolean, actorUid: String, deviceName: String) {
        database.withTransaction {
            val current = shopping.get(item.id) ?: error("Stavka više ne postoji.")
            val now = clock.now()
            val updated = current.copy(checked = checked, updatedAt = now, syncState = SyncState.PENDING)
            shopping.upsert(updated)
            enqueueOrReplaceShoppingUpsert(updated, actorUid, deviceName, now)
        }
    }

    override suspend fun previewInventory(
        pantryId: String,
        shelfId: String,
        counts: Map<String, Int>,
        actorUid: String,
        deviceName: String,
    ): InventorySession = database.withTransaction {
        require(counts.values.all { it >= 0 }) { "Inventurna količina ne može biti negativna." }
        val productModels = products.listActive(pantryId).map { product ->
            ProductWithStock(product.model(), stocks.listForPantry(pantryId).filter { it.productId == product.id }.map { it.model() })
        }
        val inventoryCounts = counts.map { InventoryCount(it.key, it.value) }
        val existing = stocks.forShelf(shelfId)
        val previousDraft = inventories.latestDraft(pantryId)
        if (previousDraft != null && previousDraft.shelfId != shelfId) inventories.deleteDraft(previousDraft.id)
        val session = InventorySession(
            id = previousDraft?.takeIf { it.shelfId == shelfId }?.id ?: ids.next(),
            pantryId = pantryId,
            shelfId = shelfId,
            expectedRevision = InventoryPolicy.snapshotVersion(existing.map { it.model() }),
            status = InventoryStatus.DRAFT,
            counts = inventoryCounts,
            differences = InventoryPolicy.differences(productModels, inventoryCounts, shelfId),
            createdBy = actorUid,
            deviceName = deviceName,
            createdAt = clock.now(),
        )
        inventories.upsert(session.entity(json))
        session
    }

    override suspend fun discardInventoryDraft(id: String) {
        inventories.deleteDraft(id)
    }

    override suspend fun applyInventory(session: InventorySession, actorUid: String, deviceName: String) {
        require(session.status == InventoryStatus.DRAFT) { "Samo nacrt inventure može se primijeniti." }
        database.withTransaction {
            val persisted = inventories.get(session.id)?.model(json) ?: error("Nacrt inventure više ne postoji.")
            require(persisted.status == InventoryStatus.DRAFT) { "Inventura je već zaključena." }
            val currentRevision = InventoryPolicy.snapshotVersion(stocks.forShelf(session.shelfId).map { it.model() })
            require(currentRevision == session.expectedRevision) { "Stanje police promijenjeno je tijekom inventure. Ponovno pregledajte razlike." }
            val now = clock.now()
            session.differences.forEach { difference ->
                val product = products.get(difference.productId) ?: error("Artikl ${difference.productName} više ne postoji.")
                val stock = stocks.get(difference.productId, session.shelfId)
                stocks.upsert(
                    (stock ?: hr.smocnica.core.data.local.StockEntity(session.pantryId, difference.productId, session.shelfId, 0, 0, now, SyncState.PENDING))
                        .copy(quantity = difference.actualQuantity, updatedAt = now, syncState = SyncState.PENDING),
                )
                val totalOutside = stocks.listForPantry(session.pantryId).filter { it.productId == difference.productId && it.shelfId != session.shelfId }.sumOf { it.quantity }
                reconcileAutomaticShopping(product.model(), totalOutside + difference.actualQuantity, now)
            }
            val applied = session.copy(status = InventoryStatus.APPLIED, appliedAt = now)
            inventories.upsert(applied.entity(json))
            val operationId = enqueue(session.pantryId, AggregateType.INVENTORY, session.id, session.expectedRevision, OperationPayload.ApplyInventory(applied), actorUid, deviceName, now)
            record(operationId, ActivityType.INVENTORY_APPLIED, session.pantryId, session.id, "Inventura police", actorUid, deviceName, now = now)
        }
    }

    override suspend fun restore(pantryId: String, type: String, id: String, actorUid: String, deviceName: String) {
        val aggregate = AggregateType.valueOf(type.uppercase(Locale.ROOT))
        database.withTransaction {
            val now = clock.now()
            when (aggregate) {
                AggregateType.PRODUCT -> {
                    val row = products.get(id) ?: error("Artikl nije pronađen u košu.")
                    require(row.purgeAfter == null || row.purgeAfter > now) { "Rok za vraćanje artikla je istekao." }
                    val restored = row.copy(deletedAt = null, purgeAfter = null, updatedAt = now, syncState = SyncState.PENDING)
                    products.upsert(restored)
                    reconcileAutomaticShopping(restored.model(), stocks.total(id), now)
                }
                AggregateType.SHELF -> {
                    val row = shelves.get(id) ?: error("Polica nije pronađena u košu.")
                    require(row.purgeAfter == null || row.purgeAfter > now) { "Rok za vraćanje police je istekao." }
                    requireUniqueShelfName(pantryId, row.name, row.id)
                    shelves.upsert(row.copy(deletedAt = null, purgeAfter = null, updatedAt = now, syncState = SyncState.PENDING))
                }
                AggregateType.CATEGORY -> {
                    val row = categories.get(id) ?: error("Kategorija nije pronađena u košu.")
                    require(row.purgeAfter == null || row.purgeAfter > now) { "Rok za vraćanje kategorije je istekao." }
                    requireUniqueCategoryName(pantryId, row.name, row.id)
                    categories.upsert(row.copy(deletedAt = null, purgeAfter = null, isDefault = false, syncState = SyncState.PENDING))
                }
                else -> error("Ova vrsta zapisa ne može se vratiti iz koša.")
            }
            val operationId = enqueue(pantryId, aggregate, id, 0, OperationPayload.Restore(aggregate, id), actorUid, deviceName, now)
            record(operationId, ActivityType.ITEM_RESTORED, pantryId, id, "Vraćen zapis", actorUid, deviceName, now = now)
        }
    }

    private suspend fun reconcileAutomaticShopping(product: Product, total: Int, now: Long) {
        val current = shopping.forProduct(product.pantryId, product.id)
        val automaticId = "auto_${product.id}"
        if (current != null && !current.manual && current.id != automaticId) shopping.deleteHard(current.id)
        val aligned = current?.takeIf { it.id == automaticId }
        val required = if (product.autoShopping) (product.minimumQuantity - total).coerceAtLeast(0) else 0
        when {
            required == 0 && aligned != null -> shopping.upsert(aligned.copy(deletedAt = now, requiredQuantity = 0, updatedAt = now, syncState = SyncState.PENDING))
            required > 0 && aligned == null -> shopping.upsert(
                ShoppingEntity(
                    id = automaticId, pantryId = product.pantryId, productId = product.id,
                    name = product.name, category = product.category, requiredQuantity = required,
                    checked = false, manual = false, revision = 0, createdAt = now, updatedAt = now,
                    deletedAt = null, syncState = SyncState.PENDING, categoryId = product.categoryId,
                ),
            )
            required > 0 && aligned != null -> shopping.upsert(
                aligned.copy(
                    name = product.name,
                    category = product.category,
                    categoryId = product.categoryId,
                    requiredQuantity = required,
                    checked = if (aligned.deletedAt != null || required > aligned.requiredQuantity) false else aligned.checked,
                    deletedAt = null,
                    updatedAt = now,
                    syncState = SyncState.PENDING,
                ),
            )
        }
    }

    private suspend fun activeCategory(pantryId: String, categoryId: String): CategoryEntity {
        require(categoryId.isNotBlank()) { "Odaberite kategoriju." }
        return categories.get(categoryId)
            ?.takeIf { it.pantryId == pantryId && it.deletedAt == null }
            ?: error("Odabrana kategorija više nije dostupna.")
    }

    private fun manualShoppingIdentity(name: String, categoryId: String): String {
        return "${name.searchKey()}\u0000$categoryId"
    }

    private fun manualShoppingId(pantryId: String, categoryId: String, name: String): String {
        val identity = "$pantryId\u0000$categoryId\u0000${name.searchKey()}"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "manual_$hash"
    }

    private suspend fun enqueueOrReplaceShoppingUpsert(
        item: ShoppingEntity,
        actorUid: String,
        deviceName: String,
        now: Long,
        quantityDelta: Int? = null,
    ) {
        val pending = operations.pendingForAggregate(item.pantryId, AggregateType.SHOPPING.name, item.id)
        val accumulatedDelta = if (quantityDelta != null && pending != null) {
            val previous = runCatching {
                json.decodeFromString(OperationPayload.serializer(), pending.payloadJson) as? OperationPayload.UpsertShopping
            }.getOrNull()
            previous?.quantityDelta?.let { Math.addExact(it, quantityDelta) }
        } else {
            quantityDelta
        }
        val payload = OperationPayload.UpsertShopping(item.model(), accumulatedDelta)
        if (pending != null) {
            operations.replacePendingPayload(
                pending.operationId,
                json.encodeToString(OperationPayload.serializer(), payload),
            )
        } else {
            enqueue(item.pantryId, AggregateType.SHOPPING, item.id, item.revision, payload, actorUid, deviceName, now)
        }
    }

    private suspend fun stageManualShoppingDeletion(
        item: ShoppingEntity,
        actorUid: String,
        deviceName: String,
        now: Long,
    ) {
        val pending = operations.pendingForAggregate(item.pantryId, AggregateType.SHOPPING.name, item.id)
        if (item.revision == 0L && pending != null) {
            operations.delete(pending.operationId)
            shopping.deleteHard(item.id)
            return
        }
        shopping.upsert(item.copy(deletedAt = now, updatedAt = now, syncState = SyncState.PENDING))
        val payload = OperationPayload.DeleteShopping(item.id)
        if (pending != null) {
            operations.replacePendingPayload(
                pending.operationId,
                json.encodeToString(OperationPayload.serializer(), payload),
            )
        } else {
            enqueue(item.pantryId, AggregateType.SHOPPING, item.id, item.revision, payload, actorUid, deviceName, now)
        }
    }

    private suspend fun enqueue(
        pantryId: String,
        aggregateType: AggregateType,
        aggregateId: String,
        baseRevision: Long,
        payload: OperationPayload,
        actorUid: String,
        deviceName: String,
        now: Long,
    ): String {
        val operationId = ids.next()
        operations.insert(
            PendingOperationEntity(
                operationId = operationId,
                pantryId = pantryId,
                aggregateType = aggregateType.name,
                aggregateId = aggregateId,
                baseRevision = baseRevision,
                payloadJson = json.encodeToString(OperationPayload.serializer(), payload),
                actorUid = actorUid,
                deviceId = deviceIdentity.deviceId,
                deviceName = deviceName,
                createdAt = now,
                attempts = 0,
                state = OperationState.PENDING,
                errorCode = null,
            ),
        )
        return operationId
    }

    private suspend fun record(
        id: String,
        type: ActivityType,
        pantryId: String,
        aggregateId: String,
        label: String,
        actorUid: String,
        deviceName: String,
        quantity: Int? = null,
        old: String? = null,
        new: String? = null,
        productId: String? = null,
        shelfId: String? = null,
        fromShelfId: String? = null,
        toShelfId: String? = null,
        now: Long,
    ) {
        activities.insert(
            ActivityEntity(
                id = id, pantryId = pantryId, type = type.name,
                aggregateId = aggregateId, displayLabel = label, quantityDelta = quantity,
                actorUid = actorUid, deviceId = deviceIdentity.deviceId, deviceName = deviceName,
                oldValue = old, newValue = new, createdAt = now,
                productId = productId, shelfId = shelfId,
                fromShelfId = fromShelfId, toShelfId = toShelfId,
            ),
        )
    }

    private fun requireName(value: String, label: String): String = value.trim().also {
        require(it.length in 1..100) { "$label mora imati između 1 i 100 znakova." }
    }

    private fun String.canonicalName(): String = trim().replace(Regex("\\s+"), " ")

    private suspend fun requireUniqueShelfName(pantryId: String, name: String, ownId: String? = null) {
        val normalized = name.searchKey()
        require(shelves.listActive(pantryId).none { it.id != ownId && it.name.searchKey() == normalized }) {
            "Aktivna polica s tim nazivom već postoji."
        }
    }

    private suspend fun requireUniqueCategoryName(pantryId: String, name: String, ownId: String? = null) {
        val normalized = name.searchKey()
        require(categories.listActive(pantryId).none { it.id != ownId && it.name.searchKey() == normalized }) {
            "Aktivna kategorija s tim nazivom već postoji."
        }
    }

    private suspend fun requireActiveBulkProducts(
        pantryId: String,
        requestedIds: List<String>,
    ): List<hr.smocnica.core.data.local.ProductEntity> {
        require(requestedIds.isNotEmpty()) { "Odaberite barem jedan artikl." }
        require(requestedIds.size <= MAX_BULK_PRODUCTS) { "Odjednom je moguće promijeniti najviše $MAX_BULK_PRODUCTS artikala." }
        require(requestedIds.distinct().size == requestedIds.size) { "Isti artikl ne smije biti odabran više puta." }
        return requestedIds.map { productId ->
            products.get(productId)
                ?.takeIf { it.pantryId == pantryId && it.deletedAt == null }
                ?: error("Jedan od odabranih artikala više nije dostupan.")
        }
    }

    private companion object {
        const val THIRTY_DAYS = 30L * 24 * 60 * 60 * 1_000
        const val MAX_QUANTITY = 1_000_000
        const val MAX_BULK_PRODUCTS = 100
    }
}
