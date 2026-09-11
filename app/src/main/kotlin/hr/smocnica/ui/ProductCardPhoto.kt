package hr.smocnica.ui

import hr.smocnica.core.model.ProductVariant
import hr.smocnica.core.model.ProductWithStock

/** A generic card can use another active package photo when its leading package has none. */
internal fun productCardPhoto(item: ProductWithStock): ProductVariant? {
    val photos = item.variants.filter { it.deletedAt == null && !it.photoUri.isNullOrBlank() }
    item.matchedVariantId?.let { id -> return photos.firstOrNull { it.id == id } }
    photos.firstOrNull { it.id == item.representativeVariant?.id }?.let { return it }
    photos.firstOrNull { it.id == item.product.preferredVariantId }?.let { return it }
    return photos.maxWithOrNull(compareBy<ProductVariant> { it.updatedAt }.thenBy { it.id })
}
