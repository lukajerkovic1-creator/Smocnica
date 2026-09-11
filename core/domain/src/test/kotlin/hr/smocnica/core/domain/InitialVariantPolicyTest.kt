package hr.smocnica.core.domain

import hr.smocnica.core.model.*
import org.junit.Assert.*
import org.junit.Test

class InitialVariantPolicyTest {
    private val product = Product("p", "pantry", "Fusilli", preferredVariantId = "p", createdAt = 1, updatedAt = 1)
    private val empty = ProductVariant("p", "pantry", "p", "Fusilli", photoUri = "photo", createdAt = 1, updatedAt = 1)
    private val real = empty.copy(id = "real", manufacturer = "Barilla", barcode = "8076802085981", packageLabel = "500 g", packageAmountBase = 500000, packageUnit = PackageUnit.G)
    private val item = ProductWithStock(product, listOf(Stock("pantry", "p", "s", 1, variantId = "real", updatedAt = 1)), listOf(empty, real))

    @Test fun obsoletePlaceholderIsExcludedWithoutChangingRealStock() {
        val result = InitialVariantPolicy.withoutObsoletePlaceholder(item)
        assertEquals(listOf(real), result.variants)
        assertEquals(1, result.totalQuantity)
        assertEquals("real", result.product.preferredVariantId)
        assertEquals(2, item.variants.size)
    }

    @Test fun genuineEmptyVariantsAndOnlyVariantRemainAvailable() {
        listOf(empty.copy(barcode = "8076802085981"), empty.copy(purchaseCount = 1), empty.copy(minimumPackages = 0),
            empty.copy(updatedAt = 2), empty.copy(revision = 2), empty.copy(photoUri = "other-photo"),
            empty.copy(manufacturer = "Drugi"), empty.copy(id = "user-variant")).forEach { candidate ->
            val original = item.copy(variants = listOf(candidate, real))
            assertEquals(original, InitialVariantPolicy.withoutObsoletePlaceholder(original))
        }
        val single = item.copy(variants = listOf(empty), stocks = emptyList())
        assertEquals(single, InitialVariantPolicy.withoutObsoletePlaceholder(single))
        val stocked = item.copy(stocks = item.stocks + Stock("pantry", "p", "s", 1, variantId = "p", updatedAt = 1))
        assertEquals(stocked, InitialVariantPolicy.withoutObsoletePlaceholder(stocked))
    }

    @Test fun firstVariantKeepsPackageBarcodeAndPhoto() {
        val result = InitialVariantPolicy.create(product, real, "p", 5)
        assertEquals("p", result.id)
        assertEquals(real.barcode, result.barcode)
        assertEquals(real.packageAmountBase, result.packageAmountBase)
        assertEquals(real.photoUri, result.photoUri)
        assertEquals(5L, result.createdAt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidFirstPackageIsRejected() {
        InitialVariantPolicy.create(product, real.copy(packageAmountBase = -1), "p", 5)
    }
}
