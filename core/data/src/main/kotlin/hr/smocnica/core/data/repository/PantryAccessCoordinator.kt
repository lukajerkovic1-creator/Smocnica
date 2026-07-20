package hr.smocnica.core.data.repository

import androidx.room.withTransaction
import hr.smocnica.core.data.local.SmocnicaDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

fun interface PantryAccessRefresher {
    suspend fun refreshAccessiblePantries()
}

@Singleton
class PantryAccessStore @Inject constructor(
    private val database: SmocnicaDatabase,
) {
    suspend fun quarantine(pantryId: String) {
        database.pantryDao().quarantineAccess(pantryId, System.currentTimeMillis())
    }

    suspend fun hasQuarantinedPantries(): Boolean = database.pantryDao().quarantinedCount() > 0

    suspend fun confirmAccessiblePantries(activeIds: Set<String>) {
        database.withTransaction {
            database.pantryDao().allIds()
                .filterNot(activeIds::contains)
                .forEach { purgePantry(it) }
        }
    }

    private suspend fun purgePantry(pantryId: String) {
        database.operationDao().deleteForPantry(pantryId)
        database.inventoryDao().deleteForPantry(pantryId)
        database.activityDao().deleteForPantry(pantryId)
        database.shoppingDao().deleteForPantry(pantryId)
        database.stockDao().deleteForPantry(pantryId)
        database.productDao().deleteForPantry(pantryId)
        database.categoryDao().deleteForPantry(pantryId)
        database.shelfDao().deleteForPantry(pantryId)
        database.memberDao().deleteForPantry(pantryId)
        database.pantryDao().deleteLocal(pantryId)
    }
}

/**
 * Persists revoked access before attempting any network reconciliation. A quarantined pantry is
 * hidden by Room and excluded from the outbox, including after process death.
 */
@Singleton
class PantryAccessCoordinator @Inject constructor(
    private val accessStore: PantryAccessStore,
    private val refresher: PantryAccessRefresher,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()

    fun onPermissionDenied(pantryId: String) {
        scope.launch { quarantineAndRefresh(pantryId) }
    }

    internal suspend fun quarantineAndRefresh(pantryId: String): Boolean {
        accessStore.quarantine(pantryId)
        return refreshMutex.withLock {
            runCatching { refresher.refreshAccessiblePantries() }.isSuccess
        }
    }

    suspend fun reconcileQuarantinedAccess(): Boolean {
        if (!accessStore.hasQuarantinedPantries()) return true
        return refreshMutex.withLock {
            runCatching { refresher.refreshAccessiblePantries() }.isSuccess
        }
    }

}
