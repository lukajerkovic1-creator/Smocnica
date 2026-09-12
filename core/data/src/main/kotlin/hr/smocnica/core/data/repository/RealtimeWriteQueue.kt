package hr.smocnica.core.data.repository

import androidx.room.withTransaction
import hr.smocnica.core.data.local.SmocnicaDatabase
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Orders snapshots and makes the pending check and writes atomic with local mutations. */
internal class RealtimeWriteQueue(
    private val database: SmocnicaDatabase,
    private val onFailure: (Throwable) -> Unit,
) {
    private val epoch = AtomicLong()
    private val tasks = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            for (task in tasks) {
                try { task() }
                catch (_: StaleSession) { /* The transaction rolls back invalidated snapshots. */ }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { onFailure(error) }
            }
        }
    }

    fun generation(): Long = epoch.get()
    fun invalidate() { epoch.incrementAndGet() }

    fun submit(generation: Long, pantryId: String, protectPending: Boolean = true, write: suspend () -> Unit) {
        tasks.trySend {
            database.withTransaction {
                if (generation != epoch.get()) return@withTransaction
                // An acknowledgement of one operation must not expose later optimistic changes.
                if (protectPending && database.operationDao().countForPantry(pantryId) > 0) return@withTransaction
                write()
                if (generation != epoch.get()) throw StaleSession()
            }
        }.getOrThrow()
    }

    private class StaleSession : RuntimeException()
}
