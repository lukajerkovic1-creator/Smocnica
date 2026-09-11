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

    @Test fun shelfGroupsRespectFiltersAndShowEachProductOnce() {
        val multi = item("multi", "Žito", 1, "s1" to 1, "s2" to 2)
        val first = item("first", "Brašno", 1, "s1" to 1)
        val second = item("second", "Riža", 1, "s2" to 1)
        val empty = item("empty", "Čaj", 1, "s1" to 0)
        val groups = groupInventoryByShelf(listOf(multi, empty, second, first), shelves)
        assertEquals(listOf("s1", "s2", null), groups.map { it.shelf?.id })
        assertEquals(listOf(first, multi, second, empty), groups.flatMap { it.products })
        assertEquals(emptyList<InventoryShelfGroup>(), groupInventoryByShelf(emptyList(), shelves))
        assertEquals(listOf("s2"), groupInventoryByShelf(listOf(second), shelves).map { it.shelf?.id })
        val filtered = groupInventoryByShelf(listOf(multi), shelves, setOf("s2"))
        assertEquals("s2", filtered.single().shelf?.id)
        assertEquals(listOf(multi), filtered.single().products)
    }

    @Test fun shelfOrderIncludesAllVariantsAndPutsUnstockedProductsLast() {
        val items = listOf(item("empty", "A", 1, "s1" to 0), item("second", "B", 1, "s2" to 2), item("first", "C", 1, "s2" to 0, "s1" to 3))
        assertEquals(listOf("first", "second", "empty"), orderInventory(items, shelves, InventoryOrder.SHELF).map { it.product.id })
    }

    @Test fun shelfCountCountsProductsOnceAcrossVariantsAndIgnoresZeroStock() {
        val products = listOf(item("a", "A", 1, "s1" to 2, "s1" to 3, "s2" to 1), item("b", "B", 1, "s1" to 0))
        assertEquals(1, shelfProductCount(products, "s1"))
        assertEquals(1, shelfProductCount(products, "s2"))
        assertEquals(0, shelfProductCount(products, "missing"))
    }
}
