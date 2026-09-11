package hr.smocnica.core.domain

import hr.smocnica.core.model.*

/** Canonical first package is saved with its generic product in the same operation. */
object InitialVariantPolicy {
    fun create(product: Product, draft: ProductVariant, id: String, now: Long): ProductVariant {
        require(draft.displayName.trim().length in 1..100) { "Unesite naziv varijante." }
        require(draft.manufacturer.trim().length <= 100 && draft.description.length <= 500) { "Podaci varijante su predugi." }
        require(draft.packageAmountBase == null || draft.packageAmountBase in 1..1_000_000_000_000L) { "Veličina pakiranja nije valjana." }
        require(draft.packageAmountBase == null || draft.packageUnit != PackageUnit.UNKNOWN) { "Odaberite mjernu jedinicu." }
        require(draft.minimumPackages == null || draft.minimumPackages in 0..1_000_000) { "Minimum varijante nije valjan." }
        return draft.copy(id = id, pantryId = product.pantryId, productId = product.id,
            displayName = draft.displayName.trim(), manufacturer = draft.manufacturer.trim(),
            barcode = draft.barcode?.takeIf(String::isNotBlank)?.let(BarcodePolicy::requireSupported),
            purchaseCount = 0, revision = 0, createdAt = now, updatedAt = now,
            deletedAt = null, purgeAfter = null, syncState = SyncState.PENDING)
    }

    /** Read projection only: preserve storage and genuine zero-stock packages. */
    fun withoutObsoletePlaceholder(item: ProductWithStock): ProductWithStock {
        val placeholder = item.variants.firstOrNull { it.id == item.product.id && it.deletedAt == null } ?: return item
        if (placeholder.createdAt != item.product.createdAt || placeholder.updatedAt != placeholder.createdAt ||
            placeholder.revision > 1 || placeholder.purchaseCount != 0L || placeholder.minimumPackages != null ||
            placeholder.barcode != null || placeholder.manufacturer.isNotBlank() || placeholder.description.isNotBlank() ||
            placeholder.packageLabel.isNotBlank() || placeholder.packageAmountBase != null || placeholder.packageUnit != PackageUnit.UNKNOWN ||
            GenericNamePolicy.normalize(placeholder.displayName) != GenericNamePolicy.normalize(item.product.name) ||
            item.stocks.any { it.variantId == placeholder.id && it.quantity != 0 }) return item
        val replacement = item.variants.firstOrNull { candidate ->
            candidate.id != placeholder.id && candidate.deletedAt == null &&
                (candidate.barcode != null || candidate.manufacturer.isNotBlank() || candidate.packageAmountBase != null) &&
                (placeholder.photoUri == null || placeholder.photoUri == candidate.photoUri)
        } ?: return item
        return item.copy(
            product = if (item.product.preferredVariantId == placeholder.id) item.product.copy(preferredVariantId = replacement.id) else item.product,
            variants = item.variants.filterNot { it.id == placeholder.id },
            stocks = item.stocks.filterNot { it.variantId == placeholder.id },
        )
    }
}
