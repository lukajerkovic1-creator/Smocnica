package hr.smocnica.core.data.repository

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import dagger.hilt.android.qualifiers.ApplicationContext
import hr.smocnica.core.data.local.ActivityEntity
import hr.smocnica.core.data.local.CategoryEntity
import hr.smocnica.core.data.local.MemberEntity
import hr.smocnica.core.data.local.PantryEntity
import hr.smocnica.core.data.local.ProductEntity
import hr.smocnica.core.data.local.ShelfEntity
import hr.smocnica.core.data.local.ShoppingEntity
import hr.smocnica.core.data.local.SmocnicaDatabase
import hr.smocnica.core.data.local.StockEntity
import hr.smocnica.core.data.local.searchKey
import hr.smocnica.core.model.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealtimePantrySynchronizer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: SmocnicaDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registrations = mutableListOf<ListenerRegistration>()
    private var activePantryId: String? = null

    @Synchronized
    fun start(pantryId: String) {
        if (activePantryId == pantryId && registrations.isNotEmpty()) return
        stop()
        if (FirebaseApp.getApps(context).isEmpty()) return
        activePantryId = pantryId
        val pantry = FirebaseFirestore.getInstance().collection("pantries").document(pantryId)
        registrations += pantry.addSnapshotListener { snapshot, error ->
            if (error == null && snapshot?.exists() == true) scope.launch { cachePantry(snapshot) }
        }
        registrations += pantry.collection("members").addSnapshotListener { snapshot, error ->
            if (error == null && snapshot != null) scope.launch {
                database.memberDao().upsertAll(snapshot.documents.map { it.member(pantryId) })
            }
        }
        registrations += pantry.collection("shelves").addSnapshotListener { snapshot, error ->
            if (error == null && snapshot != null) scope.launch {
                snapshot.documents.forEach { document ->
                    val remote = document.shelf(pantryId)
                    val local = database.shelfDao().get(remote.id)
                    if (local == null || local.syncState == SyncState.SYNCED) database.shelfDao().upsert(remote)
                }
                if (!snapshot.metadata.isFromCache) {
                    val remoteIds = snapshot.documents.map { it.id }.toSet()
                    database.shelfDao().listAll(pantryId).filter { it.syncState == SyncState.SYNCED && it.id !in remoteIds }
                        .forEach { database.shelfDao().deleteHard(it.id) }
                }
            }
        }
        registrations += pantry.collection("categories").addSnapshotListener { snapshot, error ->
            if (error == null && snapshot != null) scope.launch {
                snapshot.documents.forEach { document ->
                    val remote = document.category(pantryId)
                    val local = database.categoryDao().get(remote.id)
                    if (local == null || local.syncState == SyncState.SYNCED) database.categoryDao().upsert(remote)
                }
                if (!snapshot.metadata.isFromCache) {
                    val remoteIds = snapshot.documents.map { it.id }.toSet()
                    database.categoryDao().listAll(pantryId).filter { it.syncState == SyncState.SYNCED && it.id !in remoteIds }
                        .forEach { database.categoryDao().deleteHard(it.id) }
                }
            }
        }
        registrations += pantry.collection("products").addSnapshotListener { snapshot, error ->
            if (error == null && snapshot != null) scope.launch {
                snapshot.documents.forEach { document ->
                    val remote = document.product(pantryId)
                    val local = database.productDao().get(remote.id)
                    if (local == null || local.syncState == SyncState.SYNCED) database.productDao().upsert(remote)
                }
                if (!snapshot.metadata.isFromCache) {
                    val remoteIds = snapshot.documents.map { it.id }.toSet()
                    database.productDao().listAll(pantryId).filter { it.syncState == SyncState.SYNCED && it.id !in remoteIds }
                        .forEach { database.productDao().deleteHard(it.id) }
                }
            }
        }
        registrations += pantry.collection("stocks").addSnapshotListener { snapshot, error ->
            if (error == null && snapshot != null) scope.launch {
                snapshot.documents.forEach { document ->
                    val remote = document.stock(pantryId)
                    val local = database.stockDao().get(remote.productId, remote.shelfId)
                    if (local == null || local.syncState == SyncState.SYNCED) database.stockDao().upsert(remote)
                }
                if (!snapshot.metadata.isFromCache) {
                    val remoteIds = snapshot.documents.map { it.id }.toSet()
                    database.stockDao().listForPantry(pantryId).filter {
                        it.syncState == SyncState.SYNCED && "${it.productId}_${it.shelfId}" !in remoteIds
                    }.forEach { database.stockDao().deleteHard(pantryId, it.productId, it.shelfId) }
                }
            }
        }
        registrations += pantry.collection("shoppingItems").addSnapshotListener { snapshot, error ->
            if (error == null && snapshot != null) scope.launch {
                snapshot.documents.forEach { document ->
                    val remote = document.shopping(pantryId)
                    val local = database.shoppingDao().get(remote.id)
                    if (local == null || local.syncState == SyncState.SYNCED) database.shoppingDao().upsert(remote)
                }
                if (!snapshot.metadata.isFromCache) {
                    val remoteIds = snapshot.documents.map { it.id }.toSet()
                    database.shoppingDao().listAll(pantryId).filter { it.syncState == SyncState.SYNCED && it.id !in remoteIds }
                        .forEach { database.shoppingDao().deleteHard(it.id) }
                }
            }
        }
        registrations += pantry.collection("activities").addSnapshotListener { snapshot, error ->
            if (error == null && snapshot != null) scope.launch {
                database.activityDao().insertAll(snapshot.documents.map { it.activity(pantryId) })
            }
        }
    }

    @Synchronized
    fun stop() {
        registrations.forEach(ListenerRegistration::remove)
        registrations.clear()
        activePantryId = null
    }

    fun refresh(pantryId: String) {
        stop()
        start(pantryId)
    }

    private suspend fun cachePantry(document: DocumentSnapshot) {
        val remote = PantryEntity(
            id = document.id,
            name = document.string("name", "Smočnica"),
            ownerUid = document.string("ownerUid"),
            revision = document.long("revision"),
            createdAt = document.epoch("createdAt"),
            updatedAt = document.epoch("updatedAt"),
            deletedAt = document.epochOrNull("deletedAt"),
            purgeAfter = document.epochOrNull("purgeAfter"),
            syncState = SyncState.SYNCED,
        )
        val local = database.pantryDao().get(remote.id)
        if (local == null || local.syncState == SyncState.SYNCED) database.pantryDao().upsert(remote)
    }

    private fun DocumentSnapshot.member(pantryId: String) = MemberEntity(
        pantryId, id, string("displayName", "Član"), getString("photoUrl"),
        string("role", "MEMBER"), epoch("joinedAt"), getBoolean("active") ?: true,
    )

    private fun DocumentSnapshot.shelf(pantryId: String) = ShelfEntity(
        id, pantryId, string("name"), long("sortOrder").toInt(), long("revision"),
        epoch("createdAt"), epoch("updatedAt"), epochOrNull("deletedAt"), epochOrNull("purgeAfter"), SyncState.SYNCED,
    )

    private fun DocumentSnapshot.category(pantryId: String) = CategoryEntity(
        id, pantryId, string("name"), long("sortOrder").toInt(), getBoolean("isDefault") ?: false,
        long("revision"), epochOrNull("deletedAt"), epochOrNull("purgeAfter"), SyncState.SYNCED,
    )

    private fun DocumentSnapshot.product(pantryId: String) = ProductEntity(
        id = id,
        pantryId = pantryId,
        name = string("name"),
        normalizedName = string("name").searchKey(),
        barcode = getString("barcode"),
        description = string("description", ""),
        category = string("category", "Ostalo"),
        categoryId = getString("categoryId"),
        photoUri = getString("photoUrl") ?: getString("photoPath"),
        photoSource = string("photoSource", "NONE"),
        minimumQuantity = long("minimumQuantity").toInt(),
        autoShopping = getBoolean("autoShopping") ?: true,
        revision = long("revision"),
        createdAt = epoch("createdAt"),
        updatedAt = epoch("updatedAt"),
        deletedAt = epochOrNull("deletedAt"),
        purgeAfter = epochOrNull("purgeAfter"),
        syncState = SyncState.SYNCED,
    )

    private fun DocumentSnapshot.stock(pantryId: String) = StockEntity(
        pantryId, string("productId"), string("shelfId"), long("quantity").toInt(),
        long("revision"), epoch("updatedAt"), SyncState.SYNCED,
    )

    private fun DocumentSnapshot.shopping(pantryId: String) = ShoppingEntity(
        id, pantryId, getString("productId"), string("name"), string("category", "Ostalo"),
        long("requiredQuantity").toInt(), getBoolean("checked") ?: false, getBoolean("manual") ?: false,
        long("revision"), epoch("createdAt"), epoch("updatedAt"), epochOrNull("deletedAt"), SyncState.SYNCED,
    )

    private fun DocumentSnapshot.activity(pantryId: String) = ActivityEntity(
        id = id,
        pantryId = pantryId,
        type = string("type"),
        aggregateId = string("aggregateId"),
        displayLabel = string("displayLabel", "Aktivnost"),
        quantityDelta = (get("quantityDelta") as? Number)?.toInt(),
        actorUid = string("actorUid"),
        deviceId = string("deviceId"),
        deviceName = string("deviceDisplayName", "Android uređaj"),
        oldValue = getString("oldValue"),
        newValue = getString("newValue"),
        createdAt = epoch("createdAt"),
        productId = getString("productId"),
        shelfId = getString("shelfId"),
        fromShelfId = getString("fromShelfId"),
        toShelfId = getString("toShelfId"),
    )

    private fun DocumentSnapshot.string(name: String, fallback: String? = null): String =
        getString(name) ?: fallback ?: error("Nedostaje udaljeno polje $name.")

    private fun DocumentSnapshot.long(name: String): Long = (get(name) as? Number)?.toLong() ?: 0L

    private fun DocumentSnapshot.epoch(name: String): Long = epochOrNull(name) ?: System.currentTimeMillis()

    private fun DocumentSnapshot.epochOrNull(name: String): Long? = when (val value = get(name)) {
        is Timestamp -> value.toDate().time
        is Number -> value.toLong()
        else -> null
    }
}
