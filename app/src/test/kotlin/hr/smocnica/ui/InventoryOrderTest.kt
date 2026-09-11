package hr.smocnica.ui

import hr.smocnica.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class InventoryOrderTest {
    private val shelves = listOf(Shelf("s2", "p", "Druga", 1, createdAt = 0, updatedAt = 0), Shelf("s1", "p", "Prva", 0, createdAt = 0, updatedAt = 0))
    private fun item(id: String, name: String, time: Long, vararg stocks: Pair<String, Int>) = ProductWithStock(
        Product(id, "p", name, createdAt = time, updatedAt = 999),
        stocks.mapIndexed { index, (shelf, quantity) -> Stock("p", id, shelf, quantity, updatedAt = 1, variantId = "$id-$index") },
    )

    @Test fun ordersByCreationAndCroatianAlphabetWithStableTies() {
        val items = listOf(item("z", "Žito", 1), item("s", "Šećer", 2), item("c", "Čaj", 1), item("a", "Brašno", 1))
        assertEquals(listOf("a", "c", "s", "z"), orderInventory(items, shelves, InventoryOrder.NAME).map { it.product.id })
        assertEquals(listOf("s", "a", "c", "z"), orderInventory(items, shelves, InventoryOrder.NEWEST).map { it.product.id })
    }

    @Test fun shelfOrderIncludesAllVariantsAndPutsUnstockedProductsLast() {
        val items = listOf(item("empty", "A", 1, "s1" to 0), item("second", "B", 1, "s2" to 2), item("first", "C", 1, "s2" to 0, "s1" to 3))
        assertEquals(listOf("first", "second", "empty"), orderInventory(items, shelves, InventoryOrder.SHELF).map { it.product.id })
    }
}
