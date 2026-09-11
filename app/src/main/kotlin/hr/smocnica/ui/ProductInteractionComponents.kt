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
import hr.smocnica.ui.PantryDialog as AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import hr.smocnica.core.model.ProductVariant
import hr.smocnica.core.model.Shelf
import hr.smocnica.core.model.SyncSummary
import hr.smocnica.core.model.Stock
import hr.smocnica.core.domain.GenericStockPolicy
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
private fun VariantDetailCard(
    variant: ProductVariant,
    stocks: List<Stock>,
    shelves: List<Shelf>,
    edit: () -> Unit,
    split: (() -> Unit)?,
    delete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                variant.photoUri?.let { ProductPhoto(it, variant.updatedAt, variant.displayName, Modifier.size(64.dp)) }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(variant.displayName, fontWeight = FontWeight.Bold)
                    if (variant.manufacturer.isNotBlank()) Text(variant.manufacturer)
                    Text(variant.packageLabel.ifBlank { "Veličina pakiranja nije poznata" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    variant.barcode?.let { Text("Barkod: $it", style = MaterialTheme.typography.bodySmall) }
                }
                IconButton(edit) { Icon(Icons.Outlined.Edit, "Uredi varijantu") }
                IconButton(delete) { Icon(Icons.Outlined.DeleteOutline, "Obriši varijantu") }
            }
            Text("Ukupno ${stocks.sumOf { it.quantity }} pakiranja", fontWeight = FontWeight.SemiBold)
            stocks.filter { it.quantity > 0 }.forEach { stock ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(shelves.firstOrNull { it.id == stock.shelfId }?.name ?: "Polica")
                    Text("${stock.quantity} pakiranja", fontWeight = FontWeight.Bold)
                }
            }
            split?.let { action ->
                TextButton(action) { Text("Izdvoji u novi generički artikl") }
            }
        }
    }
}

@Composable
private fun SplitVariantDialog(
    variant: ProductVariant,
    dismiss: () -> Unit,
    confirm: (String) -> Unit,
) {
    var name by remember(variant.id) { mutableStateOf(variant.displayName) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Izdvoji varijantu") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Varijanta će postati jedina varijanta novog generičkog artikla. Zalihe, police, barkod i fotografija ostaju sačuvani.")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Novi generički naziv") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { Button({ confirm(name.trim()) }, enabled = name.isNotBlank()) { Text("Izdvoji") } },
        dismissButton = { TextButton(dismiss) { Text("Odustani") } },
    )
}

@Composable
private fun VariantQuantityActionDialog(
    item: ProductWithStock,
    shelves: List<Shelf>,
    initialShelfId: String,
    adding: Boolean,
    dismiss: () -> Unit,
    apply: (String, String, Int) -> Unit,
) {
    val variants = item.variants.filter { variant ->
        variant.deletedAt == null && (adding || item.stocks.any { it.variantId == variant.id && it.quantity > 0 })
    }
    var variantId by remember(item.product.id, adding) { mutableStateOf(item.product.preferredVariantId?.takeIf { id -> variants.any { it.id == id } } ?: variants.firstOrNull()?.id.orEmpty()) }
    val availableShelves = shelves.filter { shelf -> adding || item.stocks.any { it.variantId == variantId && it.shelfId == shelf.id && it.quantity > 0 } }
    var shelfId by remember(variantId, initialShelfId) { mutableStateOf(initialShelfId.takeIf { id -> availableShelves.any { it.id == id } } ?: availableShelves.firstOrNull()?.id.orEmpty()) }
    var quantity by remember { mutableIntStateOf(1) }
    var confirmLast by remember { mutableStateOf(false) }
    val available = item.stocks.firstOrNull { it.variantId == variantId && it.shelfId == shelfId }?.quantity ?: 0
    val variantTotal = item.stocks.filter { it.variantId == variantId }.sumOf { it.quantity }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (adding) "Dodaj pakiranja" else "Izvadi pakiranja") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PairPicker("Varijanta", variants.map { it.id to variantDisplayText(it) }, variantId) { variantId = it; confirmLast = false }
            PairPicker("Polica", availableShelves.map { it.id to it.name }, shelfId) { shelfId = it; confirmLast = false }
            OutlinedTextField(quantity.toString(), { quantity = it.filter(Char::isDigit).toIntOrNull() ?: 1; confirmLast = false }, label = { Text("Broj pakiranja") })
            if (!adding) Text("Dostupno: $available pakiranja")
            if (confirmLast) Text("Vadite posljednje pakiranje ove varijante. Ponovno potvrdite.", color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { Button({
            if (!adding && quantity == variantTotal && !confirmLast) confirmLast = true
            else apply(variantId, shelfId, if (adding) quantity else -quantity)
        }, enabled = variantId.isNotBlank() && shelfId.isNotBlank() && quantity > 0 && (adding || quantity <= available)) { Text(if (adding) "Dodaj" else "Izvadi") } },
        dismissButton = { TextButton(dismiss) { Text("Odustani") } },
    )
}

@Composable
private fun VariantMoveStockDialog(
    item: ProductWithStock,
    shelves: List<Shelf>,
    initialShelfId: String,
    dismiss: () -> Unit,
    move: (String, String, String, Int) -> Unit,
) {
    val variants = item.variants.filter { variant -> item.stocks.any { it.variantId == variant.id && it.quantity > 0 } }
    var variantId by remember(item.product.id) { mutableStateOf(variants.firstOrNull()?.id.orEmpty()) }
    val sources = shelves.filter { shelf -> item.stocks.any { it.variantId == variantId && it.shelfId == shelf.id && it.quantity > 0 } }
    var from by remember(variantId, initialShelfId) { mutableStateOf(initialShelfId.takeIf { id -> sources.any { it.id == id } } ?: sources.firstOrNull()?.id.orEmpty()) }
    var to by remember(variantId, from) { mutableStateOf(shelves.firstOrNull { it.id != from }?.id.orEmpty()) }
    var quantity by remember { mutableIntStateOf(1) }
    var confirmLarge by remember { mutableStateOf(false) }
    val available = item.stocks.firstOrNull { it.variantId == variantId && it.shelfId == from }?.quantity ?: 0
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Premjesti pakiranja") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PairPicker("Varijanta", variants.map { it.id to variantDisplayText(it) }, variantId) { variantId = it; confirmLarge = false }
            PairPicker("Izvorna polica", sources.map { it.id to it.name }, from) { from = it; if (to == it) to = shelves.firstOrNull { shelf -> shelf.id != it }?.id.orEmpty(); confirmLarge = false }
            PairPicker("Odredišna polica", shelves.filterNot { it.id == from }.map { it.id to it.name }, to) { to = it; confirmLarge = false }
            OutlinedTextField(quantity.toString(), { quantity = it.filter(Char::isDigit).toIntOrNull() ?: 1; confirmLarge = false }, label = { Text("Broj pakiranja") })
            Text("Dostupno: $available pakiranja")
            if (confirmLarge) Text("Premještate veću ili cijelu količinu. Ponovno potvrdite.", color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { Button({
            if ((quantity == available || quantity >= 5) && !confirmLarge) confirmLarge = true else move(variantId, from, to, quantity)
        }, enabled = variantId.isNotBlank() && from.isNotBlank() && to.isNotBlank() && from != to && quantity in 1..available) { Text("Premjesti") } },
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
    var editingVariant by remember { mutableStateOf<ProductVariant?>(null) }
    var deletingVariant by remember { mutableStateOf<ProductVariant?>(null) }
    var splittingVariant by remember { mutableStateOf<ProductVariant?>(null) }
    var lastQuantityShelfId by rememberSaveable(productId, initialShelfId) { mutableStateOf(initialShelfId) }
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
                    Text(item.product.category, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    item.representativeVariant?.photoUri?.let { ProductPhoto(it, item.representativeVariant!!.updatedAt, item.product.name, Modifier.fillMaxWidth().height(220.dp)) }
                }
                item { OperationSyncState(sync) }
                item {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(GenericStockPolicy.summarize(item).display(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Minimum ${item.product.minimumAmountBase} · ${if (item.product.autoShopping) "automatska kupnja uključena" else "automatska kupnja isključena"}")
                            if (item.totalQuantity == 0) Text("Artikl trenutačno nema zalihe.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { Text("Varijante", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                items(item.variants.filter { it.deletedAt == null }, key = { it.id }) { variant ->
                    VariantDetailCard(
                        variant = variant,
                        stocks = item.stocks.filter { it.variantId == variant.id },
                        shelves = shelves,
                        edit = { editingVariant = variant },
                        split = if (item.variants.count { it.deletedAt == null } > 1) ({ splittingVariant = variant }) else null,
                        delete = { deletingVariant = variant },
                    )
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
        quantityAction?.let { adding -> VariantQuantityActionDialog(
            item,
            shelves,
            lastQuantityShelfId.takeIf(String::isNotBlank) ?: initialShelfId,
            adding,
            { quantityAction = null },
        ) { variantId, shelfId, delta ->
            lastQuantityShelfId = shelfId
            viewModel.adjustVariantStock(variantId, shelfId, delta) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch {
                    val result = snackbar.showSnackbar("${item.product.name}: stanje je ažurirano.", "Poništi")
                    if (result == SnackbarResult.ActionPerformed) viewModel.adjustVariantStock(variantId, shelfId, -delta)
                }
            }
            quantityAction = null
        } }
        if (moving) VariantMoveStockDialog(item, shelves, initialShelfId, dismiss = { moving = false }) { variantId, from, to, quantity ->
            viewModel.moveVariantStock(variantId, from, to, quantity) {
                scope.launch {
                    val result = snackbar.showSnackbar("Premješteno $quantity kom.", "Poništi")
                    if (result == SnackbarResult.ActionPerformed) viewModel.moveVariantStock(variantId, to, from, quantity)
                }
            }
            moving = false
        }
        if (editing) ProductEditor(
            current = item.product,
            currentVariant = item.representativeVariant,
            currentItem = item,
            shelves = shelves,
            categories = categories,
            onDismiss = { editing = false },
        ) { submission, _, _, photo, source, done ->
            viewModel.saveProduct(submission, photo, source, onSaved = { done(true) }, onFailure = { done(false) })
        }
        editingVariant?.let { variant ->
            ProductEditor(
                current = item.product,
                currentVariant = variant,
                currentItem = item,
                shelves = shelves,
                categories = categories,
                onDismiss = { editingVariant = null },
            ) { submission, _, _, photo, source, done ->
                viewModel.saveProduct(submission, photo, source, onSaved = { done(true) }, onFailure = { done(false) })
            }
        }
        deletingVariant?.let { variant ->
            ConfirmDialog(
                "Obrisati varijantu ${variant.displayName}?",
                if (item.variants.count { it.deletedAt == null } == 1) "Ovo je posljednja varijanta pa će i generički artikl biti premješten u koš." else "Ostale varijante i njihove zalihe ostaju sačuvane.",
                { deletingVariant = null },
            ) {
                viewModel.deleteVariant(variant.id)
                deletingVariant = null
            }
        }
        splittingVariant?.let { variant ->
            SplitVariantDialog(variant, { splittingVariant = null }) { name ->
                viewModel.splitVariant(variant.id, name) {
                    scope.launch { snackbar.showSnackbar("Varijanta je izdvojena u ${it.name}.") }
                }
                splittingVariant = null
            }
        }
        if (deleting) ConfirmDialog("Obrisati ${item.product.name}?", "Artikl se može vratiti iz koša tijekom 30 dana.", { deleting = false }) { viewModel.deleteProduct(item.product); deleting = false; close() }
    }
}

@Composable
internal fun PairPicker(label: String, options: List<Pair<String, String>>, selected: String, select: (String) -> Unit) {
    PantryPicker(label, options, selected, select)
}
