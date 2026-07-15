package hr.smocnica.ui

import hr.smocnica.core.model.Product
import hr.smocnica.core.model.ProductWithStock
import hr.smocnica.core.model.Shelf
import hr.smocnica.core.model.Stock
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductQuantityTextTest {
    @Test
    fun selectedShelfShowsShelfAndTotalQuantity() {
        val item = ProductWithStock(
            Product("product1", "p1", "Sok", createdAt = 1, updatedAt = 1),
            listOf(
                Stock("p1", "product1", "s1", 2, updatedAt = 1),
                Stock("p1", "product1", "s2", 3, updatedAt = 1),
            ),
        )

        assertEquals(
            "2 kom na polici · 5 ukupno",
            productQuantityText(item, listOf(Shelf("s1", "p1", "Polica 1", 0, createdAt = 1, updatedAt = 1)), "s1"),
        )
    }
}
