package hr.smocnica

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hr.smocnica.core.data.DeviceIdentity
import hr.smocnica.core.domain.InventoryRepository
import hr.smocnica.core.domain.BackupRepository
import hr.smocnica.core.domain.ImportPreview
import hr.smocnica.core.domain.ImportStrategy
import hr.smocnica.core.domain.PantryRepository
import hr.smocnica.core.domain.SessionRepository
import hr.smocnica.core.domain.SyncRepository
import hr.smocnica.core.domain.TrashRepository
import hr.smocnica.core.domain.UpdateRepository
import hr.smocnica.core.domain.AppUpdate
import hr.smocnica.core.domain.VerifiedApk
import hr.smocnica.core.data.messaging.DeviceRegistration
import hr.smocnica.core.data.messaging.NotificationPrivacyMode
import hr.smocnica.core.data.remote.BackendCompatibilityChecker
import hr.smocnica.core.data.remote.BackendCompatibilityResult
import hr.smocnica.core.domain.ProductPhotoRepository
import hr.smocnica.core.model.Category
import hr.smocnica.core.model.InventorySession
import hr.smocnica.core.model.Pantry
import hr.smocnica.core.model.Product
import hr.smocnica.core.model.ProductFilter
import hr.smocnica.core.model.ProductWithStock
import hr.smocnica.core.model.Shelf
import hr.smocnica.core.model.ShoppingItem
import hr.smocnica.core.model.Member
import hr.smocnica.core.model.TrashItem
import hr.smocnica.core.model.ActivityType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import javax.inject.Inject

sealed interface BackendReadiness {
    data object Checking : BackendReadiness
    data object Ready : BackendReadiness
    data class Blocked(val message: String) : BackendReadiness
}

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val pantriesRepository: PantryRepository,
    private val inventory: InventoryRepository,
    private val sync: SyncRepository,
    private val backup: BackupRepository,
    private val updates: UpdateRepository,
    private val trash: TrashRepository,
    private val photos: ProductPhotoRepository,
    private val deviceRegistration: DeviceRegistration,
    private val backendCompatibilityChecker: BackendCompatibilityChecker,
    val deviceIdentity: DeviceIdentity,
) : ViewModel() {
    val session = sessions.session.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val pantries = pantriesRepository.observePantries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val selectedPantryId = MutableStateFlow<String?>(null)
    private val filter = MutableStateFlow(ProductFilter())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()
    private val _isRestoringPantries = MutableStateFlow(false)
    val isRestoringPantries: StateFlow<Boolean> = _isRestoringPantries
    private val _notificationPrivacyMode = MutableStateFlow(deviceRegistration.notificationPrivacyMode)
    val notificationPrivacyMode: StateFlow<NotificationPrivacyMode> = _notificationPrivacyMode
    private val _notificationPrivacyUpdating = MutableStateFlow(false)
    val notificationPrivacyUpdating: StateFlow<Boolean> = _notificationPrivacyUpdating
    private val _backendReadiness = MutableStateFlow<BackendReadiness>(BackendReadiness.Checking)
    val backendReadiness: StateFlow<BackendReadiness> = _backendReadiness

    val selectedPantry: StateFlow<Pantry?> = selectedPantryId.flatMapLatest { id ->
        pantriesRepository.observePantries().flatMapLatest { values -> flowOf(values.firstOrNull { it.id == id } ?: values.firstOrNull()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val products = selectedPantry.flatMapLatest { pantry ->
        if (pantry == null) flowOf(emptyList()) else filter.flatMapLatest { inventory.observeProducts(pantry.id, it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<ProductWithStock>())

    val allProducts = selectedPantry.flatMapLatest { pantry ->
        pantry?.let { inventory.observeProducts(it.id) } ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<ProductWithStock>())

    val deletedProducts = selectedPantry.flatMapLatest { pantry ->
        pantry?.let { inventory.observeDeletedProducts(it.id) } ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<ProductWithStock>())

    val shelves = selectedPantry.flatMapLatest { pantry ->
        pantry?.let { inventory.observeShelves(it.id) } ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<Shelf>())

    val categories = selectedPantry.flatMapLatest { pantry ->
        pantry?.let { inventory.observeCategories(it.id) } ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<Category>())

    val shopping = selectedPantry.flatMapLatest { pantry ->
        pantry?.let { inventory.observeShopping(it.id) } ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<ShoppingItem>())

    val activities = selectedPantry.flatMapLatest { pantry ->
        pantry?.let {
            inventory.observeActivities(it.id, System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1_000)
                .map { values -> values.filterNot { activity -> activity.type == ActivityType.UNKNOWN } }
        } ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val inventoryDraft = selectedPantry.flatMapLatest { pantry ->
        pantry?.let { inventory.observeInventoryDraft(it.id) } ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val syncSummary = selectedPantry.flatMapLatest { pantry ->
        pantry?.let { inventory.observeSyncSummary(it.id) } ?: flowOf(hr.smocnica.core.model.SyncSummary())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), hr.smocnica.core.model.SyncSummary())

    val conflicts = selectedPantry.flatMapLatest { pantry ->
        pantry?.let { sync.observeConflicts(it.id) } ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val members = selectedPantry.flatMapLatest { pantry ->
        pantry?.let { pantriesRepository.observeMembers(it.id) } ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<Member>())

    val trashItems = selectedPantry.flatMapLatest { pantry ->
        pantry?.let { trash.observe(it.id) } ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<TrashItem>())

    private val _latestUpdate = MutableStateFlow<AppUpdate?>(null)
    val latestUpdate: StateFlow<AppUpdate?> = _latestUpdate
    private val _verifiedApk = MutableSharedFlow<VerifiedApk>(extraBufferCapacity = 1)
    val verifiedApk = _verifiedApk.asSharedFlow()
    private val _invitation = MutableStateFlow<hr.smocnica.core.domain.Invitation?>(null)
    val invitation: StateFlow<hr.smocnica.core.domain.Invitation?> = _invitation
    private var inventoryDraftJob: Job? = null

    init {
        viewModelScope.launch {
            pantries.collect { list ->
                if (_backendReadiness.value !is BackendReadiness.Ready) return@collect
                val selected = selectedPantryId.value
                when {
                    list.isEmpty() && selected != null -> {
                        sync.stopRealtime()
                        selectedPantryId.value = null
                    }
                    list.isNotEmpty() && list.none { it.id == selected } -> selectPantry(list.first().id)
                }
            }
        }
        viewModelScope.launch {
            session.collectLatest { currentSession ->
                if (currentSession == null) {
                    _backendReadiness.value = BackendReadiness.Checking
                    _isRestoringPantries.value = false
                } else {
                    restoreAuthenticatedSession()
                }
            }
        }
    }

    private suspend fun restoreAuthenticatedSession() {
        _isRestoringPantries.value = true
        _backendReadiness.value = BackendReadiness.Checking
        when (val result = backendCompatibilityChecker.check()) {
            is BackendCompatibilityResult.Blocked -> {
                sync.stopRealtime()
                _backendReadiness.value = BackendReadiness.Blocked(result.message)
                _isRestoringPantries.value = false
                return
            }
            is BackendCompatibilityResult.Compatible -> {
                _backendReadiness.value = BackendReadiness.Ready
                if (result.usedCachedConfirmation) {
                    _messages.emit("Poslužitelj trenutačno nije dostupan; koristi se zadnja potvrđena kompatibilna verzija.")
                }
            }
        }
        runCatching { deviceRegistration.registerCurrentToken() }
            .onFailure { _messages.emit(it.message ?: "Uređaj nije moguće registrirati. Provjerite mrežu i pokušajte ponovno.") }
        runCatching { pantriesRepository.refreshPantries() }
            .onFailure { _messages.emit(it.message ?: "Postojeću smočnicu nije moguće dohvatiti.") }
        _isRestoringPantries.value = false
    }

    fun retryBackendCompatibility() {
        if (session.value == null || _isRestoringPantries.value) return
        viewModelScope.launch { restoreAuthenticatedSession() }
    }

    fun selectPantry(id: String) {
        selectedPantryId.value = id
        sync.startRealtime(id)
        synchronize()
    }

    fun updateFilter(value: ProductFilter) { filter.value = value }

    fun signIn(idToken: String) = action { sessions.signInWithGoogleIdToken(idToken).getOrThrow() }
    fun refreshPantries() = action {
        _isRestoringPantries.value = true
        try {
            pantriesRepository.refreshPantries()
        } finally {
            _isRestoringPantries.value = false
        }
    }
    fun signOut() = action {
        sync.synchronize()
        require(!sync.hasUnsyncedChanges()) { "Odjava nije moguća dok postoje nesinkronizirane promjene ili konflikti." }
        runCatching { deviceRegistration.unregisterCurrentDevice() }
            .getOrElse { throw IllegalStateException("Uređaj nije sigurno odjavljen. Provjerite mrežu i pokušajte ponovno.") }
        sync.stopRealtime()
        sessions.signOut()
        selectedPantryId.value = null
    }
    fun renameDevice(name: String) = action {
        deviceIdentity.displayName = name
        deviceRegistration.registerCurrentToken()
    }
    fun updateNotificationPrivacy(mode: NotificationPrivacyMode) {
        if (_notificationPrivacyUpdating.value || mode == _notificationPrivacyMode.value) return
        viewModelScope.launch {
            _notificationPrivacyUpdating.value = true
            runCatching { deviceRegistration.updateNotificationPrivacy(mode) }
                .onSuccess {
                    _notificationPrivacyMode.value = mode
                    _messages.emit(
                        if (mode == NotificationPrivacyMode.DETAILED) {
                            "Detaljne obavijesti su uključene na ovom uređaju."
                        } else {
                            "Privatne obavijesti su uključene na ovom uređaju."
                        },
                    )
                }
                .onFailure {
                    _messages.emit("Postavku obavijesti nije moguće spremiti. Provjerite mrežu i pokušajte ponovno.")
                }
            _notificationPrivacyUpdating.value = false
        }
    }
    fun createPantry(name: String, deviceName: String) {
        deviceIdentity.displayName = deviceName
        action {
            deviceRegistration.registerCurrentToken()
            selectPantry(pantriesRepository.createPantry(name, deviceIdentity.deviceId).id)
        }
    }
    fun joinPantry(code: String, deviceName: String) {
        deviceIdentity.displayName = deviceName
        action {
            deviceRegistration.registerCurrentToken()
            selectPantry(pantriesRepository.joinPantry(code, deviceIdentity.deviceId).id)
        }
    }
    fun createShelf(name: String) = withActor { pantry, uid -> inventory.createShelf(pantry.id, name, uid, deviceIdentity.displayName) }
    fun renameShelf(shelf: Shelf, name: String) = withActor { _, uid -> inventory.renameShelf(shelf, name, uid, deviceIdentity.displayName) }
    fun reorderShelves(ordered: List<Shelf>) = withActor { pantry, uid ->
        inventory.reorderShelves(pantry.id, ordered.map(Shelf::id), pantry.revision, uid, deviceIdentity.displayName)
    }
    fun moveAllStock(fromShelfId: String, toShelfId: String, values: List<ProductWithStock>) = withActor { _, uid ->
        require(fromShelfId != toShelfId) { "Odaberite drugu policu." }
        values.forEach { item ->
            val quantity = item.stocks.firstOrNull { it.shelfId == fromShelfId }?.quantity ?: 0
            if (quantity > 0) inventory.moveStock(item.product.id, fromShelfId, toShelfId, quantity, uid, deviceIdentity.displayName)
        }
    }
    fun deleteShelf(shelf: Shelf) = withActor { _, uid -> inventory.deleteShelf(shelf, uid, deviceIdentity.displayName) }
    fun saveProduct(
        product: Product,
        photoPath: String? = null,
        source: hr.smocnica.core.model.PhotoSource? = null,
        onSaved: ((Product) -> Unit)? = null,
        onFailure: (() -> Unit)? = null,
    ) = actorAction({ pantry, uid ->
        val saved = inventory.upsertProduct(product.copy(pantryId = pantry.id), uid, deviceIdentity.displayName)
        if (photoPath != null && source != null) {
            val initialSync = sync.synchronize()
            require(initialSync.failed == 0 && initialSync.conflicts == 0) { "Artikl mora biti sinkroniziran prije prijenosa fotografije." }
            val url = photos.uploadJpeg(pantry.id, saved.id, photoPath)
            inventory.upsertProduct(saved.copy(photoUri = url, photoSource = source), uid, deviceIdentity.displayName)
        } else saved
    }, { saved -> onSaved?.invoke(saved) }, { onFailure?.invoke() })
    fun createProductAndStock(
        product: Product,
        shelfId: String,
        quantity: Int,
        photoPath: String? = null,
        source: hr.smocnica.core.model.PhotoSource? = null,
        onCreated: ((Product) -> Unit)? = null,
        onFailure: (() -> Unit)? = null,
    ) = actorAction({ pantry, uid ->
        val created = inventory.upsertProduct(product.copy(pantryId = pantry.id), uid, deviceIdentity.displayName)
        if (quantity > 0) inventory.adjustStock(created.id, shelfId, quantity, uid, deviceIdentity.displayName)
        if (photoPath != null && source != null) {
            val initialSync = sync.synchronize()
            require(initialSync.failed == 0 && initialSync.conflicts == 0) { "Artikl mora biti sinkroniziran prije prijenosa fotografije." }
            val url = photos.uploadJpeg(pantry.id, created.id, photoPath)
            inventory.upsertProduct(created.copy(photoUri = url, photoSource = source), uid, deviceIdentity.displayName)
        }
        created
    }, { created -> onCreated?.invoke(created) }, { onFailure?.invoke() })
    fun deleteProduct(product: Product) = withActor { _, uid -> inventory.deleteProduct(product, uid, deviceIdentity.displayName) }
    fun adjustStock(
        productId: String,
        shelfId: String,
        delta: Int,
        onAdjusted: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null,
    ) = actorAction({ _, uid ->
        inventory.adjustStock(productId, shelfId, delta, uid, deviceIdentity.displayName)
    }, { onAdjusted?.invoke() }, { onFailure?.invoke() })
    fun restoreProductAndAddStock(
        productId: String,
        shelfId: String,
        quantity: Int,
        onRestored: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null,
    ) = actorAction({ pantry, uid ->
        inventory.restoreProductAndAdjustStock(pantry.id, productId, shelfId, quantity, uid, deviceIdentity.displayName)
    }, { onRestored?.invoke() }, { onFailure?.invoke() })

    fun undoRestoreProductAndAddStock(product: Product, shelfId: String, quantity: Int) = withActor { _, uid ->
        inventory.adjustStock(product.id, shelfId, -quantity, uid, deviceIdentity.displayName)
        inventory.deleteProduct(product.copy(deletedAt = null, purgeAfter = null), uid, deviceIdentity.displayName)
    }
    fun moveStock(productId: String, fromShelfId: String, toShelfId: String, quantity: Int, onMoved: (() -> Unit)? = null) = actorAction({ _, uid ->
        inventory.moveStock(productId, fromShelfId, toShelfId, quantity, uid, deviceIdentity.displayName)
    }, { onMoved?.invoke() })
    fun changeProductsCategory(products: List<ProductWithStock>, category: Category) = withActor { _, uid ->
        val now = System.currentTimeMillis()
        products.forEach { item ->
            inventory.upsertProduct(
                item.product.copy(category = category.name, categoryId = category.id, updatedAt = now),
                uid,
                deviceIdentity.displayName,
            )
        }
    }
    fun addProductsToShopping(products: List<ProductWithStock>) = withActor { pantry, uid ->
        products.forEach { item ->
            inventory.addManualShoppingItem(pantry.id, item.product.name, item.product.category, 1, uid, deviceIdentity.displayName)
        }
    }
    fun deleteProducts(products: List<ProductWithStock>) = withActor { _, uid ->
        products.forEach { item -> inventory.deleteProduct(item.product, uid, deviceIdentity.displayName) }
    }
    fun moveProducts(products: List<ProductWithStock>, fromShelfId: String, toShelfId: String) = withActor { _, uid ->
        require(fromShelfId != toShelfId) { "Odaberite različite police." }
        products.forEach { item ->
            val quantity = item.stocks.firstOrNull { it.shelfId == fromShelfId }?.quantity ?: 0
            if (quantity > 0) inventory.moveStock(item.product.id, fromShelfId, toShelfId, quantity, uid, deviceIdentity.displayName)
        }
    }
    fun addShopping(name: String, category: String, quantity: Int) = withActor { pantry, uid -> inventory.addManualShoppingItem(pantry.id, name, category, quantity, uid, deviceIdentity.displayName) }
    fun setChecked(item: ShoppingItem, checked: Boolean) = withActor { _, uid -> inventory.setShoppingChecked(item, checked, uid, deviceIdentity.displayName) }
    fun saveCategory(category: Category) = withActor { pantry, uid -> inventory.upsertCategory(category.copy(pantryId = pantry.id), uid, deviceIdentity.displayName) }
    fun reorderCategories(ordered: List<Category>) = withActor { pantry, uid ->
        inventory.reorderCategories(pantry.id, ordered.map(Category::id), pantry.revision, uid, deviceIdentity.displayName)
    }
    fun deleteCategory(category: Category, replacementId: String) = withActor { _, uid -> inventory.deleteCategory(category, replacementId, uid, deviceIdentity.displayName) }
    fun updateManualShopping(item: ShoppingItem, name: String, category: String, quantity: Int) = withActor { _, uid ->
        inventory.updateManualShoppingItem(item, name, category, quantity, uid, deviceIdentity.displayName)
    }
    fun deleteManualShopping(item: ShoppingItem, onDeleted: (ShoppingItem) -> Unit) = actorAction(
        block = { _, uid -> inventory.deleteManualShoppingItem(item, uid, deviceIdentity.displayName) },
        onSuccess = onDeleted,
    )
    fun restoreManualShopping(item: ShoppingItem) = withActor { pantry, uid ->
        inventory.addManualShoppingItem(
            pantry.id,
            item.name,
            item.category,
            item.requiredQuantity,
            uid,
            deviceIdentity.displayName,
            checked = item.checked,
        )
    }

    suspend fun previewInventory(shelfId: String, counts: Map<String, Int>): InventorySession {
        inventoryDraftJob?.cancel()
        val pantry = selectedPantry.value ?: error("Smočnica nije odabrana.")
        val uid = session.value?.uid ?: error("Korisnik nije prijavljen.")
        return inventory.previewInventory(pantry.id, shelfId, counts, uid, deviceIdentity.displayName)
    }

    fun persistInventoryDraft(shelfId: String, counts: Map<String, Int>) {
        inventoryDraftJob?.cancel()
        inventoryDraftJob = viewModelScope.launch {
            delay(100)
            val pantry = selectedPantry.value ?: return@launch
            val uid = session.value?.uid ?: return@launch
            runCatching { inventory.previewInventory(pantry.id, shelfId, counts, uid, deviceIdentity.displayName) }
                .onFailure { _messages.emit(it.message ?: "Nacrt inventure nije spremljen.") }
        }
    }

    fun discardInventoryDraft(id: String) = action { inventoryDraftJob?.cancel(); inventory.discardInventoryDraft(id) }

    fun applyInventory(session: InventorySession) = withActor { _, uid -> inventoryDraftJob?.cancel(); inventory.applyInventory(session, uid, deviceIdentity.displayName) }

    fun synchronize() = action {
        val result = sync.synchronize()
        if (result.conflicts > 0) _messages.emit("Potrebno je riješiti ${result.conflicts} konflikata sinkronizacije.")
    }

    fun resolveConflict(operationId: String, keepLocal: Boolean) = action {
        sync.resolveConflict(operationId, keepLocal)
        if (keepLocal) synchronize()
    }

    fun createInvitation() = action {
        val pantry = selectedPantry.value ?: error("Smočnica nije odabrana.")
        _invitation.value = pantriesRepository.createInvitation(pantry.id)
    }

    fun removeMember(uid: String) = action {
        pantriesRepository.removeMember(selectedPantry.value?.id ?: error("Smočnica nije odabrana."), uid, deviceIdentity.deviceId, deviceIdentity.displayName)
    }

    fun transferOwnership(uid: String) = action {
        pantriesRepository.transferOwnership(selectedPantry.value?.id ?: error("Smočnica nije odabrana."), uid, deviceIdentity.deviceId, deviceIdentity.displayName)
    }

    fun deletePantry() = action {
        val pantryId = selectedPantry.value?.id ?: error("Smočnica nije odabrana.")
        pantriesRepository.deletePantry(pantryId, deviceIdentity.deviceId, deviceIdentity.displayName)
        sync.stopRealtime()
        selectedPantryId.value = null
    }

    suspend fun exportJson(): ByteArray = backup.exportJson(selectedPantry.value?.id ?: error("Smočnica nije odabrana."))
    suspend fun exportCsv(): ByteArray = backup.exportCsv(selectedPantry.value?.id ?: error("Smočnica nije odabrana."))
    suspend fun previewImport(bytes: ByteArray): ImportPreview = backup.previewImport(bytes)
    fun importBackup(preview: ImportPreview, strategy: ImportStrategy) = withActor { pantry, uid -> backup.import(preview, strategy, pantry.id, uid, deviceIdentity.displayName) }

    fun checkUpdate(currentVersionCode: Long) = action {
        _latestUpdate.value = updates.check(currentVersionCode)
        if (_latestUpdate.value == null) _messages.emit("Koristite najnoviju verziju.")
    }

    fun downloadUpdate(update: AppUpdate) = action { _verifiedApk.emit(updates.downloadAndVerify(update)) }

    fun restoreTrash(item: TrashItem) = withActor { pantry, uid ->
        inventory.restore(pantry.id, item.type.name, item.id, uid, deviceIdentity.displayName)
    }

    fun purgeTrash(item: TrashItem) = action {
        trash.purgeNow(selectedPantry.value?.id ?: error("Smočnica nije odabrana."), item)
    }

    private fun withActor(block: suspend (Pantry, String) -> Any?) = action {
        val pantry = selectedPantry.value ?: error("Smočnica nije odabrana.")
        val uid = session.value?.uid ?: error("Korisnik nije prijavljen.")
        block(pantry, uid)
        val result = sync.synchronize()
        if (result.conflicts > 0) _messages.emit("Potrebno je riješiti ${result.conflicts} konflikata sinkronizacije.")
    }

    private fun <T> actorAction(
        block: suspend (Pantry, String) -> T,
        onSuccess: (T) -> Unit,
        onFailure: (Throwable) -> Unit = {},
    ) {
        viewModelScope.launch {
            val pantry = selectedPantry.value ?: run {
                val failure = IllegalStateException("Smočnica nije odabrana.")
                onFailure(failure)
                _messages.emit(failure.message.orEmpty())
                return@launch
            }
            val uid = session.value?.uid ?: run {
                val failure = IllegalStateException("Korisnik nije prijavljen.")
                onFailure(failure)
                _messages.emit(failure.message.orEmpty())
                return@launch
            }
            runCatching { block(pantry, uid) }
                .onSuccess { value ->
                    onSuccess(value)
                    runCatching { sync.synchronize() }
                        .onSuccess { result ->
                            if (result.conflicts > 0) _messages.emit("Potrebno je riješiti ${result.conflicts} konflikata sinkronizacije.")
                        }
                        .onFailure { _messages.emit("Promjena je spremljena na uređaju i čeka sinkronizaciju.") }
                }
                .onFailure {
                    onFailure(it)
                    _messages.emit(it.message ?: "Radnja nije uspjela.")
                }
        }
    }

    private fun action(block: suspend () -> Any?) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { _messages.emit(it.message ?: "Radnja nije uspjela.") }
        }
    }

    override fun onCleared() {
        sync.stopRealtime()
        super.onCleared()
    }
}
