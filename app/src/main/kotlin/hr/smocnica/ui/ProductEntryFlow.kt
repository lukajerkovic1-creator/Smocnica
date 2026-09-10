package hr.smocnica.ui

import hr.smocnica.core.domain.CatalogProduct
import hr.smocnica.core.model.PhotoSource
import hr.smocnica.core.model.Category
import hr.smocnica.core.model.ProductWithStock
import hr.smocnica.core.model.Product
import hr.smocnica.core.model.ProductVariant

enum class CatalogLookupOutcome { IDLE, LOADING, SUCCESS, EMPTY, TIMEOUT, ERROR }

internal fun preferredEntryShelf(explicit: String, remembered: String, available: List<String>): String =
    explicit.takeIf { it in available } ?: remembered.takeIf { it in available } ?: available.firstOrNull().orEmpty()

data class CatalogLookupState(
    val barcode: String = "",
    val outcome: CatalogLookupOutcome = CatalogLookupOutcome.IDLE,
    val product: CatalogProduct? = null,
) {
    val isLoading: Boolean get() = outcome == CatalogLookupOutcome.LOADING
}

data class ProductEntryDraft(
    val name: String = "",
    val barcode: String = "",
    val description: String = "",
    val category: String = "",
    val categoryId: String = "",
    val photoUri: String? = null,
    val photoSource: PhotoSource = PhotoSource.NONE,
    val manufacturer: String = "",
) {
    fun mergeEmptyFields(catalog: CatalogProduct, mappedCategory: Category? = null): ProductEntryDraft = copy(
        name = name.ifBlank { catalog.name },
        barcode = barcode.ifBlank { catalog.barcode },
        description = description.ifBlank { catalog.description },
        category = category.ifBlank { mappedCategory?.name ?: catalog.category },
        categoryId = categoryId.ifBlank { if (category.isBlank()) mappedCategory?.id.orEmpty() else "" },
        photoUri = photoUri ?: catalog.imageUrl,
        photoSource = if (photoUri == null && catalog.imageUrl != null) PhotoSource.OPEN_FOOD_FACTS else photoSource,
        manufacturer = manufacturer.ifBlank { catalog.manufacturer },
    )

    val missingRequiredFields: List<String>
        get() = buildList {
            if (name.isBlank()) add("naziv")
            if (category.isBlank()) add("kategoriju")
            else if (categoryId.isBlank()) add("valjanu kategoriju")
        }
}

data class ProductEditorSubmission(
    val product: Product,
    val variant: ProductVariant,
    /** Existing generic article chosen by the user after reviewing the grouping suggestion. */
    val targetProductId: String? = null,
)

sealed interface BarcodeInventoryMatch {
    val item: ProductWithStock
    val variant: ProductVariant

    data class Active(override val item: ProductWithStock, override val variant: ProductVariant) : BarcodeInventoryMatch
    data class Deleted(override val item: ProductWithStock, override val variant: ProductVariant) : BarcodeInventoryMatch
}

internal fun findBarcodeInventoryMatch(
    barcode: String,
    active: List<ProductWithStock>,
    deleted: List<ProductWithStock>,
    excludeVariantId: String? = null,
): BarcodeInventoryMatch? {
    if (barcode.isBlank()) return null
    active.forEach { item ->
        item.variants.firstOrNull { it.id != excludeVariantId && it.barcode == barcode }
            ?.let { return BarcodeInventoryMatch.Active(item, it) }
        if (item.variants.isEmpty() && item.product.id != excludeVariantId && item.product.barcode == barcode) {
            return BarcodeInventoryMatch.Active(item, item.product.legacyVariant())
        }
    }
    deleted.forEach { item ->
        item.variants.firstOrNull { it.id != excludeVariantId && it.barcode == barcode }
            ?.let { return BarcodeInventoryMatch.Deleted(item, it) }
        if (item.variants.isEmpty() && item.product.id != excludeVariantId && item.product.barcode == barcode) {
            return BarcodeInventoryMatch.Deleted(item, item.product.legacyVariant())
        }
    }
    return null
}

private fun Product.legacyVariant() = ProductVariant(
    id = id,
    pantryId = pantryId,
    productId = id,
    displayName = name,
    barcode = barcode,
    description = description,
    photoUri = photoUri,
    photoSource = photoSource,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    purgeAfter = purgeAfter,
    revision = revision,
    syncState = syncState,
)
