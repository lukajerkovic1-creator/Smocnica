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
import hr.smocnica.core.data.remote.sanitizeOpenFoodFactsImageUrl
import hr.smocnica.core.domain.BackupRepository
import hr.smocnica.core.domain.BarcodePolicy
import hr.smocnica.core.domain.ImportPreview
import hr.smocnica.core.domain.ImportStrategy
import hr.smocnica.core.domain.GenericStockPolicy
import hr.smocnica.core.domain.PackageAmountPolicy
import hr.smocnica.core.model.ActivityType
import hr.smocnica.core.model.AggregateType
import hr.smocnica.core.model.OperationPayload
import hr.smocnica.core.model.OperationState
import hr.smocnica.core.model.PantrySnapshot
import hr.smocnica.core.model.ProductVariant
import hr.smocnica.core.model.PackageUnit
import hr.smocnica.core.model.PhotoSource
import hr.smocnica.core.model.SyncState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
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
        val variantsByProduct = snapshot.variants.groupBy { it.productId }
        val variantsById = snapshot.variants.associateBy { it.id }
        val shelvesById = snapshot.shelves.associateBy { it.id }
        val shoppingProducts = snapshot.shoppingItems.mapNotNull { it.productId }.toSet()
        val rows = buildList {
            add(listOf("Generički naziv", "Varijante", "Barkodovi", "Pakiranja", "Kategorija", "Ukupno pakiranja", "Minimalna zaliha", "Police", "Stanje kupnje"))
            snapshot.products.sortedBy { it.name.lowercase() }.forEach { product ->
                val productStocks = stocksByProduct[product.id].orEmpty().filter { it.quantity > 0 }
                val productVariants = variantsByProduct[product.id].orEmpty().filter { it.deletedAt == null }
                add(
                    listOf(
                        product.name,
                        productVariants.joinToString(" | ") { it.displayName },
                        productVariants.mapNotNull { it.barcode }.joinToString(" | "),
                        productVariants.map { it.packageLabel }.filter(String::isNotBlank).joinToString(" | "),
                        product.category,
                        productStocks.sumOf { it.quantity }.toString(),
                        "${product.minimumMode}:${product.minimumAmountBase}",
                        productStocks.joinToString(" | ") {
                            val variant = variantsById[it.variantId]
                            "${shelvesById[it.shelfId]?.name ?: it.shelfId}/${variant?.displayName ?: it.variantId}: ${it.quantity}"
                        },
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
        val compatibleSnapshot = upgradeLegacySnapshot(envelope.snapshot)
        val conflicts = buildList {
            addAll(validateSnapshot(compatibleSnapshot))
            val duplicateBarcodes = compatibleSnapshot.variants.mapNotNull { it.barcode }
                .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (duplicateBarcodes.isNotEmpty()) add("Više artikala koristi isti barkod (${duplicateBarcodes.size}).")
            val shelfIds = compatibleSnapshot.shelves.map { it.id }.toSet()
            val orphanStocks = compatibleSnapshot.stocks.count { it.shelfId !in shelfIds }
            if (orphanStocks > 0) add("$orphanStocks lokacija nema postojeću policu.")
        }
        return ImportPreview(
            schemaVersion = envelope.schemaVersion,
            pantryName = compatibleSnapshot.pantry.name,
            shelfCount = compatibleSnapshot.shelves.size,
            productCount = compatibleSnapshot.products.size,
            shoppingCount = compatibleSnapshot.shoppingItems.size,
            snapshot = compatibleSnapshot,
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
            val requestedDefault = source.categories.filter { it.isDefault }.minWithOrNull(compareBy({ it.sortOrder }, { it.id }))
            val defaultCategory = requestedDefault
                ?: source.categories.firstOrNull { canonicalName(it.name) == canonicalName("Ostalo") }
                ?: source.categories.minWithOrNull(compareBy({ it.sortOrder }, { it.id }))
                ?: error("Sigurnosna kopija nema kategoriju.")
            val canonicalCategories = source.categories.map { it.copy(isDefault = it.id == defaultCategory.id) }
            val categoriesById = canonicalCategories.associateBy { it.id }
            val categoriesByName = canonicalCategories.associateBy { canonicalName(it.name) }
            val canonicalProducts = source.products.map {
                val canonicalCategory = categoriesById[it.categoryId]
                    ?: categoriesByName[canonicalName(it.category)]
                    ?: error("Artikl ${it.id} nema dostupnu kategoriju.")
                val keepPublicPhoto = it.photoSource == hr.smocnica.core.model.PhotoSource.OPEN_FOOD_FACTS &&
                    sanitizeOpenFoodFactsImageUrl(it.photoUri) != null
                it.copy(
                    pantryId = targetPantryId,
                    category = canonicalCategory.name,
                    categoryId = canonicalCategory.id,
                    photoUri = it.photoUri.takeIf { keepPublicPhoto },
                    photoSource = if (keepPublicPhoto) it.photoSource else hr.smocnica.core.model.PhotoSource.NONE,
                )
            }
            val productsById = canonicalProducts.associateBy { it.id }
            val canonicalVariants = source.variants.map { variant ->
                val product = productsById[variant.productId]
                    ?: error("Varijanta ${variant.id} nema postojeći artikl.")
                val keepPublicPhoto = variant.photoSource == PhotoSource.OPEN_FOOD_FACTS &&
                    sanitizeOpenFoodFactsImageUrl(variant.photoUri) != null
                variant.copy(
                    pantryId = targetPantryId,
                    productId = product.id,
                    photoUri = variant.photoUri.takeIf { keepPublicPhoto },
                    photoSource = if (keepPublicPhoto) variant.photoSource else PhotoSource.NONE,
                )
            }
            val variantsById = canonicalVariants.associateBy { it.id }
            val incoming = source.copy(
                pantry = source.pantry.copy(id = targetPantryId, ownerUid = localPantry.ownerUid),
                members = source.members.map { it.copy(pantryId = targetPantryId) },
                shelves = source.shelves.map { it.copy(pantryId = targetPantryId) },
                categories = canonicalCategories.map { it.copy(pantryId = targetPantryId) },
                products = canonicalProducts,
                variants = canonicalVariants,
                synonymRules = source.synonymRules.map { rule ->
                    rule.copy(pantryId = targetPantryId, productId = rule.productId?.takeIf(productsById::containsKey))
                },
                stocks = source.stocks.map { stock ->
                    val variant = variantsById[stock.variantId]
                        ?: error("Zaliha ${stock.variantId} nema postojeću varijantu.")
                    stock.copy(pantryId = targetPantryId, productId = variant.productId)
                },
                shoppingItems = source.shoppingItems.map { item ->
                    val category = item.productId?.let(productsById::get)?.let { categoriesById[it.categoryId] }
                        ?: categoriesById[item.categoryId]
                        ?: categoriesByName[canonicalName(item.category)]
                        ?: error("Stavka kupnje ${item.id} nema dostupnu kategoriju.")
                    item.copy(pantryId = targetPantryId, category = category.name, categoryId = category.id)
                },
                activities = source.activities.map { it.copy(pantryId = targetPantryId) },
            )
            if (strategy == ImportStrategy.REPLACE) softDeleteMissing(incoming, now)

            database.shelfDao().upsertAll(incoming.shelves.map { it.copy(pantryId = localPantry.id, syncState = SyncState.PENDING).entity() })
            database.categoryDao().upsertAll(incoming.categories.map { it.copy(pantryId = localPantry.id, syncState = SyncState.PENDING).entity() })
            database.productDao().upsertAll(incoming.products.map { it.copy(pantryId = localPantry.id, syncState = SyncState.PENDING).entity() })
            database.productVariantDao().upsertAll(incoming.variants.map { it.copy(pantryId = localPantry.id, syncState = SyncState.PENDING).entity() })
            database.synonymRuleDao().upsertAll(incoming.synonymRules.map { it.copy(pantryId = localPantry.id, syncState = SyncState.PENDING).entity() })
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
            variants = database.productVariantDao().listActive(pantryId).map { it.model() },
            synonymRules = database.synonymRuleDao().listAll(pantryId).filter { it.deletedAt == null }.map { it.model() },
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
        val variantIds = incoming.variants.map { it.id }.toSet()
        database.productVariantDao().listActive(pantryId).filter { it.id !in variantIds }.forEach {
            database.productVariantDao().upsert(it.copy(deletedAt = now, purgeAfter = now + THIRTY_DAYS, updatedAt = now, syncState = SyncState.PENDING))
        }
        val stockKeys = incoming.stocks.map { it.variantId to it.shelfId }.toSet()
        database.stockDao().listForPantry(pantryId)
            .filter { it.variantId in variantIds && (it.variantId to it.shelfId) !in stockKeys }
            .forEach { database.stockDao().deleteVariantHard(pantryId, it.variantId, it.shelfId) }
        val shoppingIds = incoming.shoppingItems.map { it.id }.toSet()
        database.shoppingDao().listActive(pantryId).filter { it.id !in shoppingIds }.forEach {
            database.shoppingDao().upsert(it.copy(deletedAt = now, updatedAt = now, syncState = SyncState.PENDING))
        }
    }

    /**
     * Schema 1/2 stored the barcode and package metadata directly on Product.  The
     * stable product id is deliberately reused as the initial variant id so stocks,
     * activities and pending operation references remain valid after an import.
     */
    private fun upgradeLegacySnapshot(snapshot: PantrySnapshot): PantrySnapshot {
        if (snapshot.variants.isNotEmpty()) {
            return snapshot.copy(
                pantry = snapshot.pantry.copy(contentSchemaVersion = maxOf(snapshot.pantry.contentSchemaVersion, CONTENT_SCHEMA_VERSION)),
            )
        }
        val variants = snapshot.products.map { product ->
            val parsed = PackageAmountPolicy.parse(product.description)
            ProductVariant(
                id = product.id,
                pantryId = product.pantryId,
                productId = product.id,
                displayName = product.name,
                barcode = product.barcode,
                packageAmountBase = parsed?.amountBase,
                packageUnit = parsed?.unit ?: PackageUnit.UNKNOWN,
                packageLabel = parsed?.label.orEmpty(),
                description = product.description,
                photoUri = product.photoUri,
                photoSource = product.photoSource,
                revision = product.revision,
                createdAt = product.createdAt,
                updatedAt = product.updatedAt,
                deletedAt = product.deletedAt,
                purgeAfter = product.purgeAfter,
                syncState = product.syncState,
            )
        }
        val variantsById = variants.associateBy { it.id }
        return snapshot.copy(
            pantry = snapshot.pantry.copy(contentSchemaVersion = CONTENT_SCHEMA_VERSION),
            products = snapshot.products.map { product ->
                product.copy(
                    barcode = null,
                    description = "",
                    photoUri = null,
                    photoSource = PhotoSource.NONE,
                    minimumAmountBase = product.minimumQuantity.toLong(),
                    preferredVariantId = product.id,
                )
            },
            variants = variants,
            stocks = snapshot.stocks.map { stock ->
                val variant = variantsById[stock.variantId] ?: variantsById[stock.productId]
                    ?: error("Zaliha ${stock.productId} nema postojeći artikl.")
                stock.copy(productId = variant.productId, variantId = variant.id)
            },
            shoppingItems = snapshot.shoppingItems.map { item ->
                item.copy(preferredVariantId = item.productId?.takeIf(variantsById::containsKey))
            },
        )
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
            snapshot.variants.size + snapshot.synonymRules.size + snapshot.stocks.size + snapshot.shoppingItems.size
        if (totalRecords > MAX_IMPORT_RECORDS) add("Sigurnosna kopija prelazi ograničenje od $MAX_IMPORT_RECORDS zapisa.")
        if (snapshot.activities.size > 5_000) add("Sigurnosna kopija sadrži previše aktivnosti.")
        duplicates(snapshot.shelves.map { it.id }, "Popis polica")
        duplicates(snapshot.categories.map { it.id }, "Popis kategorija")
        duplicates(snapshot.products.map { it.id }, "Popis artikala")
        duplicates(snapshot.variants.map { it.id }, "Popis varijanti")
        duplicates(snapshot.synonymRules.map { it.id }, "Rječnik sinonima")
        duplicates(snapshot.shoppingItems.map { it.id }, "Popis kupnje")
        if (snapshot.shelves.map { canonicalName(it.name) }.distinct().size != snapshot.shelves.size) {
            add("Popis polica sadrži duple nazive neovisno o velikim slovima ili razmacima.")
        }
        if (snapshot.categories.map { canonicalName(it.name) }.distinct().size != snapshot.categories.size) {
            add("Popis kategorija sadrži duple nazive neovisno o velikim slovima ili razmacima.")
        }
        if (snapshot.categories.count { it.isDefault } != 1) add("Mora postojati točno jedna zadana kategorija.")
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
        val categoriesById = snapshot.categories.associateBy { it.id }
        val variantIds = snapshot.variants.map { it.id }.toSet()
        val variantsById = snapshot.variants.associateBy { it.id }
        val barcodes = snapshot.variants.mapNotNull { it.barcode }
        if (barcodes.size != barcodes.distinct().size) add("Popis artikala sadrži duple barkodove.")
        snapshot.products.forEach {
            validId(it.id, "Artikl")
            if (it.name.trim().length !in 1..100 || it.category.trim().length !in 1..100) {
                add("Artikl ${it.id} ima neispravan naziv ili kategoriju.")
            }
            if (it.minimumAmountBase !in 0..MAX_BASE_AMOUNT) add("Artikl ${it.id} ima neispravan minimum.")
            if (it.preferredVariantId != null && variantsById[it.preferredVariantId]?.productId != it.id) {
                add("Artikl ${it.id} ima neispravnu preferiranu varijantu.")
            }
            if (categoriesById[it.categoryId] == null && snapshot.categories.none { category -> category.name.equals(it.category, ignoreCase = true) }) {
                add("Artikl ${it.id} upućuje na nepostojeću kategoriju.")
            }
        }
        snapshot.variants.forEach { variant ->
            validId(variant.id, "Varijanta")
            if (variant.productId !in productIds) add("Varijanta ${variant.id} upućuje na nepostojeći artikl.")
            if (variant.displayName.trim().length !in 1..100 || variant.manufacturer.length > 100 || variant.description.length > 500 || variant.packageLabel.length > 100) {
                add("Varijanta ${variant.id} ima neispravne tekstualne podatke.")
            }
            if (variant.packageAmountBase != null && variant.packageAmountBase !in 1..MAX_BASE_AMOUNT) {
                add("Varijanta ${variant.id} ima neispravnu veličinu pakiranja.")
            }
            if ((variant.packageAmountBase == null) != (variant.packageUnit == PackageUnit.UNKNOWN)) {
                add("Varijanta ${variant.id} nema usklađenu veličinu i mjernu jedinicu.")
            }
            if (variant.minimumPackages != null && variant.minimumPackages !in 0..1_000_000) add("Varijanta ${variant.id} ima neispravan minimum.")
            if (variant.photoSource == PhotoSource.OPEN_FOOD_FACTS && variant.photoUri != null && sanitizeOpenFoodFactsImageUrl(variant.photoUri) == null) {
                add("Varijanta ${variant.id} ima nedopuštenu javnu fotografiju.")
            }
            variant.barcode?.let { code -> if (!BarcodePolicy.isSupported(code)) add("Varijanta ${variant.id} ima neispravan barkod.") }
        }
        snapshot.synonymRules.forEach { rule ->
            validId(rule.id, "Pravilo sinonima")
            if (rule.sourceNormalized != canonicalName(rule.sourceNormalized) || rule.genericNameNormalized != canonicalName(rule.genericName)) {
                add("Pravilo sinonima ${rule.id} nije normalizirano.")
            }
            if (rule.productId != null && rule.productId !in productIds) add("Pravilo sinonima ${rule.id} upućuje na nepostojeći artikl.")
        }
        val stockKeys = snapshot.stocks.map { it.variantId to it.shelfId }
        if (stockKeys.size != stockKeys.distinct().size) add("Raspodjela zalihe sadrži duple lokacije.")
        snapshot.stocks.forEach {
            validId(it.productId, "Lokacija artikla")
            validId(it.variantId, "Lokacija varijante")
            validId(it.shelfId, "Lokacija police")
            if (it.productId !in productIds) add("Lokacija upućuje na nepostojeći artikl ${it.productId}.")
            if (it.variantId !in variantIds || variantsById[it.variantId]?.productId != it.productId) {
                add("Lokacija upućuje na nepostojeću ili pogrešno grupiranu varijantu ${it.variantId}.")
            }
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
            if (categoriesById[it.categoryId] == null && snapshot.categories.none { category -> canonicalName(category.name) == canonicalName(it.category) }) {
                add("Stavka kupnje ${it.id} upućuje na nepostojeću kategoriju.")
            }
            if (!it.manual) {
                val productId = it.productId
                if (productId == null || productId !in productIds || it.id != "auto_$productId") {
                    add("Automatska stavka ${it.id} nema valjanu vezu s artiklom.")
                }
            }
        }
        snapshot.products.forEach { product ->
            val item = hr.smocnica.core.model.ProductWithStock(
                product,
                snapshot.stocks.filter { it.productId == product.id },
                snapshot.variants.filter { it.productId == product.id },
            )
            val expected = GenericStockPolicy.requiredPackages(item)
            val automatic = snapshot.shoppingItems.singleOrNull { !it.manual && it.productId == product.id }
            if (expected == 0 && automatic != null) add("Artikl ${product.id} ima suvišnu automatsku stavku kupnje.")
            if (expected > 0 && (automatic == null || automatic.requiredQuantity != expected || automatic.name != product.name || automatic.categoryId != product.categoryId || automatic.category != product.category)) {
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

    private fun canonicalName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.forLanguageTag("hr"))

    private companion object {
        const val SCHEMA_VERSION = 3
        const val CONTENT_SCHEMA_VERSION = 2
        const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
        const val MAX_IMPORT_RECORDS = 10_000
        const val MAX_BASE_AMOUNT = 1_000_000_000_000L
        const val THIRTY_DAYS = 30L * 24 * 60 * 60 * 1_000
        const val TWELVE_MONTHS = 365L * 24 * 60 * 60 * 1_000
    }
}
