package hr.smocnica.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import hr.smocnica.core.data.Clock
import hr.smocnica.core.data.DeviceIdentity
import hr.smocnica.core.data.IdGenerator
import hr.smocnica.core.data.PrivacySafeCrashReporter
import hr.smocnica.core.data.local.PantryEntity
import hr.smocnica.core.data.local.ShelfEntity
import hr.smocnica.core.data.local.CategoryEntity
import hr.smocnica.core.data.local.ShoppingEntity
import hr.smocnica.core.data.local.SmocnicaDatabase
import hr.smocnica.core.data.local.model
import hr.smocnica.core.domain.ImportPreview
import hr.smocnica.core.domain.ImportStrategy
import hr.smocnica.core.model.Pantry
import hr.smocnica.core.model.PantrySnapshot
import hr.smocnica.core.model.Product
import hr.smocnica.core.model.ProductFilter
import hr.smocnica.core.model.Category
import hr.smocnica.core.model.Shelf
import hr.smocnica.core.model.SyncState
import hr.smocnica.core.model.ShoppingItem
import hr.smocnica.core.model.AggregateType
import hr.smocnica.core.model.ActivityType
import hr.smocnica.core.model.OperationPayload
import hr.smocnica.core.model.OperationState
import hr.smocnica.core.data.remote.ApplyResult
import hr.smocnica.core.data.remote.ApplyStatus
import hr.smocnica.core.data.remote.OperationGateway
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalInventoryRepositoryTest {
    private lateinit var database: SmocnicaDatabase
    private lateinit var repository: LocalInventoryRepository
    private lateinit var backup: BackupRepositoryImpl
    private lateinit var context: android.content.Context
    private lateinit var json: Json
    private var id = 0

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, SmocnicaDatabase::class.java).allowMainThreadQueries().build()
        json = Json { classDiscriminator = "type"; encodeDefaults = true }
        val clock = Clock { 1_000L }
        val ids = IdGenerator { "id-${++id}" }
        val identity = DeviceIdentity(context)
        repository = LocalInventoryRepository(database, json, clock, ids, identity)
        backup = BackupRepositoryImpl(database, json, clock, ids, identity)
        database.pantryDao().upsert(PantryEntity("p1", "Test", "u1", 0, 1, 1, null, null, SyncState.SYNCED))
        database.shelfDao().upsert(ShelfEntity("s1", "p1", "Polica 1", 0, 0, 1, 1, null, null, SyncState.SYNCED))
        database.categoryDao().upsert(CategoryEntity("cat-other", "p1", "Ostalo", 9, true, 1, null, null, SyncState.SYNCED))
    }

    @After fun tearDown() = database.close()

    @Test
    fun productStockAndOutboxAreCommittedTogether() = runTest {
        val product = repository.upsertProduct(
            Product("", "p1", "Riža 1 kg", category = "Ostalo", categoryId = "cat-other", minimumQuantity = 3, createdAt = 1, updatedAt = 1),
            "u1",
            "Test uređaj",
        )
        repository.adjustStock(product.id, "s1", 1, "u1", "Test uređaj")

        repository.observeProducts("p1").test {
            val item = awaitItem().single()
            assertEquals(1, item.totalQuantity)
            assertEquals(2, item.shortfall)
            cancelAndIgnoreRemainingEvents()
        }
        repository.observeShopping("p1").test {
            val shopping = awaitItem().single()
            assertEquals("auto_${product.id}", shopping.id)
            assertEquals(2, shopping.requiredQuantity)
            assertEquals("cat-other", shopping.categoryId)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(2, database.operationDao().next().size)
        assertTrue(database.operationDao().next().all { it.state.name == "PENDING" })
        val stockOperation = database.operationDao().next().last()
        val payload = json.decodeFromString(OperationPayload.serializer(), stockOperation.payloadJson) as OperationPayload.AdjustStock
        assertEquals("Riža 1 kg", payload.productName)
        assertEquals("Polica 1", payload.shelfName)
        val activity = database.activityDao().listSince("p1", 0).first { it.aggregateId == product.id && it.quantityDelta == 1 }
        assertEquals("Polica 1", activity.oldValue)
        assertEquals("Polica 1", activity.newValue)
        assertEquals(product.id, activity.productId)
        assertEquals("s1", activity.shelfId)
    }

    @Test
    fun productCategoryIsCanonicalizedFromTheActiveCategoryId() = runTest {
        val product = repository.upsertProduct(
            Product("", "p1", "Test", category = "Krivotvoren naziv", categoryId = "cat-other", createdAt = 1, updatedAt = 1),
            "u1", "Test uređaj",
        )
        assertEquals("cat-other", product.categoryId)
        assertEquals("Ostalo", product.category)

        val invalid = runCatching {
            repository.upsertProduct(
                Product("", "p1", "Nevaljan", category = "Ostalo", categoryId = "missing", createdAt = 1, updatedAt = 1),
                "u1", "Test uređaj",
            )
        }
        assertTrue(invalid.isFailure)
    }

    @Test
    fun productFilterUsesCanonicalCategoryIdInsteadOfDisplayName() = runTest {
        database.categoryDao().upsert(CategoryEntity("cat-food", "p1", "Hrana", 1, false, 1, null, null, SyncState.SYNCED))
        repository.upsertProduct(
            Product("", "p1", "Riža", category = "Zastarjeli naziv", categoryId = "cat-food", createdAt = 1, updatedAt = 1),
            "u1", "Test uređaj",
        )
        repository.upsertProduct(
            Product("", "p1", "Sol", category = "Ostalo", categoryId = "cat-other", createdAt = 1, updatedAt = 1),
            "u1", "Test uređaj",
        )

        repository.observeProducts("p1", ProductFilter(categoryIds = setOf("cat-food"))).test {
            assertEquals(listOf("Riža"), awaitItem().map { it.product.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun shelfAndCategoryNamesAreUniqueAfterCanonicalNormalization() = runTest {
        val shelf = repository.createShelf("p1", "  Nova   POLICA ", "u1", "Test uređaj")
        assertEquals("Nova POLICA", shelf.name)
        assertTrue(runCatching { repository.createShelf("p1", "nova polica", "u1", "Test uređaj") }.isFailure)

        val category = repository.upsertCategory(Category("", "p1", "  PiĆa  ", 1, isDefault = true), "u1", "Test uređaj")
        assertEquals("PiĆa", category.name)
        assertEquals(false, category.isDefault)
        assertTrue(runCatching {
            repository.upsertCategory(Category("", "p1", "pića", 2, isDefault = false), "u1", "Test uređaj")
        }.isFailure)
        assertEquals(1, database.categoryDao().listActive("p1").count { it.isDefault })
    }

    @Test
    fun categoryRenameUpdatesLinkedRowsByIdEvenWhenDisplayTextIsStale() = runTest {
        val category = repository.upsertCategory(Category("", "p1", "Pića", 1), "u1", "Test uređaj")
        database.shoppingDao().upsert(
            ShoppingEntity(
                id = "manual-stale", pantryId = "p1", productId = null, name = "Sok", category = "Pogrešan tekst",
                requiredQuantity = 1, checked = false, manual = true, revision = 1, createdAt = 1, updatedAt = 1,
                deletedAt = null, syncState = SyncState.SYNCED, categoryId = category.id,
            ),
        )
        repository.upsertCategory(category.copy(name = "Napitci", isDefault = true), "u1", "Test uređaj")

        val linked = database.shoppingDao().get("manual-stale")!!
        assertEquals(category.id, linked.categoryId)
        assertEquals("Napitci", linked.category)
        assertEquals(1, database.categoryDao().listActive("p1").count { it.isDefault })
    }

    @Test
    fun identicalManualShoppingItemsAreMergedAndReactivated() = runTest {
        repository.addManualShoppingItem("p1", "  Mlijeko   bez laktoze ", "cat-other", 1, "u1", "Test uređaj", checked = true)
        repository.addManualShoppingItem("p1", "mlijeko bez laktoze", "cat-other", 2, "u1", "Test uređaj")

        val active = database.shoppingDao().listActive("p1")
        assertEquals(1, active.size)
        assertEquals("Mlijeko bez laktoze", active.single().name)
        assertEquals("Ostalo", active.single().category)
        assertEquals("cat-other", active.single().categoryId)
        assertEquals(3, active.single().requiredQuantity)
        assertEquals(false, active.single().checked)
        assertTrue(active.single().id.matches(Regex("manual_[0-9a-f]{64}")))
        assertEquals(1, database.operationDao().next().size)
        val payload = json.decodeFromString(OperationPayload.serializer(), database.operationDao().next().single().payloadJson)
            as OperationPayload.UpsertShopping
        assertEquals(3, payload.item.requiredQuantity)
        assertEquals(3, payload.quantityDelta)
    }

    @Test
    fun twoOfflineDevicesGenerateTheSameManualShoppingId() = runTest {
        val secondDatabase = Room.inMemoryDatabaseBuilder(context, SmocnicaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            secondDatabase.pantryDao().upsert(PantryEntity("p1", "Test", "u2", 0, 1, 1, null, null, SyncState.SYNCED))
            secondDatabase.categoryDao().upsert(CategoryEntity("cat-other", "p1", "Ostalo", 9, true, 1, null, null, SyncState.SYNCED))
            val secondRepository = LocalInventoryRepository(
                secondDatabase,
                json,
                Clock { 2_000L },
                IdGenerator { "drugi-nasumicni-id" },
                DeviceIdentity(context),
            )

            val first = repository.addManualShoppingItem(
                "p1", "  KRUH   integralni ", "cat-other", 2, "u1", "Prvi uređaj",
            )
            val second = secondRepository.addManualShoppingItem(
                "p1", "kruh integralni", "cat-other", 3, "u2", "Drugi uređaj",
            )

            assertEquals(first.id, second.id)
            assertTrue(first.id.matches(Regex("manual_[0-9a-f]{64}")))
        } finally {
            secondDatabase.close()
        }
    }

    @Test
    fun deletingSyncedManualShoppingItemCreatesTombstoneAndDeleteOperation() = runTest {
        val item = ShoppingItem(
            id = "manual-1", pantryId = "p1", productId = null, name = "Kruh", category = "Ostalo",
            requiredQuantity = 1, checked = false, manual = true, revision = 3,
            createdAt = 1, updatedAt = 1, syncState = SyncState.SYNCED,
        )
        database.shoppingDao().upsert(
            ShoppingEntity("manual-1", "p1", null, "Kruh", "Ostalo", 1, false, true, 3, 1, 1, null, SyncState.SYNCED),
        )

        val deleted = repository.deleteManualShoppingItem(item, "u1", "Test uređaj")

        assertEquals(item.id, deleted.id)
        assertTrue(database.shoppingDao().get(item.id)?.deletedAt != null)
        val operation = database.operationDao().next().single()
        assertEquals(3, operation.baseRevision)
        val payload = json.decodeFromString(OperationPayload.serializer(), operation.payloadJson) as OperationPayload.DeleteShopping
        assertEquals(item.id, payload.itemId)
    }

    @Test
    fun automaticShoppingItemIsUncheckedWhenShortageGrows() = runTest {
        val product = repository.upsertProduct(
            Product("", "p1", "Riža", category = "Ostalo", categoryId = "cat-other", minimumQuantity = 5, createdAt = 1, updatedAt = 1),
            "u1", "Test uređaj",
        )
        repository.adjustStock(product.id, "s1", 2, "u1", "Test uređaj")
        val automatic = database.shoppingDao().forProduct("p1", product.id)!!.model()
        repository.setShoppingChecked(automatic, true, "u1", "Test uređaj")

        repository.adjustStock(product.id, "s1", -1, "u1", "Test uređaj")

        val refreshed = database.shoppingDao().forProduct("p1", product.id)!!
        assertEquals(4, refreshed.requiredQuantity)
        assertEquals(false, refreshed.checked)
    }

    @Test
    fun removingMoreThanAvailableWritesNothing() = runTest {
        val product = repository.upsertProduct(
            Product("", "p1", "Sol", category = "Ostalo", categoryId = "cat-other", createdAt = 1, updatedAt = 1), "u1", "Test uređaj",
        )
        val before = database.operationDao().next().size
        runCatching { repository.adjustStock(product.id, "s1", -1, "u1", "Test uređaj") }
        assertEquals(0, database.stockDao().total(product.id))
        assertEquals(before, database.operationDao().next().size)
    }

    @Test
    fun inventoryDraftSurvivesObservationAndCanBeDiscarded() = runTest {
        val product = repository.upsertProduct(
            Product("", "p1", "Sol", category = "Ostalo", categoryId = "cat-other", createdAt = 1, updatedAt = 1), "u1", "Test uređaj",
        )
        val draft = repository.previewInventory("p1", "s1", mapOf(product.id to 0), "u1", "Test uređaj")
        repository.observeInventoryDraft("p1").test {
            assertEquals(draft.id, awaitItem()?.id)
            repository.discardInventoryDraft(draft.id)
            assertEquals(null, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun forgedImportPreviewIsRevalidatedBeforeAnyWrite() = runTest {
        val duplicateBarcode = "4006381333931"
        val snapshot = PantrySnapshot(
            pantry = Pantry("source", "Izvor", "u1", createdAt = 1, updatedAt = 1),
            members = emptyList(), shelves = emptyList(), categories = emptyList(),
            products = listOf(
                Product("a", "source", "A", barcode = duplicateBarcode, createdAt = 1, updatedAt = 1),
                Product("b", "source", "B", barcode = duplicateBarcode, createdAt = 1, updatedAt = 1),
            ),
            stocks = emptyList(), shoppingItems = emptyList(), activities = emptyList(),
        )
        val forged = ImportPreview(1, "Izvor", 0, 2, 0, snapshot, conflicts = emptyList())
        assertTrue(runCatching { backup.import(forged, ImportStrategy.MERGE, "p1", "u1", "Test uređaj") }.isFailure)
        assertEquals(null, database.productDao().get("a"))
        assertEquals(null, database.productDao().get("b"))
    }

    @Test
    fun importRejectsCanonicalDuplicateNamesBeforeAnyWrite() = runTest {
        val snapshot = PantrySnapshot(
            pantry = Pantry("source", "Izvor", "u1", createdAt = 1, updatedAt = 1),
            members = emptyList(),
            shelves = listOf(
                Shelf("duplicate-a", "source", "Nova   polica", 0, createdAt = 1, updatedAt = 1),
                Shelf("duplicate-b", "source", "  NOVA polica ", 1, createdAt = 1, updatedAt = 1),
            ),
            categories = listOf(Category("source-default", "source", "Ostalo", 0, isDefault = true)),
            products = emptyList(), stocks = emptyList(), shoppingItems = emptyList(), activities = emptyList(),
        )
        val forged = ImportPreview(2, "Izvor", 2, 0, 0, snapshot, conflicts = emptyList())

        assertTrue(runCatching { backup.import(forged, ImportStrategy.MERGE, "p1", "u1", "Test uređaj") }.isFailure)
        assertEquals(null, database.shelfDao().get("duplicate-a"))
        assertEquals(null, database.shelfDao().get("duplicate-b"))
    }

    @Test
    fun outboxPreservesInsertionOrderWhenOperationsShareTimestamp() = runTest {
        val product = repository.upsertProduct(
            Product("", "p1", "Riža", category = "Ostalo", categoryId = "cat-other", createdAt = 1, updatedAt = 1), "u1", "Test uređaj",
        )
        repository.adjustStock(product.id, "s1", 1, "u1", "Test uređaj")
        val appliedTypes = mutableListOf<String>()
        val gateway = object : OperationGateway {
            override suspend fun apply(operation: hr.smocnica.core.data.local.PendingOperationEntity): ApplyResult {
                appliedTypes += operation.aggregateType
                return ApplyResult(ApplyStatus.APPLIED, operation.baseRevision + 1)
            }
        }
        val access = testAccessCoordinator()
        val sync = OutboxSyncRepository(
            database, gateway, RealtimePantrySynchronizer(context, database, access), json, PrivacySafeCrashReporter(context), access,
        )
        val result = sync.synchronize()
        assertEquals(listOf(AggregateType.PRODUCT.name, AggregateType.STOCK.name), appliedTypes)
        assertEquals(2, result.applied)
        assertEquals(0, database.operationDao().countUnsynced())
    }

    @Test
    fun keepLocalRebasesReorderOntoRemoteRevisionAndNewShelfSet() = runTest {
        repository.reorderShelves("p1", listOf("s1"), 0, "u1", "Test uređaj")
        database.shelfDao().upsert(ShelfEntity("s2", "p1", "Polica 2", 1, 0, 1, 1, null, null, SyncState.SYNCED))
        val gateway = object : OperationGateway {
            override suspend fun apply(operation: hr.smocnica.core.data.local.PendingOperationEntity) =
                ApplyResult(ApplyStatus.CONFLICT, 7)
        }
        val access = testAccessCoordinator()
        val sync = OutboxSyncRepository(
            database, gateway, RealtimePantrySynchronizer(context, database, access), json, PrivacySafeCrashReporter(context), access,
        )
        assertEquals(1, sync.synchronize().conflicts)
        val conflicted = database.operationDao().get("id-1")!!
        assertEquals(OperationState.CONFLICT, conflicted.state)
        sync.resolveConflict(conflicted.operationId, keepLocal = true)
        val rebased = database.operationDao().get(conflicted.operationId)!!
        assertEquals(7, rebased.baseRevision)
        assertEquals(OperationState.PENDING, rebased.state)
        val payload = json.decodeFromString(OperationPayload.serializer(), rebased.payloadJson) as OperationPayload.ReorderShelves
        assertEquals(listOf("s1", "s2"), payload.orderedShelfIds)
    }

    private fun testAccessCoordinator() = PantryAccessCoordinator(
        PantryAccessStore(database),
        PantryAccessRefresher { },
    )

    @Test
    fun deletedBarcodeCannotBeReplacedByANewProduct() = runTest {
        val barcode = "4006381333931"
        val original = repository.upsertProduct(
            Product("", "p1", "Original", barcode = barcode, category = "Ostalo", categoryId = "cat-other", createdAt = 1, updatedAt = 1),
            "u1",
            "Test uređaj",
        )
        repository.deleteProduct(original, "u1", "Test uređaj")

        val result = runCatching {
            repository.upsertProduct(
                Product("", "p1", "Duplikat", barcode = barcode, category = "Ostalo", categoryId = "cat-other", createdAt = 1, updatedAt = 1),
                "u1",
                "Test uređaj",
            )
        }

        assertTrue(result.isFailure)
        val preserved = database.productDao().get(original.id)
        assertEquals("Original", preserved?.name)
        assertTrue(preserved?.deletedAt != null)
    }

    @Test
    fun restoringDeletedProductAndAddingStockIsOneLocalTransaction() = runTest {
        val product = repository.upsertProduct(
            Product("", "p1", "Sok", barcode = "4006381333931", category = "Ostalo", categoryId = "cat-other", createdAt = 1, updatedAt = 1),
            "u1",
            "Test uređaj",
        )
        repository.adjustStock(product.id, "s1", 2, "u1", "Test uređaj")
        repository.deleteProduct(product, "u1", "Test uređaj")

        repository.restoreProductAndAdjustStock("p1", product.id, "s1", 3, "u1", "Test uređaj")

        repository.observeProducts("p1").test {
            val restored = awaitItem().single()
            assertEquals(5, restored.totalQuantity)
            assertEquals(null, restored.product.deletedAt)
            cancelAndIgnoreRemainingEvents()
        }
        val pending = database.operationDao().next()
        val lastPayloads = pending.takeLast(2).map { json.decodeFromString(OperationPayload.serializer(), it.payloadJson) }
        assertTrue(lastPayloads[0] is OperationPayload.Restore)
        assertTrue(lastPayloads[1] is OperationPayload.AdjustStock)
        val activityTypes = database.activityDao().listSince("p1", 0).map { it.type }
        assertTrue(ActivityType.ITEM_RESTORED.name in activityTypes)
        assertTrue(ActivityType.STOCK_ADDED.name in activityTypes)
    }
}
