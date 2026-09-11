package hr.smocnica.ui

import hr.smocnica.core.model.Product
import hr.smocnica.core.model.ProductVariant
import hr.smocnica.core.model.ProductWithStock
import hr.smocnica.core.model.Stock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductCardPhotoTest {
    private val product = Product("p", "pantry", "Brašno", createdAt = 1, updatedAt = 1)
    private val plain = ProductVariant("v1", "pantry", "p", "Pakiranje 1", createdAt = 1, updatedAt = 1)
    private val photographed = plain.copy(id = "v2", photoUri = "gs://test/photo.jpg", updatedAt = 2)
    private fun item(vararg variants: ProductVariant) = ProductWithStock(
        product, listOf(Stock("pantry", "p", "s", 5, updatedAt = 1, variantId = "v1")), variants.toList(),
    )

    @Test fun genericCardUsesSavedPhotoWhenMostStockedPackageHasNone() {
        assertEquals(photographed, productCardPhoto(item(plain, photographed)))
    }

    @Test fun representativePhotoHasPriority() {
        val main = plain.copy(photoUri = "gs://test/main.jpg")
        assertEquals(main, productCardPhoto(item(main, photographed)))
    }

    @Test fun exactPackageMatchDoesNotShowAnotherPackagesPhoto() {
        assertNull(productCardPhoto(item(plain, photographed).copy(matchedVariantId = plain.id)))
    }

    @Test fun deletedAndEmptyPhotosAreNotShown() {
        assertNull(productCardPhoto(item(plain.copy(photoUri = " "), photographed.copy(deletedAt = 3))))
    }
}
