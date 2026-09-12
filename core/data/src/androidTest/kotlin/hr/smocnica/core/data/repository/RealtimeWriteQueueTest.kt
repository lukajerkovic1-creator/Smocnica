package hr.smocnica.core.data.repository

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import hr.smocnica.core.data.local.PantryEntity
import hr.smocnica.core.data.local.PendingOperationEntity
import hr.smocnica.core.data.local.ShelfEntity
import hr.smocnica.core.data.local.SmocnicaDatabase
import hr.smocnica.core.model.OperationState
import hr.smocnica.core.model.SyncState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealtimeWriteQueueTest {
    private lateinit var db: SmocnicaDatabase
    private lateinit var queue: RealtimeWriteQueue
    private val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    private val original = ShelfEntity("s", "p", "Original", 0, 1, 1, 1, null, null, SyncState.SYNCED)

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SmocnicaDatabase::class.java).build()
        db.pantryDao().upsert(PantryEntity("p", "Test", "u", 1, 1, 1, null, null, SyncState.SYNCED))
        db.shelfDao().upsert(original)
        queue = RealtimeWriteQueue(db) { failures.add(it) }
    }

    @After fun cleanup() = runBlocking {
        drain()
        db.close()
        assertTrue(failures.toString(), failures.isEmpty())
    }

    private suspend fun drain() {
        val done = CompletableDeferred<Unit>()
        queue.submit(queue.generation(), "p", false) { done.complete(Unit) }
        withTimeout(5_000) { done.await() }
        db.withTransaction { /* Wait for the sentinel transaction to commit. */ }
    }

    @Test fun localMutationCannotInterleaveSnapshotReadAndWrite() = runBlocking {
        val read = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        queue.submit(queue.generation(), "p") {
            assertEquals(SyncState.SYNCED, db.shelfDao().get("s")!!.syncState)
            read.complete(Unit)
            release.await()
            db.shelfDao().upsert(original.copy(name = "Remote", revision = 2))
        }
        withTimeout(5_000) { read.await() }
        val local = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            db.withTransaction { db.shelfDao().upsert(original.copy(name = "Local", syncState = SyncState.PENDING)) }
        }
        release.complete(Unit)
        local.await()
        drain()
        assertEquals("Local", db.shelfDao().get("s")!!.name)
        assertEquals(SyncState.PENDING, db.shelfDao().get("s")!!.syncState)
    }

    @Test fun pendingOperationProtectsRowsEvenAfterEarlierAcknowledgement() = runBlocking {
        db.operationDao().insert(PendingOperationEntity("op", "p", "SHELF", "s", 1, "{}", "u", "d", "Test", 1, 0, OperationState.PENDING, null))
        queue.submit(queue.generation(), "p") { db.shelfDao().upsert(original.copy(name = "Stale")) }
        drain()
        assertEquals("Original", db.shelfDao().get("s")!!.name)
    }

    @Test fun stopRollsBackInFlightSnapshotAndRejectsLateOldCallbacksAfterClear() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val generation = queue.generation()
        queue.submit(generation, "p") {
            db.shelfDao().upsert(original.copy(name = "Stale"))
            entered.complete(Unit)
            release.await()
        }
        withTimeout(5_000) { entered.await() }
        queue.invalidate()
        release.complete(Unit)
        drain()
        assertEquals("Original", db.shelfDao().get("s")!!.name)
        db.withTransaction { db.shelfDao().deleteHard("s") }
        queue.submit(generation, "p") { db.shelfDao().upsert(original) }
        drain()
        assertNull(db.shelfDao().get("s"))
        queue.submit(queue.generation(), "p") { db.shelfDao().upsert(original.copy(name = "New session")) }
        drain()
        assertEquals("New session", db.shelfDao().get("s")!!.name)
    }

    @Test fun snapshotsAreProcessedInArrivalOrder() = runBlocking {
        val release = CompletableDeferred<Unit>()
        queue.submit(queue.generation(), "p") { release.await(); db.shelfDao().upsert(original.copy(name = "First")) }
        queue.submit(queue.generation(), "p") { db.shelfDao().upsert(original.copy(name = "Second")) }
        release.complete(Unit)
        drain()
        assertEquals("Second", db.shelfDao().get("s")!!.name)
    }
}
