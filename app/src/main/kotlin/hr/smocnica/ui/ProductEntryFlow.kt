package hr.smocnica.ui

import hr.smocnica.core.domain.CatalogProduct
import hr.smocnica.core.model.PhotoSource
import hr.smocnica.core.model.Category
import hr.smocnica.core.model.ProductWithStock

enum class CatalogLookupOutcome { IDLE, LOADING, SUCCESS, EMPTY, TIMEOUT, ERROR }

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
) {
    fun mergeEmptyFields(catalog: CatalogProduct, mappedCategory: Category? = null): ProductEntryDraft = copy(
        name = name.ifBlank { catalog.name },
        barcode = barcode.ifBlank { catalog.barcode },
        description = description.ifBlank { catalog.description },
        category = category.ifBlank { mappedCategory?.name ?: catalog.category },
        categoryId = categoryId.ifBlank { if (category.isBlank()) mappedCategory?.id.orEmpty() else "" },
        photoUri = photoUri ?: catalog.imageUrl,
        photoSource = if (photoUri == null && catalog.imageUrl != null) PhotoSource.OPEN_FOOD_FACTS else photoSource,
    )

    val missingRequiredFields: List<String>
        get() = buildList {
            if (name.isBlank()) add("naziv")
            if (category.isBlank()) add("kategoriju")
            else if (categoryId.isBlank()) add("valjanu kategoriju")
        }
}

sealed interface BarcodeInventoryMatch {
    val item: ProductWithStock

    data class Active(override val item: ProductWithStock) : BarcodeInventoryMatch
    data class Deleted(override val item: ProductWithStock) : BarcodeInventoryMatch
}

internal fun findBarcodeInventoryMatch(
    barcode: String,
    active: List<ProductWithStock>,
    deleted: List<ProductWithStock>,
    excludeProductId: String? = null,
): BarcodeInventoryMatch? {
    if (barcode.isBlank()) return null
    active.firstOrNull { it.product.id != excludeProductId && it.product.barcode == barcode }
        ?.let { return BarcodeInventoryMatch.Active(it) }
    return deleted.firstOrNull { it.product.id != excludeProductId && it.product.barcode == barcode }
        ?.let(BarcodeInventoryMatch::Deleted)
}
