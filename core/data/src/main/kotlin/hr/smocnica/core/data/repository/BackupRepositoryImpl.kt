package hr.smocnica.core.data.repository

import androidx.room.withTransaction
import hr.smocnica.core.data.Clock
import hr.smocnica.core.data.DeviceIdentity
import hr.smocnica.core.data.IdGenerator
import hr.smocnica.core.data.local.ActivityEntity
import hr.smocnica.core.data.local.PendingOperationEntity
import hr.smocnica.core.data.local.SmocnicaDatabase
import hr.smocnica.core.data.local.entity
import hr.smocnica.core.data.local.model
import hr.smocnica.core.domain.BackupRepository
import hr.smocnica.core.domain.BarcodePolicy
import hr.smocnica.core.domain.ImportPreview
import hr.smocnica.core.domain.ImportStrategy
import hr.smocnica.core.model.ActivityType
import hr.smocnica.core.model.AggregateType
import hr.smocnica.core.model.OperationPayload
import hr.smocnica.core.model.OperationState
import hr.smocnica.core.model.PantrySnapshot
import hr.smocnica.core.model.SyncState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class BackupEnvelope(
    val schemaVersion: Int,
    val exportedAt: Long,
    val checksumSha256: String,
    val snapshot: PantrySnapshot,
)

@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val database: SmocnicaDatabase,
    private val json: Json,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val deviceIdentity: DeviceIdentity,
) : BackupRepository {
    override suspend fun exportJson(pantryId: String): ByteArray {
        val snapshot = snapshot(pantryId)
        val payload = json.encodeToString(PantrySnapshot.serializer(), snapshot)
        return json.encodeToString(
            BackupEnvelope.serializer(),
            BackupEnvelope(SCHEMA_VERSION, clock.now(), sha256(payload.toByteArray(Charsets.UTF_8)), snapshot),
        ).toByteArray(Charsets.UTF_8)
    }

    override suspend fun exportCsv(pantryId: String): ByteArray {
        val snapshot = snapshot(pantryId)
        val stocksByProduct = snapshot.stocks.groupBy { it.productId }
        val shelvesById = snapshot.shelves.associateBy { it.id }
        val shoppingProducts = snapshot.shoppingItems.mapNotNull { it.productId }.toSet()
        val rows = buildList {
            add(listOf("Naziv", "Barkod", "Pakiranje", "Kategorija", "Ukupna količina", "Minimalna količina", "Police", "Stanje kupnje"))
            snapshot.products.sortedBy { it.name.lowercase() }.forEach { product ->
                val productStocks = stocksByProduct[product.id].orEmpty().filter { it.quantity > 0 }
                add(
                    listOf(
                        product.name,
                        product.barcode.orEmpty(),
                        product.description,
                        product.category,
                        productStocks.sumOf { it.quantity }.toString(),
                        product.minimumQuantity.toString(),
                        productStocks.joinToString(" | ") { "${shelvesById[it.shelfId]?.name ?: it.shelfId}: ${it.quantity}" },
                        if (product.id in shoppingProducts) "Na popisu" else "Nije na popisu",
                    ),
                )
            }
        }
        val csv = rows.joinToString("\r\n") { row -> row.joinToString(";") { csvCell(it) } } + "\r\n"
        return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + csv.toByteArray(Charsets.UTF_8)
    }

    override suspend fun previewImport(bytes: ByteArray): ImportPreview {
        require(bytes.size in 2..MAX_IMPORT_BYTES) { "JSON sigurnosna kopija mora biti manja od 20 MiB." }
        val envelope = runCatching {
            json.decodeFromString(BackupEnvelope.serializer(), bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
        }.getOrElse { throw IllegalArgumentException("Datoteka nije valjana sigurnosna kopija Smočnice.", it) }
        require(envelope.schemaVersion in 1..SCHEMA_VERSION) { "Verzija sigurnosne kopije ${envelope.schemaVersion} nije podržana." }
        val payload = json.encodeToString(PantrySnapshot.serializer(), envelope.snapshot)
        require(sha256(payload.toByteArray(Charsets.UTF_8)).equals(envelope.checksumSha256, ignoreCase = true)) {
            "Kontrolni sažetak sigurnosne kopije nije ispravan. Datoteka je možda oštećena."
        }
        val conflicts = buildList {
            addAll(validateSnapshot(envelope.snapshot))
            val duplicateBarcodes = envelope.snapshot.products.mapNotNull { it.barcode }
                .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (duplicateBarcodes.isNotEmpty()) add("Više artikala koristi isti barkod (${duplicateBarcodes.size}).")
            val shelfIds = envelope.snapshot.shelves.map { it.id }.toSet()
            val orphanStocks = envelope.snapshot.stocks.count { it.shelfId !in shelfIds }
            if (orphanStocks > 0) add("$orphanStocks lokacija nema postojeću policu.")
        }
        return ImportPreview(
            schemaVersion = envelope.schemaVersion,
            pantryName = envelope.snapshot.pantry.name,
            shelfCount = envelope.snapshot.shelves.size,
            productCount = envelope.snapshot.products.size,
            shoppingCount = envelope.snapshot.shoppingItems.size,
            snapshot = envelope.snapshot,
            conflicts = conflicts,
        )
    }

    override suspend fun import(preview: ImportPreview, strategy: ImportStrategy, targetPantryId: String, actorUid: String, deviceName: String) {
        require(preview.conflicts.isEmpty()) { "Razriješite prikazane konflikte prije uvoza." }
        require(validateSnapshot(preview.snapshot).isEmpty()) { "Sigurnosna kopija više nije valjana za uvoz." }
        database.withTransaction {
            val source = preview.snapshot
            val localPantry = database.pantryDao().get(targetPantryId)
                ?: error("Najprije se prijavite i otvorite odgovarajuću zajedničku smočnicu.")
            val now = clock.now()
            val incoming = source.copy(
                pantry = source.pantry.copy(id = targetPantryId, ownerUid = localPantry.ownerUid),
                members = source.members.map { it.copy(pantryId = targetPantryId) },
                shelves = source.shelves.map { it.copy(pantryId = targetPantryId) },
                categories = source.categories.map { it.copy(pantryId = targetPantryId) },
                products = source.products.map {
                    val keepPublicPhoto = it.photoSource == hr.smocnica.core.model.PhotoSource.OPEN_FOOD_FACTS &&
                        it.photoUri?.startsWith("https://") == true
                    it.copy(
                        pantryId = targetPantryId,
                        photoUri = it.photoUri.takeIf { keepPublicPhoto },
                        photoSource = if (keepPublicPhoto) it.photoSource else hr.smocnica.core.model.PhotoSource.NONE,
                    )
                },
                stocks = source.stocks.map { it.copy(pantryId = targetPantryId) },
                shoppingItems = source.shoppingItems.map { it.copy(pantryId = targetPantryId) },
                activities = source.activities.map { it.copy(pantryId = targetPantryId) },
            )
            if (strategy == ImportStrategy.REPLACE) softDeleteMissing(incoming, now)

            database.shelfDao().upsertAll(incoming.shelves.map { it.copy(pantryId = localPantry.id, syncState = SyncState.PENDING).entity() })
            database.categoryDao().upsertAll(incoming.categories.map { it.copy(pantryId = localPantry.id, syncState = SyncState.PENDING).entity() })
            database.productDao().upsertAll(incoming.products.map { it.copy(pantryId = localPantry.id, syncState = SyncState.PENDING).entity() })
            database.stockDao().upsertAll(incoming.stocks.map { it.copy(pantryId = localPantry.id, syncState = SyncState.PENDING).entity() })
            database.shoppingDao().upsertAll(incoming.shoppingItems.map { it.copy(pantryId = localPantry.id, syncState = SyncState.PENDING).entity() })
            database.activityDao().insertAll(incoming.activities.map { it.copy(pantryId = localPantry.id).entity() })

            val normalizedSnapshot = incoming
            val operationId = ids.next()
            database.operationDao().insert(
                PendingOperationEntity(
                    operationId = operationId,
                    pantryId = localPantry.id,
                    aggregateType = AggregateType.PANTRY.name,
                    aggregateId = localPantry.id,
                    baseRevision = localPantry.revision,
                    payloadJson = json.encodeToString(
                        OperationPayload.serializer(),
                        OperationPayload.ImportSnapshot(normalizedSnapshot, strategy == ImportStrategy.REPLACE),
                    ),
                    actorUid = actorUid,
                    deviceId = deviceIdentity.deviceId,
                    deviceName = deviceName,
                    createdAt = now,
                    attempts = 0,
                    state = OperationState.PENDING,
                    errorCode = null,
                ),
            )
            database.activityDao().insert(
                ActivityEntity(
                    id = operationId, pantryId = localPantry.id, type = ActivityType.IMPORT_APPLIED.name,
                    aggregateId = localPantry.id, displayLabel = "Uvezena sigurnosna kopija",
                    quantityDelta = null, actorUid = actorUid, deviceId = deviceIdentity.deviceId,
                    deviceName = deviceName, oldValue = null, newValue = strategy.name, createdAt = now,
                ),
            )
        }
    }

    private suspend fun snapshot(pantryId: String): PantrySnapshot {
        val pantry = database.pantryDao().get(pantryId)?.model() ?: error("Smočnica nije pronađena.")
        return PantrySnapshot(
            pantry = pantry,
            members = database.memberDao().listActive(pantryId).map { it.model() },
            shelves = database.shelfDao().listActive(pantryId).map { it.model() },
            categories = database.categoryDao().listActive(pantryId).map { it.model() },
            products = database.productDao().listActive(pantryId).map { it.model() },
            stocks = database.stockDao().listForPantry(pantryId).map { it.model() },
            shoppingItems = database.shoppingDao().listActive(pantryId).map { it.model() },
            activities = database.activityDao().listSince(pantryId, clock.now() - TWELVE_MONTHS).map { it.model() },
        )
    }

    private suspend fun softDeleteMissing(incoming: PantrySnapshot, now: Long) {
        val pantryId = incoming.pantry.id
        val shelfIds = incoming.shelves.map { it.id }.toSet()
        database.shelfDao().listActive(pantryId).filter { it.id !in shelfIds }.forEach {
            database.shelfDao().upsert(it.copy(deletedAt = now, purgeAfter = now + THIRTY_DAYS, syncState = SyncState.PENDING))
        }
        val categoryIds = incoming.categories.map { it.id }.toSet()
        database.categoryDao().listActive(pantryId).filter { it.id !in categoryIds }.forEach {
            database.categoryDao().upsert(it.copy(deletedAt = now, purgeAfter = now + THIRTY_DAYS, syncState = SyncState.PENDING))
        }
        val productIds = incoming.products.map { it.id }.toSet()
        database.productDao().listActive(pantryId).filter { it.id !in productIds }.forEach {
            database.productDao().upsert(it.copy(deletedAt = now, purgeAfter = now + THIRTY_DAYS, updatedAt = now, syncState = SyncState.PENDING))
        }
        val stockKeys = incoming.stocks.map { it.productId to it.shelfId }.toSet()
        database.stockDao().listForPantry(pantryId)
            .filter { it.productId in productIds && (it.productId to it.shelfId) !in stockKeys }
            .forEach { database.stockDao().deleteHard(pantryId, it.productId, it.shelfId) }
        val shoppingIds = incoming.shoppingItems.map { it.id }.toSet()
        database.shoppingDao().listActive(pantryId).filter { it.id !in shoppingIds }.forEach {
            database.shoppingDao().upsert(it.copy(deletedAt = now, updatedAt = now, syncState = SyncState.PENDING))
        }
    }

    private fun csvCell(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun validateSnapshot(snapshot: PantrySnapshot): List<String> = buildList {
        val idPattern = Regex("^[A-Za-z0-9_-]{1,128}$")
        fun validId(value: String, label: String) {
            if (!idPattern.matches(value)) add("$label ima neispravan identifikator.")
        }
        fun duplicates(values: List<String>, label: String) {
            if (values.size != values.distinct().size) add("$label sadrži duple identifikatore.")
        }
        if (snapshot.pantry.name.trim().length !in 1..60) add("Naziv smočnice nije ispravan.")
        val totalRecords = snapshot.shelves.size + snapshot.categories.size + snapshot.products.size +
            snapshot.stocks.size + snapshot.shoppingItems.size
        if (totalRecords > 350) add("Sigurnosna kopija prelazi ograničenje od 350 atomarnih zapisa.")
        if (snapshot.activities.size > 5_000) add("Sigurnosna kopija sadrži previše aktivnosti.")
        duplicates(snapshot.shelves.map { it.id }, "Popis polica")
        duplicates(snapshot.categories.map { it.id }, "Popis kategorija")
        duplicates(snapshot.products.map { it.id }, "Popis artikala")
        duplicates(snapshot.shoppingItems.map { it.id }, "Popis kupnje")
        snapshot.shelves.forEach {
            validId(it.id, "Polica")
            if (it.name.trim().length !in 1..100 || it.sortOrder !in 0..10_000) add("Polica ${it.id} ima neispravne podatke.")
        }
        snapshot.categories.forEach {
            validId(it.id, "Kategorija")
            if (it.name.trim().length !in 1..100 || it.sortOrder !in 0..10_000) add("Kategorija ${it.id} ima neispravne podatke.")
        }
        val productIds = snapshot.products.map { it.id }.toSet()
        val shelfIds = snapshot.shelves.map { it.id }.toSet()
        val barcodes = snapshot.products.mapNotNull { it.barcode }
        if (barcodes.size != barcodes.distinct().size) add("Popis artikala sadrži duple barkodove.")
        snapshot.products.forEach {
            validId(it.id, "Artikl")
            if (it.name.trim().length !in 1..100 || it.category.trim().length !in 1..100 || it.description.length > 500) {
                add("Artikl ${it.id} ima neispravan naziv, kategoriju ili opis.")
            }
            if (it.minimumQuantity !in 0..1_000_000) add("Artikl ${it.id} ima neispravan minimum.")
            it.barcode?.let { code -> if (!BarcodePolicy.isSupported(code)) add("Artikl ${it.id} ima neispravan barkod.") }
        }
        val stockKeys = snapshot.stocks.map { it.productId to it.shelfId }
        if (stockKeys.size != stockKeys.distinct().size) add("Raspodjela zalihe sadrži duple lokacije.")
        snapshot.stocks.forEach {
            validId(it.productId, "Lokacija artikla")
            validId(it.shelfId, "Lokacija police")
            if (it.productId !in productIds) add("Lokacija upućuje na nepostojeći artikl ${it.productId}.")
            if (it.shelfId !in shelfIds) add("Lokacija upućuje na nepostojeću policu ${it.shelfId}.")
            if (it.quantity !in 0..1_000_000) add("Lokacija ${it.productId}/${it.shelfId} ima neispravnu količinu.")
        }
        snapshot.shoppingItems.forEach {
            validId(it.id, "Stavka kupnje")
            if (it.name.trim().length !in 1..100 || it.category.trim().length !in 1..100 || it.requiredQuantity !in 1..1_000_000) {
                add("Stavka kupnje ${it.id} ima neispravne podatke.")
            }
            if (it.manual && (it.productId != null || it.id.startsWith("auto_"))) {
                add("Ručna stavka ${it.id} ima rezerviranu vezu ili identifikator.")
            }
            if (!it.manual) {
                val productId = it.productId
                if (productId == null || productId !in productIds || it.id != "auto_$productId") {
                    add("Automatska stavka ${it.id} nema valjanu vezu s artiklom.")
                }
            }
        }
        val totals = snapshot.stocks.groupBy { it.productId }.mapValues { (_, rows) -> rows.sumOf { it.quantity } }
        snapshot.products.forEach { product ->
            val expected = if (product.autoShopping) (product.minimumQuantity - (totals[product.id] ?: 0)).coerceAtLeast(0) else 0
            val automatic = snapshot.shoppingItems.singleOrNull { !it.manual && it.productId == product.id }
            if (expected == 0 && automatic != null) add("Artikl ${product.id} ima suvišnu automatsku stavku kupnje.")
            if (expected > 0 && (automatic == null || automatic.requiredQuantity != expected || automatic.name != product.name || automatic.category != product.category)) {
                add("Automatska stavka artikla ${product.id} ne odgovara stvarnom manjku.")
            }
        }
        snapshot.activities.forEach {
            validId(it.id, "Aktivnost")
            if (it.displayLabel.length > 100 || it.deviceName.length !in 2..40) add("Aktivnost ${it.id} ima neispravne podatke.")
        }
    }.distinct()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
        const val THIRTY_DAYS = 30L * 24 * 60 * 60 * 1_000
        const val TWELVE_MONTHS = 365L * 24 * 60 * 60 * 1_000
    }
}
