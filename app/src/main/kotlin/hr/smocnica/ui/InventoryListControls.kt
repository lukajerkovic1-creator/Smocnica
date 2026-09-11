package hr.smocnica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hr.smocnica.core.model.ProductWithStock
import hr.smocnica.core.model.Shelf
import java.text.Collator
import java.util.Locale

internal enum class InventoryOrder(val label: String) {
    NAME("Abecedno"), NEWEST("Najnovije dodano"), SHELF("Po policama"),
}

@Composable
internal fun InventoryAddButton(add: () -> Unit) {
    FloatingActionButton(onClick = add, containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary, shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
        Icon(Icons.Outlined.Add, "Dodaj artikl")
    }
}

@Composable
internal fun InventorySearchField(query: String, change: (String) -> Unit) {
    TextField(query, change, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("Pretraži artikle") },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp), singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
    )
}

internal fun orderInventory(items: List<ProductWithStock>, shelves: List<Shelf>, order: InventoryOrder): List<ProductWithStock> {
    val collator = Collator.getInstance(Locale.forLanguageTag("hr"))
    val byName = Comparator<ProductWithStock> { a, b -> collator.compare(a.product.name, b.product.name) }
        .thenBy { it.product.id }
    val shelfOrder = shelves.sortedWith(compareBy<Shelf> { it.sortOrder }.thenBy { it.id }).mapIndexed { index, shelf -> shelf.id to index }.toMap()
    return items.sortedWith(when (order) {
        InventoryOrder.NAME -> byName
        InventoryOrder.NEWEST -> compareByDescending<ProductWithStock> { it.product.createdAt }.then(byName)
        InventoryOrder.SHELF -> compareBy<ProductWithStock> { item ->
            item.stocks.filter { it.quantity > 0 }.mapNotNull { shelfOrder[it.shelfId] }.minOrNull() ?: Int.MAX_VALUE
        }.then(byName)
    })
}

@Composable
internal fun InventoryListControls(
    shelves: List<Shelf>, selectedShelfId: String?, order: InventoryOrder,
    selectShelf: (String?) -> Unit, selectOrder: (InventoryOrder) -> Unit, moreFilters: () -> Unit,
) {
    var shelfMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      androidx.compose.foundation.layout.FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            AssistChip({ shelfMenu = true }, { Text(shelves.firstOrNull { it.id == selectedShelfId }?.name ?: "Sve police", fontWeight = androidx.compose.ui.text.font.FontWeight.Normal) },
                shape = CircleShape,
                trailingIcon = { Icon(Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurface) })
            DropdownMenu(shelfMenu, { shelfMenu = false }) {
                DropdownMenuItem({ Text("Sve police") }, { shelfMenu = false; selectShelf(null) })
                shelves.forEach { shelf -> DropdownMenuItem({ Text(shelf.name) }, { shelfMenu = false; selectShelf(shelf.id) }) }
            }
        }
        Box {
            AssistChip({ sortMenu = true }, { Text(order.label, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal) }, shape = CircleShape,
                trailingIcon = { Icon(Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurface) })
            DropdownMenu(sortMenu, { sortMenu = false }) {
                InventoryOrder.entries.forEach { option -> DropdownMenuItem({ Text(option.label) }, { sortMenu = false; selectOrder(option) }) }
            }
        }
      }
      IconButton(moreFilters) { Icon(Icons.Outlined.FilterList, "Dodatni filtri") }
    }
}

