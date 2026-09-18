package hr.smocnica.ui

import hr.smocnica.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentEntryTest {
    @Test fun recentListKeepsExactPackagesIncludingEmptyStockButExcludesTrash() {
        val product = Product("p", "pantry", "Mlijeko", createdAt = 1, updatedAt = 1)
        val large = ProductVariant("large", "pantry", "p", "1 l", createdAt = 1, updatedAt = 1)
        val small = large.copy(id = "small", displayName = "500 ml", createdAt = 2)
        val trash = large.copy(id = "trash", deletedAt = 5)
        val item = ProductWithStock(product, listOf(Stock("pantry", "p", "s", 0, updatedAt = 3, variantId = "large")), listOf(large, small, trash))
        assertEquals(listOf("large", "small"), recentEntries(listOf(item)).map { it.variant.id })
        assertEquals(emptyList<RecentEntry>(), recentEntries(listOf(item.copy(product = product.copy(deletedAt = 6)))))
    }
}
