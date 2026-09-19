package hr.smocnica.ui

import hr.smocnica.core.model.*
import org.junit.Assert.*
import org.junit.Test

class TrashPresentationTest {
    private fun milk(id: String, size: Long) = ProductVariant(id, "pantry", "p", "Mlijeko",
        packageAmountBase = size, packageUnit = PackageUnit.ML, createdAt = 1, updatedAt = 1)

    @Test fun sameNamedPackagesShowSizeAndParent() {
        val small = TrashItem(AggregateType.VARIANT, "small", "pantry", "Mlijeko", 1, 2,
            packages = listOf(milk("small", 500)), parentName = "Mlijeko")
        val large = small.copy(id = "large", packages = listOf(milk("large", 1000)))
        assertEquals("Pakiranje", trashTypeLabel(small))
        assertEquals("Mlijeko · 500 ml", trashDetails(small).first())
        assertNotEquals(trashDetails(small).first(), trashDetails(large).first())
        assertTrue(trashDetails(small).contains("Pripada artiklu: Mlijeko"))
    }

    @Test fun parentProductExplainsThatPackagesRestoreTogether() {
        val product = TrashItem(AggregateType.PRODUCT, "p", "pantry", "Mlijeko", 1, 2,
            packages = listOf(milk("small", 500), milk("large", 1000)))
        assertEquals("Artikl", trashTypeLabel(product))
        assertEquals(3, trashDetails(product).size)
        assertTrue(trashDetails(product).last().contains("zajedno"))
        assertEquals("Polica", trashTypeLabel(product.copy(type = AggregateType.SHELF)))
    }
}
