package hr.smocnica.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import hr.smocnica.MainViewModel
import hr.smocnica.core.model.Category
import hr.smocnica.core.model.Product
import hr.smocnica.core.model.ProductFilter
import hr.smocnica.core.model.ProductWithStock
import hr.smocnica.core.model.Shelf
import hr.smocnica.core.model.ShoppingItem
import hr.smocnica.core.model.PhotoSource
import hr.smocnica.ui.theme.Purple
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun StocksScreen(
    viewModel: MainViewModel,
    padding: PaddingValues,
    initialShelfId: String = "",
    initialAction: String = "",
    initialFilter: String = "",
    scan: () -> Unit,
    openProduct: (String) -> Unit,
    lookup: ScannerLookupViewModel = hiltViewModel(),
) {
    val products by viewModel.products.collectAsStateWithLifecycle()
    val allProducts by viewModel.allProducts.collectAsStateWithLifecycle()
    val deletedProducts by viewModel.deletedProducts.collectAsStateWithLifecycle()
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val sync by viewModel.syncSummary.collectAsStateWithLifecycle()
    val catalogLookup by lookup.state.collectAsStateWithLifecycle()
    var activeFilter by remember(initialShelfId, initialFilter) {
        mutableStateOf(
            ProductFilter(
                shelfIds = initialShelfId.takeIf(String::isNotBlank)?.let(::setOf) ?: emptySet(),
                belowMinimumOnly = initialFilter == "belowMinimum",
                onShoppingListOnly = initialFilter == "shopping",
            ),
        )
    }
    var showFilters by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf<Product?>(null) }
    var creating by rememberSaveable(initialAction) { mutableStateOf(initialAction == "new") }
    var movingProduct by remember { mutableStateOf<ProductWithStock?>(null) }
    var moveDestinationId by remember { mutableStateOf("") }
    var chooseMoveProduct by remember { mutableStateOf(initialAction == "move") }
    var deletingProduct by remember { mutableStateOf<Product?>(null) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var selecting by remember { mutableStateOf(false) }
    var bulkMove by remember { mutableStateOf(false) }
    var bulkCategory by remember { mutableStateOf(false) }
    var bulkDelete by remember { mutableStateOf(false) }
    var lastRemoval by remember { mutableStateOf<Pair<ProductWithStock, String>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val selectedShelf = shelves.firstOrNull { it.id == initialShelfId }

    LaunchedEffect(initialShelfId, initialFilter) { viewModel.updateFilter(activeFilter) }
    DisposableEffect(Unit) { onDispose { viewModel.updateFilter(ProductFilter()) } }

    fun quickAdjust(item: ProductWithStock, delta: Int) {
        val shelfId = initialShelfId.takeIf(String::isNotBlank)
            ?: item.stocks.firstOrNull { it.quantity > 0 }?.shelfId
            ?: shelves.firstOrNull()?.id
            ?: return
        val available = item.stocks.firstOrNull { it.shelfId == shelfId }?.quantity ?: 0
        if (delta < 0 && available == 1) {
            lastRemoval = item to shelfId
            return
        }
        if (delta < 0 && available == 0) return
        viewModel.adjustStock(item.product.id, shelfId, delta) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch {
                val result = snackbar.showSnackbar(if (delta > 0) "Dodano 1 kom." else "Izvađen 1 kom.", "Poništi")
                if (result == SnackbarResult.ActionPerformed) viewModel.adjustStock(item.product.id, shelfId, -delta)
            }
        }
    }

    Scaffold(
        modifier = Modifier.padding(padding),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Skeniraj") },
                icon = { Icon(Icons.Outlined.QrCodeScanner, null) },
                onClick = scan,
            )
        },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = inner.calculateTopPadding() + 12.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ScreenTitle(selectedShelf?.name ?: "Sve zalihe", if (selectedShelf != null) "Artikli i količine na ovoj polici" else "Pretražite i uredite artikle") }
            item { OperationSyncState(sync) }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(scan) { Icon(Icons.Outlined.QrCodeScanner, null); Text("Skeniraj artikl", Modifier.padding(start = 6.dp)) }
                    OutlinedButton({ creating = true }) { Icon(Icons.Outlined.Add, null); Text("Dodaj ručno", Modifier.padding(start = 6.dp)) }
                    if (selectedShelf != null) OutlinedButton({ chooseMoveProduct = true }) { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null); Text("Premjesti ovamo", Modifier.padding(start = 6.dp)) }
                    OutlinedButton({ selecting = !selecting; if (!selecting) selectedIds = emptySet() }) { Text(if (selecting) "Završi odabir" else "Odaberi više") }
                }
            }
            item {
                OutlinedTextField(
                    activeFilter.query,
                    { activeFilter = activeFilter.copy(query = it); viewModel.updateFilter(activeFilter) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    label = { Text("Pretraži naziv") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item { AssistChip({ showFilters = true }, { Text("Filtri") }, leadingIcon = { Icon(Icons.Outlined.FilterList, null) }) }
            if (selectedIds.isNotEmpty()) item {
                BulkActionBar(
                    selectedIds.size,
                    { bulkMove = true },
                    { viewModel.addProductsToShopping(allProducts.filter { it.product.id in selectedIds }); selectedIds = emptySet(); selecting = false },
                    { bulkCategory = true },
                    { bulkDelete = true },
                    { selectedIds = emptySet(); selecting = false },
                )
            }
            items(products, key = { it.product.id }) { item ->
                ProductCard(
                    item,
                    shelves,
                    initialShelfId.takeIf(String::isNotBlank),
                    item.product.id in selectedIds,
                    selecting,
                    { if (!selecting) openProduct(item.product.id) else selectedIds = selectedIds.toggle(item.product.id) },
                    { selecting = true; selectedIds = selectedIds.toggle(item.product.id) },
                    { quickAdjust(item, 1) },
                    { quickAdjust(item, -1) },
                    { moveDestinationId = ""; movingProduct = item },
                    { showEditor = item.product },
                    { deletingProduct = item.product },
                )
            }
            if (products.isEmpty()) item { ContextEmptyState("Nema artikala za odabrane filtre.", scan, { creating = true }, if (selectedShelf != null) ({ chooseMoveProduct = true }) else null) }
        }
    }
    if (creating) ProductEditor(
        current = null,
        shelves = shelves,
        categories = categories,
        onDismiss = { creating = false },
        initialShelfId = initialShelfId,
        activeProducts = allProducts,
        deletedProducts = deletedProducts,
        catalogLookup = catalogLookup,
        requestCatalogLookup = lookup::lookup,
        continueManually = lookup::continueManually,
        onAddExisting = { item, shelf, quantity, done ->
            viewModel.adjustStock(
                item.product.id,
                shelf,
                quantity,
                onAdjusted = {
                    done(true)
                    scope.launch {
                        val result = snackbar.showSnackbar("${item.product.name}: dodano $quantity kom.", "Poništi")
                        if (result == SnackbarResult.ActionPerformed) viewModel.adjustStock(item.product.id, shelf, -quantity)
                    }
                },
                onFailure = { done(false) },
            )
        },
        onRestoreDeleted = { item, shelf, quantity, done ->
            viewModel.restoreProductAndAddStock(
                item.product.id,
                shelf,
                quantity,
                onRestored = {
                    done(true)
                    scope.launch {
                        val result = snackbar.showSnackbar("${item.product.name}: vraćen iz koša i dodan na policu.", "Poništi")
                        if (result == SnackbarResult.ActionPerformed) viewModel.undoRestoreProductAndAddStock(item.product, shelf, quantity)
                    }
                },
                onFailure = { done(false) },
            )
        },
    ) { product, shelf, quantity, photo, source, done ->
        viewModel.createProductAndStock(
            product,
            shelf,
            quantity,
            photo,
            source,
            onCreated = { created ->
                done(true)
                scope.launch {
                    val result = snackbar.showSnackbar("${created.name}: dodano na policu.", "Poništi")
                    if (result == SnackbarResult.ActionPerformed) viewModel.deleteProduct(created)
                }
            },
            onFailure = { done(false) },
        )
    }
    showEditor?.let { current ->
        ProductEditor(current, shelves, categories, onDismiss = { showEditor = null }) { product, _, _, photo, source, done ->
            viewModel.saveProduct(product, photo, source, onSaved = { done(true) }, onFailure = { done(false) })
        }
    }
    movingProduct?.let { item ->
        val preferredSource = if (moveDestinationId.isNotBlank()) {
            item.stocks.firstOrNull { it.quantity > 0 && it.shelfId != moveDestinationId }?.shelfId.orEmpty()
        } else initialShelfId
        MoveStockDialog(
            item,
            shelves,
            initialFromShelfId = preferredSource,
            initialToShelfId = moveDestinationId,
            dismiss = { movingProduct = null; moveDestinationId = "" },
        ) { from, to, quantity ->
        viewModel.moveStock(item.product.id, from, to, quantity) {
            scope.launch {
                val result = snackbar.showSnackbar("Premješteno $quantity kom.", "Poništi")
                if (result == SnackbarResult.ActionPerformed) viewModel.moveStock(item.product.id, to, from, quantity)
            }
        }
        movingProduct = null
        moveDestinationId = ""
    } }
    if (chooseMoveProduct && selectedShelf != null) ProductPickerDialog(
        "Premjesti na ${selectedShelf.name}",
        allProducts.filter { item -> item.stocks.any { it.quantity > 0 && it.shelfId != selectedShelf.id } },
        { chooseMoveProduct = false },
    ) { item -> moveDestinationId = selectedShelf.id; movingProduct = item; chooseMoveProduct = false }
    if (bulkMove) BulkMoveDialog(allProducts.filter { it.product.id in selectedIds }, shelves, initialShelfId, { bulkMove = false }) { from, to ->
        viewModel.moveProducts(allProducts.filter { it.product.id in selectedIds }, from, to); selectedIds = emptySet(); selecting = false; bulkMove = false
    }
    if (bulkCategory) BulkCategoryDialog(categories.map { it.name }.ifEmpty { listOf("Ostalo") }, { bulkCategory = false }) { category ->
        viewModel.changeProductsCategory(allProducts.filter { it.product.id in selectedIds }, category); selectedIds = emptySet(); selecting = false; bulkCategory = false
    }
    if (bulkDelete) ConfirmDialog("Obrisati ${selectedIds.size} artikala?", "Artikli će biti premješteni u koš na 30 dana.", { bulkDelete = false }) {
        viewModel.deleteProducts(allProducts.filter { it.product.id in selectedIds }); selectedIds = emptySet(); selecting = false; bulkDelete = false
    }
    lastRemoval?.let { (item, shelfId) -> ConfirmDialog("Izvaditi zadnji komad?", "${item.product.name} više neće biti na ovoj polici.", { lastRemoval = null }) {
        viewModel.adjustStock(item.product.id, shelfId, -1) {
            scope.launch {
                val result = snackbar.showSnackbar("Izvađen je zadnji komad.", "Poništi")
                if (result == SnackbarResult.ActionPerformed) viewModel.adjustStock(item.product.id, shelfId, 1)
            }
        }
        lastRemoval = null
    } }
    if (showFilters) ProductFilterDialog(activeFilter, shelves, categories, { showFilters = false }) { value -> activeFilter = value; viewModel.updateFilter(value); showFilters = false }
    deletingProduct?.let { product -> ConfirmDialog("Obrisati artikl ${product.name}?", "Artikl, njegove lokacije i fotografija ostaju dostupni za vraćanje iz koša 30 dana.", { deletingProduct = null }) { viewModel.deleteProduct(product); deletingProduct = null } }
}

internal fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id

@Composable
private fun BulkActionBar(count: Int, move: () -> Unit, shopping: () -> Unit, category: () -> Unit, delete: () -> Unit, clear: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Odabrano: $count", fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(move) { Text("Premjesti") }
                OutlinedButton(shopping) { Text("Na kupnju") }
                OutlinedButton(category) { Text("Kategorija") }
                OutlinedButton(delete) { Text("Obriši") }
                TextButton(clear) { Text("Odustani") }
            }
        }
    }
}

@Composable
private fun ProductPickerDialog(title: String, products: List<ProductWithStock>, dismiss: () -> Unit, select: (ProductWithStock) -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(products, key = { it.product.id }) { item ->
                    OutlinedButton({ select(item) }, Modifier.fillMaxWidth()) {
                        Text("${item.product.name} · ${item.totalQuantity} kom")
                    }
                }
                if (products.isEmpty()) item { Text("Nema artikala koje je moguće premjestiti.") }
            }
        },
        confirmButton = { TextButton(dismiss) { Text("Zatvori") } },
    )
}

@Composable
private fun BulkMoveDialog(products: List<ProductWithStock>, shelves: List<Shelf>, initialFromShelfId: String, dismiss: () -> Unit, move: (String, String) -> Unit) {
    val sources = shelves.filter { shelf -> products.any { item -> item.stocks.any { it.shelfId == shelf.id && it.quantity > 0 } } }
    var from by remember { mutableStateOf(initialFromShelfId.takeIf { id -> sources.any { it.id == id } } ?: sources.firstOrNull()?.id.orEmpty()) }
    var to by remember { mutableStateOf(shelves.firstOrNull { it.id != from }?.id.orEmpty()) }
    var confirmed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Skupno premještanje") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Premješta se cijela dostupna količina odabranih artikala; količina se ne mijenja.")
            PairPicker("Izvorna polica", sources.map { it.id to it.name }, from) { from = it; if (to == it) to = shelves.firstOrNull { shelf -> shelf.id != it }?.id.orEmpty(); confirmed = false }
            PairPicker("Odredišna polica", shelves.filterNot { it.id == from }.map { it.id to it.name }, to) { to = it; confirmed = false }
            if (confirmed) Text("Ponovno pritisnite Premjesti za potvrdu skupne radnje.", color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { Button({ if (confirmed) move(from, to) else confirmed = true }, enabled = from.isNotBlank() && to.isNotBlank() && from != to) { Text("Premjesti") } },
        dismissButton = { TextButton(dismiss) { Text("Odustani") } },
    )
}

@Composable
private fun BulkCategoryDialog(categories: List<String>, dismiss: () -> Unit, apply: (String) -> Unit) {
    var selected by remember { mutableStateOf(categories.firstOrNull().orEmpty()) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Promijeni kategoriju") },
        text = { PairPicker("Kategorija", categories.map { it to it }, selected) { selected = it } },
        confirmButton = { Button({ apply(selected) }, enabled = selected.isNotBlank()) { Text("Primijeni") } },
        dismissButton = { TextButton(dismiss) { Text("Odustani") } },
    )
}

@Composable
private fun ProductFilterDialog(
    initial: ProductFilter,
    shelves: List<Shelf>,
    categories: List<Category>,
    dismiss: () -> Unit,
    apply: (ProductFilter) -> Unit,
) {
    var shelf by remember { mutableStateOf(initial.shelfIds.firstOrNull().orEmpty()) }
    var category by remember { mutableStateOf(initial.categories.firstOrNull().orEmpty()) }
    var quantity by remember { mutableStateOf(initial.quantityAtMost?.toString().orEmpty()) }
    var below by remember { mutableStateOf(initial.belowMinimumOnly) }
    var shopping by remember { mutableStateOf(initial.onShoppingListOnly) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Filtri zalihe") },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SimpleDropdown("Polica", shelves.firstOrNull { it.id == shelf }?.name ?: "Sve", listOf("Sve") + shelves.map { it.name }) { selected -> shelf = shelves.firstOrNull { it.name == selected }?.id.orEmpty() } }
            item { SimpleDropdown("Kategorija", category.ifBlank { "Sve" }, listOf("Sve") + categories.map { it.name }) { category = if (it == "Sve") "" else it } }
            item { OutlinedTextField(quantity, { quantity = it.filter(Char::isDigit) }, label = { Text("Ukupna količina najviše") }, modifier = Modifier.fillMaxWidth()) }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Ispod minimuma", Modifier.weight(1f)); Switch(below, { below = it }) } }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Na popisu za kupnju", Modifier.weight(1f)); Switch(shopping, { shopping = it }) } }
        } },
        confirmButton = { Button({ apply(initial.copy(shelfIds = shelf.takeIf(String::isNotBlank)?.let(::setOf) ?: emptySet(), categories = category.takeIf(String::isNotBlank)?.let(::setOf) ?: emptySet(), quantityAtMost = quantity.toIntOrNull(), belowMinimumOnly = below, onShoppingListOnly = shopping)) }) { Text("Primijeni") } },
        dismissButton = { Row { TextButton({ apply(ProductFilter(query = initial.query)) }) { Text("Očisti") }; TextButton(dismiss) { Text("Odustani") } } },
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ProductCard(
    item: ProductWithStock,
    shelves: List<Shelf>,
    selectedShelfId: String?,
    selected: Boolean,
    selectionMode: Boolean,
    open: () -> Unit,
    select: () -> Unit,
    increment: () -> Unit,
    decrement: () -> Unit,
    move: () -> Unit,
    edit: () -> Unit,
    delete: () -> Unit,
) {
    val available = selectedShelfId?.let { id -> item.stocks.firstOrNull { it.shelfId == id }?.quantity } ?: item.totalQuantity
    var menu by remember { mutableStateOf(false) }
    val swipeScope = rememberCoroutineScope()
    val swipeState = rememberSwipeToDismissBoxState(confirmValueChange = { target ->
        when (target) {
            SwipeToDismissBoxValue.StartToEnd -> increment()
            SwipeToDismissBoxValue.EndToStart -> return@rememberSwipeToDismissBoxState true
            SwipeToDismissBoxValue.Settled -> Unit
        }
        false
    })
    SwipeToDismissBox(
        state = swipeState,
        enableDismissFromStartToEnd = !selectionMode,
        enableDismissFromEndToStart = !selectionMode && (available > 0 || item.totalQuantity > 0 && shelves.size > 1),
        backgroundContent = {
            val adding = swipeState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier.fillMaxSize().background(if (adding) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp),
                contentAlignment = if (adding) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (adding) Text("+1", fontWeight = FontWeight.Bold)
                else Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { decrement(); swipeScope.launch { swipeState.reset() } },
                        modifier = Modifier.semantics { contentDescription = "Izvadi jedan gestom" },
                        enabled = available > 0,
                    ) { Text("−1") }
                    TextButton(
                        onClick = { move(); swipeScope.launch { swipeState.reset() } },
                        modifier = Modifier.semantics { contentDescription = "Premjesti gestom" },
                        enabled = item.totalQuantity > 0 && shelves.size > 1,
                    ) { Text("Premjesti") }
                }
            }
        },
    ) {
      Card(
          shape = RoundedCornerShape(18.dp),
          modifier = Modifier.fillMaxWidth().combinedClickable(onClick = open, onLongClick = select),
      ) {
        BoxWithConstraints {
            val showWideActions = this.maxWidth > 430.dp
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) Checkbox(selected, { select() })
                else if (item.product.photoUri != null) ProductPhoto(item.product.photoUri, item.product.updatedAt, null, Modifier.size(54.dp))
                else Icon(Icons.Outlined.ShoppingCart, null, Modifier.size(42.dp), tint = Purple)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(item.product.name, fontWeight = FontWeight.Bold)
                    Text(item.product.description.ifBlank { item.product.category }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(productQuantityText(item, shelves, selectedShelfId), style = MaterialTheme.typography.bodySmall)
                    if (item.isBelowMinimum) Text("Nedostaje ${item.shortfall} kom", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
                if (!selectionMode) {
                    IconButton(increment) { Icon(Icons.Outlined.Add, "Dodaj jedan") }
                    IconButton(decrement, enabled = available > 0) { Icon(Icons.Outlined.Remove, "Izvadi jedan") }
                    if (showWideActions) {
                        IconButton(move, enabled = item.totalQuantity > 0 && shelves.size > 1) { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, "Premjesti") }
                        IconButton(edit) { Icon(Icons.Outlined.Edit, "Uredi") }
                    }
                    IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "Dodatne radnje") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Premjesti") }, { menu = false; move() }, enabled = item.totalQuantity > 0 && shelves.size > 1)
                        DropdownMenuItem({ Text("Uredi") }, { menu = false; edit() })
                        DropdownMenuItem({ Text("Obriši") }, { menu = false; delete() })
                    }
                }
            }
        }
      }
    }
}

internal fun productQuantityText(item: ProductWithStock, shelves: List<Shelf>, selectedShelfId: String?): String {
    if (!selectedShelfId.isNullOrBlank()) {
        val onShelf = item.stocks.firstOrNull { it.shelfId == selectedShelfId }?.quantity ?: 0
        return "$onShelf kom na polici · ${item.totalQuantity} ukupno"
    }
    val locations = item.stocks.filter { it.quantity > 0 }.joinToString { stock ->
        "${shelves.firstOrNull { it.id == stock.shelfId }?.name ?: "Polica"} ${stock.quantity}"
    }
    return if (locations.isBlank()) "${item.totalQuantity} kom" else "${item.totalQuantity} kom · $locations"
}

@Composable
fun ProductEditor(
    current: Product?,
    shelves: List<Shelf>,
    categories: List<Category>,
    onDismiss: () -> Unit,
    initialShelfId: String = "",
    activeProducts: List<ProductWithStock> = emptyList(),
    deletedProducts: List<ProductWithStock> = emptyList(),
    catalogLookup: CatalogLookupState = CatalogLookupState(),
    requestCatalogLookup: (String) -> Unit = {},
    continueManually: () -> Unit = {},
    showInventoryMatchInitially: Boolean = false,
    barcodeScanner: (@Composable ((String) -> Unit, () -> Unit) -> Unit)? = null,
    onAddExisting: (ProductWithStock, String, Int, (Boolean) -> Unit) -> Unit = { _, _, _, done -> done(false) },
    onRestoreDeleted: (ProductWithStock, String, Int, (Boolean) -> Unit) -> Unit = { _, _, _, done -> done(false) },
    onSave: (Product, String, Int, String?, PhotoSource?, (Boolean) -> Unit) -> Unit,
) {
    val isNew = current == null || current.id.isBlank()
    var name by rememberSaveable(current?.id) { mutableStateOf(current?.name.orEmpty()) }
    var barcode by rememberSaveable(current?.id) { mutableStateOf(current?.barcode.orEmpty()) }
    var description by rememberSaveable(current?.id) { mutableStateOf(current?.description.orEmpty()) }
    var category by rememberSaveable(current?.id) { mutableStateOf(current?.category.orEmpty()) }
    var minimum by rememberSaveable(current?.id) { mutableStateOf((current?.minimumQuantity ?: 0).toString()) }
    var autoShopping by rememberSaveable(current?.id) { mutableStateOf(current?.autoShopping ?: true) }
    var shelfId by rememberSaveable(initialShelfId) { mutableStateOf(initialShelfId) }
    var quantity by rememberSaveable(current?.id) { mutableStateOf("1") }
    var remotePhotoUri by rememberSaveable(current?.id) { mutableStateOf(current?.photoUri) }
    var remotePhotoSource by rememberSaveable(current?.id) { mutableStateOf(current?.photoSource?.name ?: PhotoSource.NONE.name) }
    var showBarcodeScanner by rememberSaveable { mutableStateOf(false) }
    var showInventoryMatch by rememberSaveable { mutableStateOf(showInventoryMatchInitially) }
    var lookupAttempted by rememberSaveable { mutableStateOf(false) }
    var submitting by rememberSaveable { mutableStateOf(false) }
    var operationError by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedPhotoPath by rememberProductPhotoDraftPath(current?.id)
    var selectedSourceName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedSource = selectedSourceName?.let(PhotoSource::valueOf)
    var photoError by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraPath by rememberSaveable(current?.id) { mutableStateOf<String?>(null) }

    fun replaceSelectedPhoto(path: String, source: PhotoSource) {
        deleteTemporaryProductPhoto(context.cacheDir, selectedPhotoPath)
        selectedPhotoPath = path
        selectedSourceName = source.name
        photoError = null
    }

    fun finishEditor() {
        deleteTemporaryProductPhoto(context.cacheDir, selectedPhotoPath)
        deleteTemporaryProductPhoto(context.cacheDir, pendingCameraPath)
        selectedPhotoPath = null
        pendingCameraPath = null
        onDismiss()
    }

    fun dismissEditor() {
        if (!submitting) finishEditor()
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            runCatching { resizeJpegToTempFile(context, uri) }
                .onSuccess { replaceSelectedPhoto(it.absolutePath, PhotoSource.GALLERY) }
                .onFailure { photoError = it.message }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val capturePath = pendingCameraPath
        if (success && capturePath != null) scope.launch {
            runCatching { resizeJpegToTempFile(context, Uri.fromFile(File(capturePath))) }
                .onSuccess { replaceSelectedPhoto(it.absolutePath, PhotoSource.CAMERA) }
                .onFailure { photoError = it.message }
            deleteTemporaryProductPhoto(context.cacheDir, capturePath)
            pendingCameraPath = null
        } else {
            deleteTemporaryProductPhoto(context.cacheDir, capturePath)
            pendingCameraPath = null
        }
    }
    fun launchCameraCapture() {
        deleteTemporaryProductPhoto(context.cacheDir, pendingCameraPath)
        val capture = createProductPhotoCaptureFile(context.cacheDir)
        pendingCameraPath = capture.absolutePath
        camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", capture))
    }
    var cameraPermissionGranted by rememberCameraPermissionState {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    var cameraPermissionDenied by rememberSaveable { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPermissionGranted = granted
        cameraPermissionDenied = !granted
        if (granted) launchCameraCapture() else photoError = "Kamera nije dopuštena. Omogućite je u postavkama aplikacije."
    }
    LaunchedEffect(cameraPermissionGranted) {
        if (cameraPermissionGranted) {
            cameraPermissionDenied = false
            if (photoError == "Kamera nije dopuštena. Omogućite je u postavkama aplikacije.") photoError = null
        }
    }
    LaunchedEffect(shelves, initialShelfId) {
        if (shelves.none { it.id == shelfId }) {
            shelfId = initialShelfId.takeIf { id -> shelves.any { it.id == id } } ?: shelves.firstOrNull()?.id.orEmpty()
        }
    }
    LaunchedEffect(catalogLookup.barcode, catalogLookup.outcome, catalogLookup.product) {
        val catalog = catalogLookup.product
        if (catalogLookup.barcode == barcode && catalogLookup.outcome == CatalogLookupOutcome.SUCCESS && catalog != null) {
            val merged = ProductEntryDraft(name, barcode, description, category, remotePhotoUri, PhotoSource.valueOf(remotePhotoSource))
                .mergeEmptyFields(catalog)
            name = merged.name
            description = merged.description
            category = merged.category
            remotePhotoUri = merged.photoUri
            remotePhotoSource = merged.photoSource.name
        }
    }
    val inventoryMatch = remember(barcode, activeProducts, deletedProducts, current?.id) {
        findBarcodeInventoryMatch(barcode, activeProducts, deletedProducts, current?.id)
    }
    val missingRequired = ProductEntryDraft(name = name, category = category).missingRequiredFields

    fun acceptScannedBarcode(code: String) {
        barcode = code
        lookupAttempted = true
        showBarcodeScanner = false
        val match = findBarcodeInventoryMatch(code, activeProducts, deletedProducts, current?.id)
        if (match != null) showInventoryMatch = true else requestCatalogLookup(code)
    }

    AlertDialog(
        onDismissRequest = ::dismissEditor,
        title = { Text(if (isNew) "Novi artikl" else "Uredi artikl") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(name, { name = it.take(100) }, label = { Text("Naziv *") }, modifier = Modifier.fillMaxWidth()) }
                item {
                    OutlinedTextField(
                        barcode,
                        {
                            barcode = it.filter(Char::isDigit)
                            lookupAttempted = false
                        },
                        label = { Text("Barkod (opcionalno)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = if (isNew) ({
                            IconButton({ showBarcodeScanner = true }, enabled = !submitting) {
                                Icon(Icons.Outlined.QrCodeScanner, "Skeniraj barkod")
                            }
                        }) else null,
                    )
                }
                if (catalogLookup.barcode == barcode && catalogLookup.isLoading) item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Dohvaćam podatke iz Open Food Factsa…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(continueManually) { Text("Nastavi ručno") }
                    }
                }
                if (catalogLookup.barcode == barcode && catalogLookup.outcome in setOf(CatalogLookupOutcome.TIMEOUT, CatalogLookupOutcome.ERROR, CatalogLookupOutcome.EMPTY)) item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            when (catalogLookup.outcome) {
                                CatalogLookupOutcome.TIMEOUT -> "Open Food Facts nije odgovorio na vrijeme. Barkod je sačuvan."
                                CatalogLookupOutcome.EMPTY -> "Proizvod nije pronađen u Open Food Factsu. Barkod je sačuvan."
                                else -> "Podatke nije moguće dohvatiti. Barkod je sačuvan."
                            },
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(continueManually) { Text("Nastavi ručno") }
                    }
                }
                item { OutlinedTextField(description, { description = it.take(500) }, label = { Text("Pakiranje / opis") }, modifier = Modifier.fillMaxWidth()) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        remotePhotoUri?.let { ProductPhoto(it, current?.updatedAt ?: 0, "Fotografija artikla", Modifier.fillMaxWidth().height(120.dp)) }
                        selectedPhotoPath?.let { path ->
                            ProductPhoto(
                                Uri.fromFile(File(path)).toString(),
                                0,
                                "Nova fotografija artikla",
                                Modifier.fillMaxWidth().height(120.dp),
                            )
                            Text(
                                "Odabrana je nova fotografija (${File(path).length() / 1024} KiB).",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        photoError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (cameraPermissionDenied) OutlinedButton({
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                        }) { Text("Otvori postavke aplikacije") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({
                                if (cameraPermissionGranted) launchCameraCapture()
                                else cameraPermission.launch(Manifest.permission.CAMERA)
                            }) { Text("Snimi") }
                            OutlinedButton({ gallery.launch("image/*") }) { Text("Odaberi") }
                        }
                    }
                }
                item { SimpleDropdown("Kategorija *", category, (categories.map { it.name } + "Ostalo").distinct(), { category = it }) }
                if (lookupAttempted && !catalogLookup.isLoading && missingRequired.isNotEmpty()) item {
                    Text("Još ispunite obvezna polja: ${missingRequired.joinToString()}.", color = MaterialTheme.colorScheme.error)
                }
                if (!showInventoryMatch) {
                    operationError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                }
                item { OutlinedTextField(minimum, { minimum = it.filter(Char::isDigit) }, label = { Text("Minimalna količina") }, modifier = Modifier.fillMaxWidth()) }
                item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Automatski dodaj na kupnju", Modifier.weight(1f)); Switch(autoShopping, { autoShopping = it }) } }
                if (isNew) {
                    item { SimpleDropdown("Početna polica", shelves.firstOrNull { it.id == shelfId }?.name.orEmpty(), shelves.map { it.name }, { selected -> shelfId = shelves.first { it.name == selected }.id }) }
                    item { OutlinedTextField(quantity, { quantity = it.filter(Char::isDigit) }, label = { Text("Početna količina") }, modifier = Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (inventoryMatch != null) {
                        showInventoryMatch = true
                        return@Button
                    }
                    operationError = null
                    submitting = true
                    val now = System.currentTimeMillis()
                    onSave(
                        (current ?: Product("", "", name, createdAt = now, updatedAt = now)).copy(
                            name = name,
                            barcode = barcode.ifBlank { null },
                            description = description,
                            category = category,
                            photoUri = remotePhotoUri,
                            photoSource = PhotoSource.valueOf(remotePhotoSource),
                            minimumQuantity = minimum.toIntOrNull() ?: 0,
                            autoShopping = autoShopping,
                            updatedAt = now,
                        ),
                        shelfId,
                        quantity.toIntOrNull() ?: 0,
                        selectedPhotoPath,
                        selectedSource,
                    ) { success ->
                        submitting = false
                        if (success) finishEditor() else operationError = "Spremanje nije uspjelo. Pokušajte ponovno."
                    }
                },
                enabled = !submitting && !catalogLookup.isLoading && name.trim().length in 1..100 && category.isNotBlank() &&
                    (barcode.isBlank() || hr.smocnica.core.domain.BarcodePolicy.isSupported(barcode)) && (!isNew || shelves.isNotEmpty()),
            ) { Text(if (submitting) "Spremanje…" else "Spremi") }
        },
        dismissButton = { TextButton(::dismissEditor, enabled = !submitting) { Text("Odustani") } },
    )

    if (showBarcodeScanner) {
        val scanner = barcodeScanner
        if (scanner == null) SharedBarcodeScannerDialog("Skeniraj barkod artikla", { showBarcodeScanner = false }, ::acceptScannedBarcode)
        else scanner(::acceptScannedBarcode) { showBarcodeScanner = false }
    }
    if (showInventoryMatch && inventoryMatch != null) {
        ExistingBarcodeDialog(
            match = inventoryMatch,
            shelves = shelves,
            initialShelfId = shelfId,
            dismiss = { if (!submitting) showInventoryMatch = false },
            submitting = submitting,
            errorMessage = operationError,
        ) { selectedShelfId, selectedQuantity ->
            operationError = null
            submitting = true
            val done: (Boolean) -> Unit = { success ->
                submitting = false
                if (success) finishEditor() else operationError = "Dodavanje količine nije uspjelo. Pokušajte ponovno."
            }
            when (inventoryMatch) {
                is BarcodeInventoryMatch.Active -> onAddExisting(inventoryMatch.item, selectedShelfId, selectedQuantity, done)
                is BarcodeInventoryMatch.Deleted -> onRestoreDeleted(inventoryMatch.item, selectedShelfId, selectedQuantity, done)
            }
        }
    }
}

@Composable
private fun ExistingBarcodeDialog(
    match: BarcodeInventoryMatch,
    shelves: List<Shelf>,
    initialShelfId: String,
    dismiss: () -> Unit,
    submitting: Boolean,
    errorMessage: String?,
    confirm: (String, Int) -> Unit,
) {
    val item = match.item
    var shelfId by rememberSaveable(item.product.id) {
        mutableStateOf(initialShelfId.takeIf { id -> shelves.any { it.id == id } } ?: shelves.firstOrNull()?.id.orEmpty())
    }
    var quantity by rememberSaveable(item.product.id) { mutableIntStateOf(1) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (match is BarcodeInventoryMatch.Deleted) "Artikl je u košu" else "Artikl već postoji") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        item.product.photoUri?.let { ProductPhoto(it, item.product.updatedAt, item.product.name, Modifier.size(72.dp)) }
                        Column {
                            Text(item.product.name, fontWeight = FontWeight.Bold)
                            if (item.product.description.isNotBlank()) Text(item.product.description)
                            Text("Ukupno: ${item.totalQuantity} kom")
                        }
                    }
                }
                item {
                    val locations = item.stocks.filter { it.quantity > 0 }.joinToString("\n") { stock ->
                        "${shelves.firstOrNull { it.id == stock.shelfId }?.name ?: "Polica"}: ${stock.quantity} kom"
                    }
                    Text(locations.ifBlank { "Artikl trenutačno nema količinu ni na jednoj polici." })
                }
                item {
                    SimpleDropdown("Polica", shelves.firstOrNull { it.id == shelfId }?.name.orEmpty(), shelves.map { it.name }) { selected ->
                        shelfId = shelves.first { it.name == selected }.id
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ if (quantity > 1) quantity-- }, enabled = !submitting) { Icon(Icons.Outlined.Remove, "Smanji količinu") }
                        Text("$quantity kom", Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.Bold)
                        IconButton({ if (quantity < 1_000_000) quantity++ }, enabled = !submitting) { Icon(Icons.Outlined.Add, "Povećaj količinu") }
                    }
                }
                errorMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = {
            Button({ confirm(shelfId, quantity) }, enabled = !submitting && shelfId.isNotBlank() && quantity > 0) {
                Text(
                    if (submitting) "Spremanje…"
                    else if (match is BarcodeInventoryMatch.Deleted) "Vrati artikl iz koša i dodaj količinu"
                    else "Dodaj količinu postojećem artiklu",
                )
            }
        },
        dismissButton = { TextButton(dismiss, enabled = !submitting) { Text("Nastavi ručno") } },
    )
}

@Composable
private fun StockActionDialog(item: ProductWithStock, shelves: List<Shelf>, dismiss: () -> Unit, apply: (String, Int) -> Unit) {
    var shelfId by remember { mutableStateOf(item.stocks.firstOrNull { it.quantity > 0 }?.shelfId ?: shelves.firstOrNull()?.id.orEmpty()) }
    var quantity by remember { mutableIntStateOf(1) }
    var adding by remember { mutableStateOf(true) }
    val available = item.stocks.firstOrNull { it.shelfId == shelfId }?.quantity ?: 0
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(item.product.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip({ adding = true }, { Text("Dodaj") })
                    AssistChip({ adding = false }, { Text("Izvadi") })
                }
                SimpleDropdown("Polica", shelves.firstOrNull { it.id == shelfId }?.name.orEmpty(), shelves.map { it.name }, { selected -> shelfId = shelves.first { it.name == selected }.id })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ if (quantity > 1) quantity-- }) { Icon(Icons.Outlined.Remove, null) }
                    Text("$quantity kom", Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.Bold)
                    IconButton({ quantity++ }) { Icon(Icons.Outlined.Add, null) }
                }
                if (!adding) Text("Dostupno na polici: $available kom", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button({ apply(shelfId, if (adding) quantity else -quantity) }, enabled = shelfId.isNotBlank() && (adding || quantity <= available)) { Text(if (adding) "Dodaj u smočnicu" else "Izvadi iz smočnice") } },
        dismissButton = { TextButton(dismiss) { Text("Odustani") } },
    )
}

@Composable
fun ShoppingScreen(
    viewModel: MainViewModel,
    padding: PaddingValues,
    scanAndStore: (ShoppingItem) -> Unit,
) {
    val items by viewModel.shopping.collectAsStateWithLifecycle()
    val sync by viewModel.syncSummary.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ShoppingItem?>(null) }
    Scaffold(
        modifier = Modifier.padding(padding),
        floatingActionButton = { FloatingActionButton({ showAdd = true }) { Icon(Icons.Outlined.Add, "Dodaj") } },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = inner.calculateTopPadding() + 12.dp, bottom = 100.dp)) {
            item { ScreenTitle("Popis za kupnju", "Stavke ostaju dok roba stvarno ne uđe u smočnicu") }
            item { OperationSyncState(sync) }
            items.groupBy { it.category }.forEach { (category, group) ->
                item { Text(category, Modifier.padding(top = 18.dp, bottom = 6.dp), color = Purple, fontWeight = FontWeight.Bold) }
                items(group, key = { it.id }) { item ->
                    ShoppingRow(
                        item = item,
                        checked = { viewModel.setChecked(item, it) },
                        scanAndStore = { scanAndStore(item) },
                        edit = { if (item.manual) editing = item },
                    )
                }
            }
            if (items.isEmpty()) item { EmptyState("Popis za kupnju je prazan.") }
        }
    }
    if (showAdd) ManualShoppingDialog(null, { showAdd = false }) { name, category, qty -> viewModel.addShopping(name, category, qty); showAdd = false }
    editing?.let { item -> ManualShoppingDialog(item, { editing = null }) { name, category, qty ->
        viewModel.updateManualShopping(item, name, category, qty)
        editing = null
    } }
}

@Composable
private fun ShoppingRow(item: ShoppingItem, checked: (Boolean) -> Unit, scanAndStore: () -> Unit, edit: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(item.checked, checked)
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, textDecoration = if (item.checked) TextDecoration.LineThrough else null, color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                Text("${item.requiredQuantity} kom · ${if (item.manual) "ručno" else "automatski manjak"}", style = MaterialTheme.typography.bodySmall)
            }
            if (item.manual) IconButton(edit) { Icon(Icons.Outlined.Edit, "Uredi ručnu stavku") }
        }
        OutlinedButton(scanAndStore, Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.QrCodeScanner, null)
            Text("Skeniraj i spremi", Modifier.padding(start = 8.dp))
        }
    }
    HorizontalDivider()
}

@Composable
private fun ManualShoppingDialog(current: ShoppingItem?, dismiss: () -> Unit, save: (String, String, Int) -> Unit) {
    var name by remember { mutableStateOf(current?.name.orEmpty()) }; var category by remember { mutableStateOf(current?.category ?: "Ostalo") }; var quantity by remember { mutableStateOf(current?.requiredQuantity?.toString() ?: "1") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (current == null) "Ručna stavka" else "Uredi ručnu stavku") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it.take(100) }, label = { Text("Naziv") })
        OutlinedTextField(category, { category = it.take(100) }, label = { Text("Kategorija") })
        OutlinedTextField(quantity, { quantity = it.filter(Char::isDigit) }, label = { Text("Količina") })
    } }, confirmButton = { Button({ save(name, category, quantity.toIntOrNull() ?: 1) }, enabled = name.trim().length in 1..100 && category.trim().length in 1..100 && quantity.toIntOrNull() in 1..1_000_000) { Text(if (current == null) "Dodaj" else "Spremi") } }, dismissButton = { TextButton(dismiss) { Text("Odustani") } })
}

@Composable
private fun SimpleDropdown(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) { Text("$label: ${selected.ifBlank { "Odaberite" }}") }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { option -> DropdownMenuItem({ Text(option) }, { onSelect(option); expanded = false }) }
        }
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState(text: String) { Text(text, Modifier.fillMaxWidth().padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
