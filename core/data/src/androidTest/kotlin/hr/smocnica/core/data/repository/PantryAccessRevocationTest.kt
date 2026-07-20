package hr.smocnica.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import hr.smocnica.core.data.PrivacySafeCrashReporter
import hr.smocnica.core.data.local.ActivityEntity
import hr.smocnica.core.data.local.CategoryEntity
import hr.smocnica.core.data.local.InventoryEntity
import hr.smocnica.core.data.local.MemberEntity
import hr.smocnica.core.data.local.PantryEntity
import hr.smocnica.core.data.local.PendingOperationEntity
import hr.smocnica.core.data.local.ProductEntity
import hr.smocnica.core.data.local.ShelfEntity
import hr.smocnica.core.data.local.ShoppingEntity
import hr.smocnica.core.data.local.SmocnicaDatabase
import hr.smocnica.core.data.local.StockEntity
import hr.smocnica.core.model.OperationState
import hr.smocnica.core.model.SyncState
import hr.smocnica.core.data.remote.ApplyResult
import hr.smocnica.core.data.remote.ApplyStatus
import hr.smocnica.core.data.remote.OperationGateway
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PantryAccessRevocationTest {
    private lateinit var database: SmocnicaDatabase
    private lateinit var store: PantryAccessStore
    private lateinit var context: android.content.Context

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context,
            SmocnicaDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = PantryAccessStore(database)
        seedPantry()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun permissionDeniedWhileOpenImmediatelyHidesPantryAndBlocksOutbox() = runTest {
        val coordinator = PantryAccessCoordinator(store, PantryAccessRefresher { error("offline") })

        assertFalse(coordinator.quarantineAndRefresh("p1"))

        database.pantryDao().observeActive().test {
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(database.operationDao().next().isEmpty())
        assertTrue(database.pantryDao().get("p1")?.accessRevokedAt != null)
    }

    @Test
    fun returningFromBackgroundReconcilesPersistedQuarantineAndPurgesRevokedPantry() = runTest {
        PantryAccessCoordinator(store, PantryAccessRefresher { error("offline") })
            .quarantineAndRefresh("p1")
        val resumedCoordinator = PantryAccessCoordinator(
            store,
            PantryAccessRefresher { store.confirmAccessiblePantries(emptySet()) },
        )

        assertTrue(resumedCoordinator.reconcileQuarantinedAccess())

        assertNull(database.pantryDao().get("p1"))
        assertNull(database.productDao().get("product1"))
        assertNull(database.shelfDao().get("shelf1"))
        assertEquals(0, database.operationDao().countUnsynced())
        assertTrue(database.memberDao().listActive("p1").isEmpty())
        assertTrue(database.stockDao().listForPantry("p1").isEmpty())
        assertTrue(database.shoppingDao().listAll("p1").isEmpty())
        assertTrue(database.activityDao().listSince("p1", 0).isEmpty())
        assertNull(database.inventoryDao().latestDraft("p1"))
    }

    @Test
    fun offlineRevocationStaysQuarantinedUntilReconnectConfirmsAccess() = runTest {
        var online = false
        val original = database.pantryDao().get("p1")!!
        val coordinator = PantryAccessCoordinator(
            store,
            PantryAccessRefresher {
                if (!online) error("offline")
                database.pantryDao().upsert(original.copy(accessRevokedAt = null))
                store.confirmAccessiblePantries(setOf("p1"))
            },
        )

        assertFalse(coordinator.quarantineAndRefresh("p1"))
        assertTrue(database.pantryDao().get("p1")?.accessRevokedAt != null)
        online = true
        assertTrue(coordinator.reconcileQuarantinedAccess())

        assertNull(database.pantryDao().get("p1")?.accessRevokedAt)
        assertEquals(listOf("p1"), database.pantryDao().allIds())
        assertEquals(1, database.operationDao().next().size)
    }

    @Test
    fun quarantinedPantryDoesNotBlockOutboxForAnotherAccessiblePantry() = runTest {
        database.pantryDao().upsert(PantryEntity("p2", "Druga", "u1", 1, 1, 1, null, null, SyncState.SYNCED))
        database.operationDao().insert(
            PendingOperationEntity("operation2", "p2", "PRODUCT", "product2", 1, "{}", "u1", "device", "Telefon", 2, 0, OperationState.PENDING, null),
        )
        val appliedPantries = mutableListOf<String>()
        val gateway = object : OperationGateway {
            override suspend fun apply(operation: PendingOperationEntity): ApplyResult {
                appliedPantries += operation.pantryId
                return ApplyResult(ApplyStatus.APPLIED, 2)
            }
        }
        val coordinator = PantryAccessCoordinator(store, PantryAccessRefresher { error("offline") })
        store.quarantine("p1")
        val sync = OutboxSyncRepository(
            database,
            gateway,
            RealtimePantrySynchronizer(context, database, coordinator),
            Json { classDiscriminator = "type" },
            PrivacySafeCrashReporter(context),
            coordinator,
        )

        val result = sync.synchronize()

        assertEquals(listOf("p2"), appliedPantries)
        assertEquals(1, result.applied)
        assertEquals(1, result.failed)
        assertTrue(database.operationDao().get("operation1") != null)
        assertNull(database.operationDao().get("operation2"))
    }

    private suspend fun seedPantry() {
        database.pantryDao().upsert(PantryEntity("p1", "Test", "u1", 1, 1, 1, null, null, SyncState.SYNCED))
        database.memberDao().upsertAll(listOf(MemberEntity("p1", "u1", "Test", null, "MEMBER", 1, true)))
        database.shelfDao().upsert(ShelfEntity("shelf1", "p1", "Polica", 0, 1, 1, 1, null, null, SyncState.SYNCED))
        database.categoryDao().upsert(CategoryEntity("category1", "p1", "Ostalo", 0, true, 1, null, null, SyncState.SYNCED))
        database.productDao().upsert(
            ProductEntity("product1", "p1", "Artikl", "artikl", null, "", "Ostalo", "category1", null, "NONE", 0, true, 1, 1, 1, null, null, SyncState.SYNCED),
        )
        database.stockDao().upsert(StockEntity("p1", "product1", "shelf1", 1, 1, 1, SyncState.SYNCED))
        database.shoppingDao().upsert(ShoppingEntity("shop1", "p1", null, "Ručno", "Ostalo", 1, false, true, 1, 1, 1, null, SyncState.SYNCED))
        database.activityDao().insert(ActivityEntity("activity1", "p1", "STOCK_ADJUSTED", "product1", "Artikl", 1, "u1", "device", "Telefon", null, null, 1))
        database.inventoryDao().upsert(InventoryEntity("inventory1", "p1", "shelf1", 1, "DRAFT", "{}", "[]", "u1", "Telefon", 1, null))
        database.operationDao().insert(
            PendingOperationEntity("operation1", "p1", "PRODUCT", "product1", 1, "{}", "u1", "device", "Telefon", 1, 0, OperationState.PENDING, null),
        )
    }
}
