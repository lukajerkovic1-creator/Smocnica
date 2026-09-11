package hr.smocnica.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hr.smocnica.core.model.ProductWithStock
import hr.smocnica.core.model.Shelf

internal data class InventoryShelfGroup(val shelf: Shelf?, val products: List<ProductWithStock>)

internal fun groupInventoryByShelf(
    products: List<ProductWithStock>, shelves: List<Shelf>, filteredShelfIds: Set<String> = emptySet(),
): List<InventoryShelfGroup> {
    val visibleShelves = shelves.filter { filteredShelfIds.isEmpty() || it.id in filteredShelfIds }
        .sortedWith(compareBy<Shelf> { it.sortOrder }.thenBy { it.id })
    val grouped = orderInventory(products, shelves, InventoryOrder.NAME).groupBy { product ->
        val occupied = product.stocks.filter { it.quantity > 0 }.map { it.shelfId }.toSet()
        visibleShelves.firstOrNull { it.id in occupied }?.id
    }
    return visibleShelves.mapNotNull { shelf -> grouped[shelf.id]?.let { InventoryShelfGroup(shelf, it) } } +
        listOfNotNull(grouped[null]?.let { InventoryShelfGroup(null, it) })
}

internal fun LazyListScope.inventoryRows(
    products: List<ProductWithStock>, shelves: List<Shelf>, order: InventoryOrder,
    filteredShelfIds: Set<String> = emptySet(), shelfActions: ShelfManagementActions? = null, row: @Composable (ProductWithStock) -> Unit,
) {
    if (order == InventoryOrder.SHELF) {
        groupInventoryByShelf(products, shelves, filteredShelfIds).forEach { group ->
            item(key = "shelf-header:${group.shelf?.id ?: "unassigned"}", contentType = "shelf-header") {
                InventoryShelfHeader(group.shelf?.name ?: "Bez zalihe na policama", group.shelf, shelfActions)
            }
            items(group.products, key = { "product:${it.product.id}" }, contentType = { "product" }) { row(it) }
        }
    } else {
        items(orderInventory(products, shelves, order), key = { "product:${it.product.id}" }, contentType = { "product" }) { row(it) }
    }
}

@Composable
private fun InventoryShelfHeader(name: String, shelf: Shelf?, actions: ShelfManagementActions?) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(name, modifier = Modifier.weight(1f).semantics { heading() }.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        if (shelf != null && actions != null) ShelfActionMenu(shelf, actions)
        }
    }
}
