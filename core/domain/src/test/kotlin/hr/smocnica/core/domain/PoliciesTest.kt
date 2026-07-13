package hr.smocnica.core.domain

import hr.smocnica.core.model.InventoryCount
import hr.smocnica.core.model.InventoryDifferenceType
import hr.smocnica.core.model.Product
import hr.smocnica.core.model.ProductWithStock
import hr.smocnica.core.model.Stock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliciesTest {
    @Test
    fun `removal cannot exceed shelf stock`() {
        assertThrows(IllegalArgumentException::class.java) {
            StockPolicy.adjust(currentOnShelf = 2, currentTotal = 4, delta = -3, minimum = 2)
        }
    }

    @Test
    fun `crossing minimum creates exact shortfall once`() {
        val crossing = StockPolicy.adjust(3, 5, -2, 4)
        assertTrue(crossing.crossedBelowMinimum)
        assertEquals(1, crossing.shortfall)

        val alreadyBelow = StockPolicy.adjust(2, 3, -1, 4)
        assertFalse(alreadyBelow.crossedBelowMinimum)
        assertEquals(2, alreadyBelow.shortfall)
    }

    @Test
    fun `supported GTIN checksums validate`() {
        assertTrue(BarcodePolicy.isSupported("96385074"))
        assertTrue(BarcodePolicy.isSupported("4006381333931"))
        assertTrue(BarcodePolicy.isSupported("036000291452"))
        assertTrue(BarcodePolicy.isSupported("04252614"))
        assertFalse(BarcodePolicy.isSupported("4006381333932"))
        assertFalse(BarcodePolicy.isSupported("123"))
    }

    @Test
    fun `duplicate scan is suppressed only within window`() {
        val guard = DuplicateScanGuard(1_500)
        assertTrue(guard.accept("4006381333931", 1_000))
        assertFalse(guard.accept("4006381333931", 2_000))
        assertTrue(guard.accept("4006381333931", 2_501))
    }

    @Test
    fun `inventory reports missing unexpected and quantity differences`() {
        val expected = listOf(
            product("a", "Riža", 2),
            product("b", "Sol", 4),
        )
        val result = InventoryPolicy.differences(
            expected = expected,
            counts = listOf(InventoryCount("b", 3), InventoryCount("c", 1)),
            shelfId = "s1",
        )
        assertEquals(3, result.size)
        assertEquals(InventoryDifferenceType.MISSING, result.first { it.productId == "a" }.type)
        assertEquals(InventoryDifferenceType.QUANTITY, result.first { it.productId == "b" }.type)
        assertEquals(InventoryDifferenceType.UNEXPECTED, result.first { it.productId == "c" }.type)
    }

    @Test
    fun `inventory snapshot detects any quantity change and ignores row order`() {
        val first = listOf(
            Stock("p1", "a", "s1", 2, updatedAt = 1),
            Stock("p1", "b", "s1", 3, updatedAt = 1),
        )
        assertEquals(InventoryPolicy.snapshotVersion(first), InventoryPolicy.snapshotVersion(first.reversed()))
        assertTrue(
            InventoryPolicy.snapshotVersion(first) != InventoryPolicy.snapshotVersion(
                first.map { if (it.productId == "b") it.copy(quantity = 4) else it },
            ),
        )
    }

    private fun product(id: String, name: String, quantity: Int): ProductWithStock = ProductWithStock(
        product = Product(id, "p1", name, createdAt = 1, updatedAt = 1),
        stocks = listOf(Stock("p1", id, "s1", quantity, updatedAt = 1)),
    )
}
