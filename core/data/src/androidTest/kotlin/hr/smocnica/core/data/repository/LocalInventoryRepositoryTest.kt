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
import hr.smocnica.core.data.local.SmocnicaDatabase
import hr.smocnica.core.domain.ImportPreview
import hr.smocnica.core.domain.ImportStrategy
import hr.smocnica.core.model.Pantry
import hr.smocnica.core.model.PantrySnapshot
import hr.smocnica.core.model.Product
import hr.smocnica.core.model.SyncState
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
            Product("", "p1", "Riža 1 kg", minimumQuantity = 3, createdAt = 1, updatedAt = 1),
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
    fun removingMoreThanAvailableWritesNothing() = runTest {
        val product = repository.upsertProduct(
            Product("", "p1", "Sol", createdAt = 1, updatedAt = 1), "u1", "Test uređaj",
        )
        val before = database.operationDao().next().size
        runCatching { repository.adjustStock(product.id, "s1", -1, "u1", "Test uređaj") }
        assertEquals(0, database.stockDao().total(product.id))
        assertEquals(before, database.operationDao().next().size)
    }

    @Test
    fun inventoryDraftSurvivesObservationAndCanBeDiscarded() = runTest {
        val product = repository.upsertProduct(
            Product("", "p1", "Sol", createdAt = 1, updatedAt = 1), "u1", "Test uređaj",
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
    fun outboxPreservesInsertionOrderWhenOperationsShareTimestamp() = runTest {
        val product = repository.upsertProduct(
            Product("", "p1", "Riža", createdAt = 1, updatedAt = 1), "u1", "Test uređaj",
        )
        repository.adjustStock(product.id, "s1", 1, "u1", "Test uređaj")
        val appliedTypes = mutableListOf<String>()
        val gateway = object : OperationGateway {
            override suspend fun apply(operation: hr.smocnica.core.data.local.PendingOperationEntity): ApplyResult {
                appliedTypes += operation.aggregateType
                return ApplyResult(ApplyStatus.APPLIED, operation.baseRevision + 1)
            }
        }
        val sync = OutboxSyncRepository(
            database, gateway, RealtimePantrySynchronizer(context, database), json, PrivacySafeCrashReporter(context),
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
        val sync = OutboxSyncRepository(
            database, gateway, RealtimePantrySynchronizer(context, database), json, PrivacySafeCrashReporter(context),
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

    @Test
    fun deletedBarcodeCannotBeReplacedByANewProduct() = runTest {
        val barcode = "4006381333931"
        val original = repository.upsertProduct(
            Product("", "p1", "Original", barcode = barcode, createdAt = 1, updatedAt = 1),
            "u1",
            "Test uređaj",
        )
        repository.deleteProduct(original, "u1", "Test uređaj")

        val result = runCatching {
            repository.upsertProduct(
                Product("", "p1", "Duplikat", barcode = barcode, createdAt = 1, updatedAt = 1),
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
            Product("", "p1", "Sok", barcode = "4006381333931", createdAt = 1, updatedAt = 1),
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
