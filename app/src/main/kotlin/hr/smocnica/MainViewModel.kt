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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import javax.inject.Inject

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

    val selectedPantry: StateFlow<Pantry?> = selectedPantryId.flatMapLatest { id ->
        pantriesRepository.observePantries().flatMapLatest { values -> flowOf(values.firstOrNull { it.id == id } ?: values.firstOrNull()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val products = selectedPantry.flatMapLatest { pantry ->
        if (pantry == null) flowOf(emptyList()) else filter.flatMapLatest { inventory.observeProducts(pantry.id, it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<ProductWithStock>())

    val allProducts = selectedPantry.flatMapLatest { pantry ->
        pantry?.let { inventory.observeProducts(it.id) } ?: flowOf(emptyList())
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
        pantry?.let { inventory.observeActivities(it.id, System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1_000) } ?: flowOf(emptyList())
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
            session.filterNotNull().collectLatest {
                _isRestoringPantries.value = true
                runCatching { pantriesRepository.refreshPantries() }
                    .onFailure { _messages.emit(it.message ?: "Postojeću smočnicu nije moguće dohvatiti.") }
                runCatching { deviceRegistration.registerCurrentToken() }
                _isRestoringPantries.value = false
            }
        }
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
    fun createPantry(name: String, deviceName: String) {
        deviceIdentity.displayName = deviceName
        action { selectPantry(pantriesRepository.createPantry(name, deviceIdentity.displayName).id) }
    }
    fun joinPantry(code: String, deviceName: String) {
        deviceIdentity.displayName = deviceName
        action { selectPantry(pantriesRepository.joinPantry(code, deviceIdentity.displayName).id) }
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
    fun saveProduct(product: Product, photo: ByteArray? = null, source: hr.smocnica.core.model.PhotoSource? = null) = withActor { pantry, uid ->
        val saved = inventory.upsertProduct(product.copy(pantryId = pantry.id), uid, deviceIdentity.displayName)
        if (photo != null && source != null) {
            val initialSync = sync.synchronize()
            require(initialSync.failed == 0 && initialSync.conflicts == 0) { "Artikl mora biti sinkroniziran prije prijenosa fotografije." }
            val url = photos.uploadJpeg(pantry.id, saved.id, photo)
            inventory.upsertProduct(saved.copy(photoUri = url, photoSource = source), uid, deviceIdentity.displayName)
        }
    }
    fun createProductAndStock(product: Product, shelfId: String, quantity: Int, photo: ByteArray? = null, source: hr.smocnica.core.model.PhotoSource? = null) = withActor { pantry, uid ->
        val created = inventory.upsertProduct(product.copy(pantryId = pantry.id), uid, deviceIdentity.displayName)
        if (quantity > 0) inventory.adjustStock(created.id, shelfId, quantity, uid, deviceIdentity.displayName)
        if (photo != null && source != null) {
            val initialSync = sync.synchronize()
            require(initialSync.failed == 0 && initialSync.conflicts == 0) { "Artikl mora biti sinkroniziran prije prijenosa fotografije." }
            val url = photos.uploadJpeg(pantry.id, created.id, photo)
            inventory.upsertProduct(created.copy(photoUri = url, photoSource = source), uid, deviceIdentity.displayName)
        }
    }
    fun deleteProduct(product: Product) = withActor { _, uid -> inventory.deleteProduct(product, uid, deviceIdentity.displayName) }
    fun adjustStock(productId: String, shelfId: String, delta: Int) = withActor { _, uid -> inventory.adjustStock(productId, shelfId, delta, uid, deviceIdentity.displayName) }
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
