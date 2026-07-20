package hr.smocnica.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hr.smocnica.MainViewModel
import hr.smocnica.core.model.ProductWithStock
import hr.smocnica.core.model.Shelf
import hr.smocnica.core.model.SyncSummary
import kotlinx.coroutines.launch

@Composable
internal fun OperationSyncState(summary: SyncSummary) {
    if (summary.isFullySynced) return
    val problem = summary.conflicts + summary.failed > 0
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (problem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            when {
                summary.conflicts > 0 -> "${summary.conflicts} konflikata čeka rješavanje"
                summary.failed > 0 -> "${summary.failed} promjena nije sinkronizirano"
                summary.syncing > 0 -> "Sinkronizacija u tijeku"
                else -> "${summary.pending} promjena čeka mrežu"
            },
            Modifier.padding(12.dp),
            color = if (problem) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
internal fun QuantityActionDialog(
    item: ProductWithStock,
    shelves: List<Shelf>,
    initialShelfId: String,
    adding: Boolean,
    dismiss: () -> Unit,
    apply: (String, Int) -> Unit,
) {
    var shelfId by remember(item.product.id, initialShelfId) {
        mutableStateOf(initialShelfId.takeIf { id -> shelves.any { it.id == id } } ?: item.stocks.firstOrNull { it.quantity > 0 }?.shelfId ?: shelves.firstOrNull()?.id.orEmpty())
    }
    var quantity by remember { mutableIntStateOf(1) }
    var confirmLastRemoval by remember { mutableStateOf(false) }
    val available = item.stocks.firstOrNull { it.shelfId == shelfId }?.quantity ?: 0
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (adding) "Dodaj ${item.product.name}" else "Izvadi ${item.product.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PairPicker("Polica", shelves.map { it.id to it.name }, shelfId) { shelfId = it; confirmLastRemoval = false }
                OutlinedTextField(
                    quantity.toString(),
                    { quantity = it.filter(Char::isDigit).toIntOrNull() ?: 1; confirmLastRemoval = false },
                    label = { Text("Količina") },
                )
                if (!adding) Text("Dostupno na polici: $available kom", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (confirmLastRemoval) Text("Vadite zadnji komad artikla. Ponovno pritisnite Izvadi.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!adding && quantity == item.totalQuantity && !confirmLastRemoval) confirmLastRemoval = true
                    else apply(shelfId, if (adding) quantity else -quantity)
                },
                enabled = shelfId.isNotBlank() && quantity > 0 && (adding || quantity <= available),
            ) { Text(if (adding) "Dodaj" else "Izvadi") }
        },
        dismissButton = { TextButton(dismiss) { Text("Odustani") } },
    )
}

@Composable
internal fun MoveStockDialog(
    item: ProductWithStock,
    shelves: List<Shelf>,
    initialFromShelfId: String = "",
    initialToShelfId: String = "",
    dismiss: () -> Unit,
    move: (String, String, Int) -> Unit,
) {
    val availableSources = shelves.filter { shelf -> (item.stocks.firstOrNull { it.shelfId == shelf.id }?.quantity ?: 0) > 0 }
    var fromShelfId by remember(item.product.id, initialFromShelfId) {
        mutableStateOf(initialFromShelfId.takeIf { id -> availableSources.any { it.id == id } } ?: availableSources.firstOrNull()?.id.orEmpty())
    }
    var toShelfId by remember(item.product.id, initialToShelfId) {
        mutableStateOf(initialToShelfId.takeIf { id -> shelves.any { it.id == id } && id != fromShelfId } ?: shelves.firstOrNull { it.id != fromShelfId }?.id.orEmpty())
    }
    var quantity by remember { mutableIntStateOf(1) }
    var confirmLargeMove by remember { mutableStateOf(false) }
    val available = item.stocks.firstOrNull { it.shelfId == fromShelfId }?.quantity ?: 0
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Premjesti ${item.product.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PairPicker("Izvorna polica", availableSources.map { it.id to it.name }, fromShelfId) {
                    fromShelfId = it
                    if (toShelfId == it) toShelfId = shelves.firstOrNull { shelf -> shelf.id != it }?.id.orEmpty()
                    quantity = quantity.coerceAtMost(item.stocks.firstOrNull { stock -> stock.shelfId == it }?.quantity ?: 1)
                    confirmLargeMove = false
                }
                PairPicker("Odredišna polica", shelves.filterNot { it.id == fromShelfId }.map { it.id to it.name }, toShelfId) { toShelfId = it; confirmLargeMove = false }
                OutlinedTextField(quantity.toString(), { quantity = it.filter(Char::isDigit).toIntOrNull() ?: 1; confirmLargeMove = false }, label = { Text("Količina") })
                Text("Dostupno: $available kom", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (confirmLargeMove) Text("Premještate ${if (quantity == available) "cijelu količinu" else "$quantity kom"}. Ponovno pritisnite Premjesti za potvrdu.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val isLarge = quantity == available || quantity >= 5
                    if (isLarge && !confirmLargeMove) confirmLargeMove = true else move(fromShelfId, toShelfId, quantity)
                },
                enabled = fromShelfId.isNotBlank() && toShelfId.isNotBlank() && fromShelfId != toShelfId && quantity in 1..available,
            ) { Text("Premjesti") }
        },
        dismissButton = { TextButton(dismiss) { Text("Odustani") } },
    )
}

@Composable
internal fun ContextEmptyState(
    text: String,
    scan: () -> Unit,
    add: () -> Unit,
    move: (() -> Unit)? = null,
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(scan) { Icon(Icons.Outlined.QrCodeScanner, null); Text("Skeniraj artikl", Modifier.padding(start = 8.dp)) }
            OutlinedButton(add) { Icon(Icons.Outlined.Add, null); Text("Dodaj ručno", Modifier.padding(start = 8.dp)) }
            move?.let { action -> OutlinedButton(action) { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null); Text("Premjesti postojeći artikl ovamo", Modifier.padding(start = 8.dp)) } }
        }
    }
}

@Composable
fun ProductDetailScreen(
    viewModel: MainViewModel,
    padding: PaddingValues,
    productId: String,
    initialShelfId: String,
    scan: (ScannerMode) -> Unit,
    close: () -> Unit,
) {
    val products by viewModel.allProducts.collectAsStateWithLifecycle()
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val sync by viewModel.syncSummary.collectAsStateWithLifecycle()
    val item = products.firstOrNull { it.product.id == productId }
    var quantityAction by remember { mutableStateOf<Boolean?>(null) }
    var moving by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    SecondaryScreenScaffold(
        title = item?.product?.name ?: "Detalj artikla",
        outerPadding = padding,
        onBack = close,
        snackbarHostState = snackbar,
    ) { inner ->
        if (item == null) {
            Column(Modifier.fillMaxSize().padding(inner).padding(20.dp)) {
                Text("Artikl nije dostupan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Možda je obrisan ili još nije sinkroniziran.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = inner.calculateTopPadding() + 12.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(item.product.description.ifBlank { item.product.category }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    item.product.photoUri?.let { ProductPhoto(it, item.product.updatedAt, item.product.name, Modifier.fillMaxWidth().height(220.dp)) }
                }
                item { OperationSyncState(sync) }
                item {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Ukupno ${item.totalQuantity} kom", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Minimum ${item.product.minimumQuantity} · ${if (item.product.autoShopping) "automatska kupnja uključena" else "automatska kupnja isključena"}")
                            item.product.barcode?.let { Text("Barkod: $it") }
                            HorizontalDivider()
                            item.stocks.filter { it.quantity > 0 }.forEach { stock ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(shelves.firstOrNull { it.id == stock.shelfId }?.name ?: "Polica")
                                    Text("${stock.quantity} kom", fontWeight = FontWeight.Bold)
                                }
                            }
                            if (item.totalQuantity == 0) Text("Artikl trenutačno nema zalihe.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip({ quantityAction = true }, { Text("+ Dodaj") })
                        AssistChip({ quantityAction = false }, { Text("− Izvadi") }, enabled = item.totalQuantity > 0)
                        AssistChip({ moving = true }, { Text("Premjesti") }, enabled = item.totalQuantity > 0 && shelves.size > 1)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ scan(ScannerMode.DEFAULT) }, Modifier.weight(1f)) { Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(20.dp)); Text("Skeniraj", Modifier.padding(start = 6.dp)) }
                        OutlinedButton({ editing = true }, Modifier.weight(1f)) { Icon(Icons.Outlined.Edit, null, Modifier.size(20.dp)); Text("Uredi", Modifier.padding(start = 6.dp)) }
                    }
                }
                item {
                    Button({ viewModel.addShopping(item.product.name, item.product.categoryId, 1) }, Modifier.fillMaxWidth()) { Text("Dodaj na popis za kupnju") }
                    TextButton({ deleting = true }, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.DeleteOutline, null); Text("Obriši artikl", Modifier.padding(start = 6.dp)) }
                }
            }
        }
    }
    if (item != null) {
        quantityAction?.let { adding -> QuantityActionDialog(item, shelves, initialShelfId, adding, { quantityAction = null }) { shelfId, delta ->
            viewModel.adjustStock(item.product.id, shelfId, delta) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch {
                    val result = snackbar.showSnackbar("${item.product.name}: stanje je ažurirano.", "Poništi")
                    if (result == SnackbarResult.ActionPerformed) viewModel.adjustStock(item.product.id, shelfId, -delta)
                }
            }
            quantityAction = null
        } }
        if (moving) MoveStockDialog(item, shelves, initialShelfId, dismiss = { moving = false }) { from, to, quantity ->
            viewModel.moveStock(item.product.id, from, to, quantity) {
                scope.launch {
                    val result = snackbar.showSnackbar("Premješteno $quantity kom.", "Poništi")
                    if (result == SnackbarResult.ActionPerformed) viewModel.moveStock(item.product.id, to, from, quantity)
                }
            }
            moving = false
        }
        if (editing) ProductEditor(item.product, shelves, categories, { editing = false }) { product, _, _, photo, source, done ->
            viewModel.saveProduct(product, photo, source, onSaved = { done(true) }, onFailure = { done(false) })
        }
        if (deleting) ConfirmDialog("Obrisati ${item.product.name}?", "Artikl se može vratiti iz koša tijekom 30 dana.", { deleting = false }) { viewModel.deleteProduct(item.product); deleting = false; close() }
    }
}

@Composable
internal fun PairPicker(label: String, options: List<Pair<String, String>>, selected: String, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth(), enabled = options.isNotEmpty()) {
            Text("$label: ${options.firstOrNull { it.first == selected }?.second ?: "Odaberite"}")
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { option -> DropdownMenuItem({ Text(option.second) }, { select(option.first); expanded = false }) }
        }
    }
}
