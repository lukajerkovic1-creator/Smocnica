package hr.smocnica.core.data.repository

import androidx.room.withTransaction
import com.google.firebase.functions.FirebaseFunctionsException
import hr.smocnica.core.data.local.PendingOperationEntity
import hr.smocnica.core.data.PrivacySafeCrashReporter
import hr.smocnica.core.data.TechnicalErrorCode
import hr.smocnica.core.data.local.SmocnicaDatabase
import hr.smocnica.core.data.remote.ApplyStatus
import hr.smocnica.core.data.remote.OperationGateway
import hr.smocnica.core.domain.SyncRepository
import hr.smocnica.core.domain.SyncResult
import hr.smocnica.core.domain.SyncConflict
import hr.smocnica.core.model.AggregateType
import hr.smocnica.core.model.OperationPayload
import hr.smocnica.core.model.OperationState
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class OutboxSyncRepository @Inject constructor(
    private val database: SmocnicaDatabase,
    private val gateway: OperationGateway,
    private val realtime: RealtimePantrySynchronizer,
    private val json: Json,
    private val crashReporter: PrivacySafeCrashReporter,
    private val accessCoordinator: PantryAccessCoordinator,
) : SyncRepository {
    override fun startRealtime(pantryId: String) = realtime.start(pantryId)
    override fun stopRealtime() = realtime.stop()
    override fun observeConflicts(pantryId: String): Flow<List<SyncConflict>> =
        database.operationDao().observeConflicts(pantryId).map { values -> values.map { operation ->
            val payload = runCatching { json.decodeFromString(OperationPayload.serializer(), operation.payloadJson) }.getOrNull()
            SyncConflict(operation.operationId, operation.aggregateType, operation.aggregateId, payload?.let(::payloadLabel) ?: "Lokalna promjena", operation.createdAt, operation.state == OperationState.CONFLICT, operation.errorCode)
        } }

    override suspend fun synchronize(): SyncResult {
        val accessRefreshFailed = !accessCoordinator.reconcileQuarantinedAccess()
        var applied = 0
        var conflicts = 0
        var failed = if (accessRefreshFailed) 1 else 0
        val refreshPantries = linkedSetOf<String>()
        for (operation in database.operationDao().next()) {
            database.operationDao().setState(operation.operationId, OperationState.IN_FLIGHT)
            try {
                val result = gateway.apply(operation)
                when (result.status) {
                    ApplyStatus.APPLIED, ApplyStatus.ALREADY_APPLIED -> {
                        val refresh = database.withTransaction {
                            val requiresRefresh = markAggregateSynced(operation, result.revision)
                            database.operationDao().delete(operation.operationId)
                            requiresRefresh
                        }
                        if (refresh) refreshPantries += operation.pantryId
                        applied++
                    }
                    ApplyStatus.CONFLICT -> {
                        database.operationDao().setState(operation.operationId, OperationState.CONFLICT, errorCode = "REVISION_CONFLICT:${result.revision}")
                        conflicts++
                        break
                    }
                }
            } catch (error: Exception) {
                if ((error as? FirebaseFunctionsException)?.code == FirebaseFunctionsException.Code.ABORTED) {
                    crashReporter.record(TechnicalErrorCode.SYNC_CONFLICT, error)
                    val remoteRevision = Regex("REVISION_CONFLICT:(\\d+)").find(error.message.orEmpty())?.groupValues?.get(1)
                    database.operationDao().setState(
                        operation.operationId,
                        OperationState.CONFLICT,
                        errorCode = remoteRevision?.let { "REVISION_CONFLICT:$it" } ?: "REVISION_CONFLICT",
                    )
                    conflicts++
                    break
                }
                if ((error as? FirebaseFunctionsException)?.code == FirebaseFunctionsException.Code.PERMISSION_DENIED) {
                    database.operationDao().setState(
                        operation.operationId,
                        OperationState.PENDING,
                        attemptIncrement = 1,
                        errorCode = "FUNCTION_PERMISSION_DENIED",
                    )
                    accessCoordinator.quarantineAndRefresh(operation.pantryId)
                    crashReporter.record(TechnicalErrorCode.SYNC_TRANSPORT, error)
                    failed++
                    break
                }
                val permanent = (error as? FirebaseFunctionsException)?.code in setOf(
                    FirebaseFunctionsException.Code.INVALID_ARGUMENT,
                    FirebaseFunctionsException.Code.UNAUTHENTICATED,
                    FirebaseFunctionsException.Code.FAILED_PRECONDITION,
                    FirebaseFunctionsException.Code.NOT_FOUND,
                )
                database.operationDao().setState(
                    operation.operationId,
                    if (permanent) OperationState.PERMANENT_FAILURE else OperationState.PENDING,
                    attemptIncrement = 1,
                    errorCode = errorCode(error),
                )
                crashReporter.record(TechnicalErrorCode.SYNC_TRANSPORT, error)
                failed++
                break
            }
        }
        refreshPantries.forEach(realtime::refresh)
        return SyncResult(applied, conflicts, failed)
    }

    override suspend fun resolveConflict(operationId: String, keepLocal: Boolean) {
        val operation = database.operationDao().get(operationId) ?: error("Konflikt više ne postoji.")
        require(operation.state in setOf(OperationState.CONFLICT, OperationState.PERMANENT_FAILURE)) { "Operacija nema problem za rješavanje." }
        if (keepLocal) {
            val serverRevision = operation.errorCode?.substringAfter("REVISION_CONFLICT:", "")?.toLongOrNull()
            val payload = json.decodeFromString(OperationPayload.serializer(), operation.payloadJson)
            val rebasedPayload = when (payload) {
                is OperationPayload.ReorderShelves -> {
                    val active = database.shelfDao().listActive(operation.pantryId).map { it.id }
                    payload.copy(orderedShelfIds = payload.orderedShelfIds.filter { it in active } + active.filterNot { it in payload.orderedShelfIds })
                }
                is OperationPayload.ReorderCategories -> {
                    val active = database.categoryDao().listActive(operation.pantryId).map { it.id }
                    payload.copy(orderedCategoryIds = payload.orderedCategoryIds.filter { it in active } + active.filterNot { it in payload.orderedCategoryIds })
                }
                else -> payload
            }
            database.operationDao().requeue(
                operationId,
                serverRevision ?: currentRevision(operation),
                json.encodeToString(OperationPayload.serializer(), rebasedPayload),
            )
        } else {
            database.withTransaction {
                allowRemote(operation)
                database.operationDao().delete(operationId)
            }
            realtime.refresh(operation.pantryId)
        }
    }

    override suspend fun hasUnsyncedChanges(): Boolean = database.operationDao().countUnsynced() > 0

    private suspend fun currentRevision(operation: PendingOperationEntity): Long = when (AggregateType.valueOf(operation.aggregateType)) {
        AggregateType.SHELF -> database.shelfDao().get(operation.aggregateId)?.revision
        AggregateType.CATEGORY -> database.categoryDao().get(operation.aggregateId)?.revision
        AggregateType.PRODUCT -> database.productDao().get(operation.aggregateId)?.revision
        AggregateType.SHOPPING -> database.shoppingDao().get(operation.aggregateId)?.revision
        AggregateType.STOCK -> when (val payload = json.decodeFromString(OperationPayload.serializer(), operation.payloadJson)) {
            is OperationPayload.AdjustStock -> database.stockDao().get(payload.productId, payload.shelfId)?.revision
            is OperationPayload.MoveStock -> database.stockDao().get(payload.productId, payload.fromShelfId)?.revision
            else -> null
        }
        AggregateType.PANTRY -> database.pantryDao().get(operation.pantryId)?.revision
        else -> null
    } ?: operation.baseRevision

    private fun payloadLabel(payload: OperationPayload): String = when (payload) {
        is OperationPayload.RenameShelf -> "Preimenovanje police"
        is OperationPayload.ReorderShelves -> "Promjena redoslijeda polica"
        is OperationPayload.UpsertProduct -> "Promjena artikla ${payload.product.name}"
        is OperationPayload.UpsertCategory -> "Promjena kategorije ${payload.category.name}"
        is OperationPayload.DeleteShelf, is OperationPayload.DeleteCategory, is OperationPayload.SoftDelete -> "Brisanje zapisa"
        else -> "Promjena ${payload::class.simpleName.orEmpty()}"
    }

    private suspend fun markAggregateSynced(operation: PendingOperationEntity, revision: Long): Boolean {
        var refresh = false
        when (AggregateType.valueOf(operation.aggregateType)) {
            AggregateType.SHELF -> database.shelfDao().markSynced(operation.aggregateId, revision)
            AggregateType.CATEGORY -> database.categoryDao().markSynced(operation.aggregateId, revision)
            AggregateType.PRODUCT -> {
                database.productDao().markSynced(operation.aggregateId, revision)
                database.shoppingDao().allowRemote("auto_${operation.aggregateId}")
                refresh = true
            }
            AggregateType.SHOPPING -> database.shoppingDao().markSynced(operation.aggregateId, revision)
            AggregateType.STOCK -> when (val payload = json.decodeFromString(OperationPayload.serializer(), operation.payloadJson)) {
                is OperationPayload.AdjustStock -> {
                    database.stockDao().markSynced(payload.productId, payload.shelfId, revision)
                    database.shoppingDao().allowRemote("auto_${payload.productId}")
                    refresh = true
                }
                is OperationPayload.MoveStock -> {
                    database.stockDao().markSynced(payload.productId, payload.fromShelfId, revision)
                    database.stockDao().markSynced(payload.productId, payload.toShelfId, revision)
                }
                else -> Unit
            }
            AggregateType.PANTRY -> {
                when (json.decodeFromString(OperationPayload.serializer(), operation.payloadJson)) {
                    is OperationPayload.ReorderShelves -> database.shelfDao().allowRemoteForPantry(operation.pantryId)
                    is OperationPayload.ReorderCategories -> database.categoryDao().allowRemoteForPantry(operation.pantryId)
                    is OperationPayload.ImportSnapshot -> {
                        database.shelfDao().allowRemoteForPantry(operation.pantryId)
                        database.categoryDao().allowRemoteForPantry(operation.pantryId)
                        database.productDao().allowRemoteForPantry(operation.pantryId)
                        database.stockDao().allowRemoteForPantry(operation.pantryId)
                        database.shoppingDao().allowRemoteForPantry(operation.pantryId)
                    }
                    else -> Unit
                }
                refresh = true
            }
            AggregateType.INVENTORY -> {
                val payload = json.decodeFromString(OperationPayload.serializer(), operation.payloadJson)
                if (payload is OperationPayload.ApplyInventory) {
                    payload.session.differences.forEach {
                        database.stockDao().allowRemote(it.productId, payload.session.shelfId)
                        database.shoppingDao().allowRemote("auto_${it.productId}")
                    }
                }
                refresh = true
            }
            else -> Unit
        }
        return refresh
    }

    private suspend fun allowRemote(operation: PendingOperationEntity) {
        val payload = json.decodeFromString(OperationPayload.serializer(), operation.payloadJson)
        when (AggregateType.valueOf(operation.aggregateType)) {
            AggregateType.SHELF -> database.shelfDao().allowRemote(operation.aggregateId)
            AggregateType.CATEGORY -> database.categoryDao().allowRemote(operation.aggregateId)
            AggregateType.PRODUCT -> database.productDao().allowRemote(operation.aggregateId)
            AggregateType.SHOPPING -> database.shoppingDao().allowRemote(operation.aggregateId)
            AggregateType.STOCK -> when (payload) {
                is OperationPayload.AdjustStock -> database.stockDao().allowRemote(payload.productId, payload.shelfId)
                is OperationPayload.MoveStock -> {
                    database.stockDao().allowRemote(payload.productId, payload.fromShelfId)
                    database.stockDao().allowRemote(payload.productId, payload.toShelfId)
                }
                else -> Unit
            }
            AggregateType.PANTRY -> {
                when (payload) {
                    is OperationPayload.ReorderShelves -> database.shelfDao().allowRemoteForPantry(operation.pantryId)
                    is OperationPayload.ReorderCategories -> database.categoryDao().allowRemoteForPantry(operation.pantryId)
                    is OperationPayload.ImportSnapshot -> {
                    database.shelfDao().allowRemoteForPantry(operation.pantryId)
                    database.categoryDao().allowRemoteForPantry(operation.pantryId)
                    database.productDao().allowRemoteForPantry(operation.pantryId)
                    database.stockDao().allowRemoteForPantry(operation.pantryId)
                    database.shoppingDao().allowRemoteForPantry(operation.pantryId)
                    }
                    else -> Unit
                }
            }
            AggregateType.INVENTORY -> if (payload is OperationPayload.ApplyInventory) {
                payload.session.differences.forEach { database.stockDao().allowRemote(it.productId, payload.session.shelfId) }
            }
            else -> Unit
        }
    }

    private fun errorCode(error: Exception): String = when (error) {
        is FirebaseFunctionsException -> "FUNCTION_${error.code.name}"
        else -> "SYNC_TRANSPORT"
    }
}
