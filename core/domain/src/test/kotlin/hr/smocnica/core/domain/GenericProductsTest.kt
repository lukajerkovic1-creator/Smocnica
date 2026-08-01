package hr.smocnica.core.domain

import hr.smocnica.core.model.MeasurementKind
import hr.smocnica.core.model.MinimumMode
import hr.smocnica.core.model.PackageUnit
import hr.smocnica.core.model.Product
import hr.smocnica.core.model.ProductVariant
import hr.smocnica.core.model.ProductWithStock
import hr.smocnica.core.model.Stock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericProductsTest {
    @Test
    fun `two one-kilogram and three half-kilogram packages equal three and half kilograms`() {
        val item = flourItem(includeUnknown = false)
        assertEquals("5 pakiranja (3,5 kg)", GenericStockPolicy.summarize(item).display())
    }

    @Test
    fun `unknown package keeps known lower bound`() {
        val item = flourItem(includeUnknown = true)
        assertEquals("6 pakiranja (najmanje 3,5 kg)", GenericStockPolicy.summarize(item).display())
    }

    @Test
    fun `count aggregate keeps the shared package unit label`() {
        val item = ProductWithStock(
            product = Product("paper", "pantry", "Toaletni papir", createdAt = 1, updatedAt = 1),
            variants = listOf(variant("paper-pack", 10, PackageUnit.ROLL)),
            stocks = listOf(stock("paper-pack", 2)),
        )

        assertEquals("2 pakiranja (20 rola)", GenericStockPolicy.summarize(item).display())
    }

    @Test
    fun `corrupt oversized aggregate saturates instead of crashing the UI`() {
        val item = ProductWithStock(
            product = Product("large", "pantry", "Velika zaliha", createdAt = 1, updatedAt = 1),
            variants = listOf(variant("large-a", Long.MAX_VALUE, PackageUnit.MG), variant("large-b", Long.MAX_VALUE, PackageUnit.MG)),
            stocks = listOf(stock("large-a", 2), stock("large-b", 2)),
        )

        assertEquals(Long.MAX_VALUE, item.knownAmountBase)
        assertEquals(Long.MAX_VALUE, GenericStockPolicy.summarize(item).knownAmountBase)
    }

    @Test
    fun `minimum in kilograms rounds preferred package upward`() {
        val item = flourItem(includeUnknown = false).copy(
            product = flourItem(false).product.copy(
                minimumMode = MinimumMode.MASS_MG,
                minimumAmountBase = 4_000_000,
                preferredVariantId = "one-kg",
            ),
        )
        assertEquals(1, GenericStockPolicy.requiredPackages(item))
    }

    @Test
    fun `generic and variant minimum create one line with sufficient whole packages`() {
        val base = flourItem(includeUnknown = false)
        val item = base.copy(
            product = base.product.copy(
                minimumMode = MinimumMode.PACKAGES,
                minimumAmountBase = 6,
                autoShopping = true,
            ),
            variants = base.variants.map { variant ->
                if (variant.id == "one-kg") variant.copy(minimumPackages = 4) else variant
            },
        )
        assertEquals(2, GenericStockPolicy.requiredPackages(item))
        assertEquals("one-kg", GenericStockPolicy.preferredVariantForShopping(item)?.id)
    }

    @Test
    fun `package parser uses exact integer base units`() {
        assertEquals(1_000_000L, PackageAmountPolicy.parse("vreća 1 kg")?.amountBase)
        assertEquals(500_000L, PackageAmountPolicy.parse("500 g")?.amountBase)
        assertEquals(1_500L, PackageAmountPolicy.baseAmount("1,5", PackageUnit.L))
        assertEquals(20L, PackageAmountPolicy.parse("20 rola")?.amountBase)
    }

    @Test
    fun `deterministic dictionary proposes Croatian generic name but never merges`() {
        val suggestion = GenericNamePolicy.suggest("pšenično brašno T-550", listOf("flour" to "Glatko brašno"), emptyList())
        assertEquals("Glatko brašno", suggestion.suggestedGenericName)
        assertEquals("flour", suggestion.existingProductId)
        assertTrue(suggestion.confidencePercent >= 90)
        assertFalse(suggestion.confidencePercent == 100)
    }

    private fun flourItem(includeUnknown: Boolean): ProductWithStock {
        val variants = buildList {
            add(variant("one-kg", 1_000_000, PackageUnit.KG))
            add(variant("half-kg", 500_000, PackageUnit.G))
            if (includeUnknown) add(variant("unknown", null, PackageUnit.UNKNOWN))
        }
        val stocks = buildList {
            add(stock("one-kg", 2))
            add(stock("half-kg", 3))
            if (includeUnknown) add(stock("unknown", 1))
        }
        return ProductWithStock(
            product = Product("flour", "pantry", "Glatko brašno", createdAt = 1, updatedAt = 1),
            stocks = stocks,
            variants = variants,
        )
    }

    private fun variant(id: String, amount: Long?, unit: PackageUnit) = ProductVariant(
        id = id,
        pantryId = "pantry",
        productId = "flour",
        displayName = id,
        packageAmountBase = amount,
        packageUnit = unit,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun stock(variantId: String, quantity: Int) = Stock(
        pantryId = "pantry",
        productId = "flour",
        shelfId = "shelf",
        quantity = quantity,
        updatedAt = 1,
        variantId = variantId,
    )
}
