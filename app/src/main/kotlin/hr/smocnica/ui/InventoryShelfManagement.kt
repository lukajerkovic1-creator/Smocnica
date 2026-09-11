package hr.smocnica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hr.smocnica.core.model.ProductWithStock
import hr.smocnica.core.model.Shelf

internal data class ShelfManagementActions(val add: () -> Unit, val rename: (Shelf) -> Unit, val delete: (Shelf) -> Unit)

internal fun shelfProductCount(products: List<ProductWithStock>, shelfId: String): Int =
    products.count { item -> item.stocks.any { it.shelfId == shelfId && it.quantity > 0 } }

@Composable
internal fun ShelfActionMenu(shelf: Shelf, actions: ShelfManagementActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton({ expanded = true }) { Icon(Icons.Outlined.MoreVert, "Radnje police ${shelf.name}") }
        DropdownMenu(expanded, { expanded = false }, shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(shelf.name, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            HorizontalDivider()
            DropdownMenuItem({ Text("Preimenuj") }, { expanded = false; actions.rename(shelf) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
            DropdownMenuItem({ Text("Obriši policu", color = MaterialTheme.colorScheme.error) }, { expanded = false; actions.delete(shelf) },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) })
        }
    }
}

@Composable
internal fun InventoryShelfSelector(
    shelves: List<Shelf>, selectedShelfId: String?, products: List<ProductWithStock>,
    select: (String?) -> Unit, actions: ShelfManagementActions?,
) {
    var expanded by remember { mutableStateOf(false) }
    val closeActions = actions?.let { callbacks -> ShelfManagementActions(
        { expanded = false; callbacks.add() }, { expanded = false; callbacks.rename(it) }, { expanded = false; callbacks.delete(it) }) }
    Box {
        AssistChip({ expanded = true }, { Text(shelves.firstOrNull { it.id == selectedShelfId }?.name ?: "Sve police") },
            shape = androidx.compose.foundation.shape.CircleShape, trailingIcon = { Icon(Icons.Outlined.ExpandMore, null) })
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.widthIn(min = 260.dp, max = 320.dp),
            shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            DropdownMenuItem({ Text("Sve police") }, { expanded = false; select(null) },
                trailingIcon = if (selectedShelfId.isNullOrBlank()) ({ Icon(Icons.Outlined.Check, null) }) else null)
            HorizontalDivider()
            shelves.forEach { shelf ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { expanded = false; select(shelf.id) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(shelf.name, style = MaterialTheme.typography.bodyLarge)
                        if (actions != null) {
                            val count = shelfProductCount(products, shelf.id)
                            Text(if (count == 0) "Prazna polica" else "$count ${if (count == 1) "artikl" else if (count % 10 in 2..4 && count % 100 !in 12..14) "artikla" else "artikala"}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (selectedShelfId == shelf.id) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    closeActions?.let { ShelfActionMenu(shelf, it) }
                }
            }
            closeActions?.let {
                HorizontalDivider()
                DropdownMenuItem({ Text("Dodaj policu", color = MaterialTheme.colorScheme.primary) }, it.add,
                    leadingIcon = { Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.primary) })
            }
        }
    }
}

@Composable
internal fun InventoryShelfDeleteDialog(
    shelf: Shelf, products: List<ProductWithStock>, shelves: List<Shelf>, dismiss: () -> Unit,
    delete: () -> Unit, move: (String) -> Unit,
) {
    val occupied = shelfProductCount(products, shelf.id) > 0
    val targets = shelves.filterNot { it.id == shelf.id }
    var target by remember(shelf.id) { mutableStateOf(targets.firstOrNull()?.id.orEmpty()) }
    PantryDialog(onDismissRequest = dismiss, title = { Text(if (occupied) "Polica nije prazna" else "Obrisati policu?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(shelf.name, style = MaterialTheme.typography.titleMedium)
            if (occupied) {
                Text("Premjestite artikle na drugu policu, zatim ponovno odaberite brisanje.")
                if (targets.isEmpty()) Text("Najprije dodajte drugu policu.")
                else PantryPicker("Odredišna polica", targets.map { it.id to it.name }, target) { target = it }
            } else Text("Prazna polica premjestit će se u koš na 30 dana.")
        } },
        confirmButton = {
            Button({ if (occupied) move(target) else delete() }, enabled = !occupied || targets.any { it.id == target }) {
                Text(if (occupied) "Premjesti artikle" else "Obriši policu")
            }
        }, dismissButton = { TextButton(dismiss) { Text("Odustani") } })
}
