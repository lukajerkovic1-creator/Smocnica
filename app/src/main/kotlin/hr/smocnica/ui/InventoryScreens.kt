package hr.smocnica.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import hr.smocnica.core.model.ProductVariant
import hr.smocnica.core.model.PackageUnit
import hr.smocnica.core.model.MinimumMode
import hr.smocnica.core.model.SynonymRule
import hr.smocnica.core.model.Shelf
import hr.smocnica.core.model.ShoppingItem
import hr.smocnica.core.model.PhotoSource
import hr.smocnica.ui.theme.Purple
import hr.smocnica.core.domain.GenericNamePolicy
import hr.smocnica.core.domain.PackageAmountPolicy
import hr.smocnica.core.domain.GenericStockPolicy
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
    var showEditor by remember { mutableStateOf<ProductWithStock?>(null) }
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
    var quickAction by remember { mutableStateOf<Pair<ProductWithStock, Int>?>(null) }
    var lastQuickShelfId by rememberSaveable(initialShelfId) { mutableStateOf(initialShelfId) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val selectedShelf = shelves.firstOrNull { it.id == initialShelfId }

    LaunchedEffect(initialShelfId, initialFilter) { viewModel.updateFilter(activeFilter) }
    DisposableEffect(Unit) { onDispose { viewModel.updateFilter(ProductFilter()) } }

    fun quickAdjust(item: ProductWithStock, delta: Int) { quickAction = item to delta }

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
                    { showEditor = item },
                    { deletingProduct = item.product },
                )
            }
            if (products.isEmpty()) item { ContextEmptyState("Nema artikala za odabrane filtre.", scan, { creating = true }, if (selectedShelf != null) ({ chooseMoveProduct = true }) else null) }
        }
    }
    if (creating) ProductEditor(
        current = null,
        recognizePhoto = viewModel::recognizePhoto,
        shelves = shelves,
        categories = categories,
        onDismiss = { creating = false },
        initialShelfId = initialShelfId,
        activeProducts = allProducts,
        deletedProducts = deletedProducts,
        synonymRules = viewModel.synonymRules.collectAsStateWithLifecycle().value,
        catalogLookup = catalogLookup,
        requestCatalogLookup = lookup::lookup,
        continueManually = lookup::continueManually,
        onAddExisting = { item, variant, shelf, quantity, done ->
            viewModel.adjustVariantStock(
                variant.id,
                shelf,
                quantity,
                onAdjusted = {
                    done(true)
                    scope.launch {
                        val result = snackbar.showSnackbar("${item.product.name}: dodano $quantity kom.", "Poništi")
                        if (result == SnackbarResult.ActionPerformed) viewModel.adjustVariantStock(variant.id, shelf, -quantity)
                    }
                },
                onFailure = { done(false) },
            )
        },
        onRestoreDeleted = { item, variant, shelf, quantity, done ->
            viewModel.restoreProductAndAddStock(
                item.product.id,
                variant.id,
                shelf,
                quantity,
                onRestored = {
                    done(true)
                    scope.launch {
                        val result = snackbar.showSnackbar("${item.product.name}: vraćen iz koša i dodan na policu.", "Poništi")
                        if (result == SnackbarResult.ActionPerformed) viewModel.undoRestoreProductAndAddStock(item.product, variant.id, shelf, quantity)
                    }
                },
                onFailure = { done(false) },
            )
        },
    ) { submission, shelf, quantity, photo, source, done ->
        viewModel.createProductAndStock(
            submission,
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
        ProductEditor(
            current.product,
            currentVariant = current.representativeVariant,
            currentItem = current,
            shelves = shelves,
            categories = categories,
            onDismiss = { showEditor = null },
        ) { submission, _, _, photo, source, done ->
            viewModel.saveProduct(submission, photo, source, onSaved = { done(true) }, onFailure = { done(false) })
        }
    }
    quickAction?.let { (item, delta) ->
        VariantQuickActionDialog(
            item,
            shelves,
            lastQuickShelfId.takeIf(String::isNotBlank) ?: initialShelfId,
            delta,
            dismiss = { quickAction = null },
        ) { variantId, shelfId ->
            quickAction = null
            lastQuickShelfId = shelfId
            viewModel.adjustVariantStock(variantId, shelfId, delta) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch {
                    val result = snackbar.showSnackbar(if (delta > 0) "Dodano 1 pakiranje." else "Izvađeno 1 pakiranje.", "Poništi")
                    if (result == SnackbarResult.ActionPerformed) viewModel.adjustVariantStock(variantId, shelfId, -delta)
                }
            }
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
        bulkMove = false
        viewModel.moveProducts(allProducts.filter { it.product.id in selectedIds }, from, to) {
            selectedIds = emptySet(); selecting = false
        }
    }
    if (bulkCategory) BulkCategoryDialog(categories, { bulkCategory = false }) { categoryId ->
        categories.firstOrNull { it.id == categoryId }?.let { category ->
            bulkCategory = false
            viewModel.changeProductsCategory(allProducts.filter { it.product.id in selectedIds }, category) {
                selectedIds = emptySet(); selecting = false
            }
        }
    }
    if (bulkDelete) ConfirmDialog("Obrisati ${selectedIds.size} artikala?", "Artikli će biti premješteni u koš na 30 dana.", { bulkDelete = false }) {
        bulkDelete = false
        viewModel.deleteProducts(allProducts.filter { it.product.id in selectedIds }) {
            selectedIds = emptySet(); selecting = false
        }
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

internal fun packageUnitLabel(unit: PackageUnit): String = when (unit) {
    PackageUnit.MG -> "mg"
    PackageUnit.G -> "g"
    PackageUnit.KG -> "kg"
    PackageUnit.ML -> "ml"
    PackageUnit.L -> "l"
    PackageUnit.PIECE -> "komad"
    PackageUnit.ROLL -> "rola"
    PackageUnit.BAG -> "vrećica"
    PackageUnit.CAPSULE -> "kapsula"
    PackageUnit.UNKNOWN -> "Nepoznato"
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
private fun BulkCategoryDialog(categories: List<Category>, dismiss: () -> Unit, apply: (String) -> Unit) {
    var selectedId by remember { mutableStateOf(categories.firstOrNull()?.id.orEmpty()) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Promijeni kategoriju") },
        text = { PairPicker("Kategorija", categories.map { it.id to it.name }, selectedId) { selectedId = it } },
        confirmButton = { Button({ apply(selectedId) }, enabled = categories.any { it.id == selectedId }) { Text("Primijeni") } },
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
    var categoryId by remember { mutableStateOf(initial.categoryIds.firstOrNull().orEmpty()) }
    var quantity by remember { mutableStateOf(initial.quantityAtMost?.toString().orEmpty()) }
    var below by remember { mutableStateOf(initial.belowMinimumOnly) }
    var shopping by remember { mutableStateOf(initial.onShoppingListOnly) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Filtri zalihe") },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { PairPicker("Polica", listOf("" to "Sve") + shelves.map { it.id to it.name }, shelf) { shelf = it } }
            item { PairPicker("Kategorija", listOf("" to "Sve") + categories.map { it.id to it.name }, categoryId) { categoryId = it } }
            item { OutlinedTextField(quantity, { quantity = it.filter(Char::isDigit) }, label = { Text("Ukupna količina najviše") }, modifier = Modifier.fillMaxWidth()) }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Ispod minimuma", Modifier.weight(1f)); Switch(below, { below = it }) } }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Na popisu za kupnju", Modifier.weight(1f)); Switch(shopping, { shopping = it }) } }
        } },
        confirmButton = { Button({
            apply(initial.copy(shelfIds = shelf.takeIf(String::isNotBlank)?.let(::setOf) ?: emptySet(), categoryIds = categoryId.takeIf(String::isNotBlank)?.let(::setOf) ?: emptySet(), quantityAtMost = quantity.toIntOrNull(), belowMinimumOnly = below, onShoppingListOnly = shopping))
        }) { Text("Primijeni") } },
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
    val available = selectedShelfId?.let { id -> item.stocks.filter { it.shelfId == id }.sumOf { it.quantity } } ?: item.totalQuantity
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
                Modifier.fillMaxSize().background(
                    if (swipeState.dismissDirection == SwipeToDismissBoxValue.Settled) Color.Transparent
                    else if (adding) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    RoundedCornerShape(20.dp),
                ).padding(horizontal = 14.dp),
                contentAlignment = if (adding) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (adding) Text("+1", fontWeight = FontWeight.Bold)
                else Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
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
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
          border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
          elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
          modifier = Modifier.fillMaxWidth().combinedClickable(onClick = open, onLongClick = select),
      ) {
        BoxWithConstraints {
            val compact = maxWidth < 600.dp || LocalDensity.current.fontScale >= 1.5f
            if (compact) {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ProductCardLeading(item, selectionMode, selected, select)
                        ProductCardSummary(item, shelves, selectedShelfId, Modifier.weight(1f).padding(start = 10.dp))
                    }
                    if (!selectionMode) {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            ProductQuantityButtons(available, increment, decrement)
                            IconButton({ menu = true }, Modifier.size(48.dp).semantics { contentDescription = "Dodatne radnje" }) { Icon(Icons.Outlined.MoreVert, null) }
                            ProductCardMenu(menu, { menu = false }, item, shelves, move, edit, delete)
                        }
                    }
                }
            } else {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProductCardLeading(item, selectionMode, selected, select)
                    ProductCardSummary(item, shelves, selectedShelfId, Modifier.weight(1f).padding(horizontal = 10.dp))
                    if (!selectionMode) {
                        ProductQuantityButtons(available, increment, decrement)
                        IconButton(move, Modifier.size(48.dp).semantics { contentDescription = "Premjesti" }, enabled = item.totalQuantity > 0 && shelves.size > 1) { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) }
                        IconButton(edit, Modifier.size(48.dp).semantics { contentDescription = "Uredi" }) { Icon(Icons.Outlined.Edit, null) }
                        IconButton({ menu = true }, Modifier.size(48.dp).semantics { contentDescription = "Dodatne radnje" }) { Icon(Icons.Outlined.MoreVert, null) }
                        ProductCardMenu(menu, { menu = false }, item, shelves, move, edit, delete)
                    }
                }
            }
        }
      }
    }
}

@Composable
private fun ProductCardLeading(item: ProductWithStock, selectionMode: Boolean, selected: Boolean, select: () -> Unit) {
    val photo = productCardPhoto(item)
    if (selectionMode) Checkbox(selected, { select() })
    else if (photo != null) {
        ProductPhoto(photo.photoUri, photo.updatedAt, "Fotografija: ${photo.displayName}", Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer))
    }
    else Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.ShoppingCart, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ProductCardSummary(item: ProductWithStock, shelves: List<Shelf>, selectedShelfId: String?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(item.product.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        val variantSummary = item.representativeVariant?.let { variant ->
            listOf(
                variant.manufacturer.takeIf(String::isNotBlank),
                variant.displayName.takeUnless {
                    GenericNamePolicy.normalize(it) == GenericNamePolicy.normalize(item.product.name)
                },
                variant.packageLabel.takeIf(String::isNotBlank),
                variant.description.takeIf(String::isNotBlank),
            ).filterNotNull().distinct().joinToString(" · ")
        }.orEmpty().ifBlank { item.product.description }
        if (variantSummary.isNotBlank()) {
            Text(variantSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier) {
            Text(productQuantityText(item, shelves, selectedShelfId), Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
        }
        if (item.isBelowMinimum) Text("Ispod minimalne zalihe", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ProductCardMenu(
    expanded: Boolean,
    dismiss: () -> Unit,
    item: ProductWithStock,
    shelves: List<Shelf>,
    move: () -> Unit,
    edit: () -> Unit,
    delete: () -> Unit,
) {
    DropdownMenu(expanded, dismiss) {
        DropdownMenuItem({ Text("Premjesti") }, { dismiss(); move() }, enabled = item.totalQuantity > 0 && shelves.size > 1)
        DropdownMenuItem({ Text("Uredi") }, { dismiss(); edit() })
        DropdownMenuItem({ Text("Obriši") }, { dismiss(); delete() })
    }
}

internal fun productQuantityText(item: ProductWithStock, shelves: List<Shelf>, selectedShelfId: String?): String {
    if (!selectedShelfId.isNullOrBlank()) {
        val onShelf = GenericStockPolicy.summarize(item.copy(stocks = item.stocks.filter { it.shelfId == selectedShelfId })).display()
        return "$onShelf na polici · ${GenericStockPolicy.summarize(item).display()} ukupno"
    }
    return GenericStockPolicy.summarize(item).display()
}

@Composable
private fun VariantQuickActionDialog(
    item: ProductWithStock,
    shelves: List<Shelf>,
    initialShelfId: String,
    delta: Int,
    dismiss: () -> Unit,
    apply: (String, String) -> Unit,
) {
    val eligibleVariants = item.variants.filter { variant ->
        variant.deletedAt == null && (delta > 0 || item.stocks.any { it.variantId == variant.id && it.quantity > 0 })
    }
    var variantId by rememberSaveable(item.product.id, delta) {
        mutableStateOf(
            item.product.preferredVariantId?.takeIf { id -> eligibleVariants.any { it.id == id } }
                ?: eligibleVariants.firstOrNull()?.id.orEmpty(),
        )
    }
    val shelfOptions = shelves.filter { shelf ->
        delta > 0 || item.stocks.any { it.variantId == variantId && it.shelfId == shelf.id && it.quantity > 0 }
    }
    var shelfId by rememberSaveable(item.product.id, variantId, delta) {
        mutableStateOf(
            initialShelfId.takeIf { id -> shelfOptions.any { it.id == id } }
                ?: item.stocks.firstOrNull { it.variantId == variantId && it.quantity > 0 }?.shelfId
                ?: shelfOptions.firstOrNull()?.id.orEmpty(),
        )
    }
    LaunchedEffect(variantId) {
        if (shelfOptions.none { it.id == shelfId }) shelfId = shelfOptions.firstOrNull()?.id.orEmpty()
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (delta > 0) "Dodaj jedno pakiranje" else "Izvadi jedno pakiranje") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(item.product.name, fontWeight = FontWeight.Bold)
                PairPicker("Varijanta", eligibleVariants.map { it.id to variantDisplayText(it) }, variantId) { variantId = it }
                PairPicker("Polica", shelfOptions.map { it.id to it.name }, shelfId) { shelfId = it }
            }
        },
        confirmButton = { Button({ apply(variantId, shelfId) }, enabled = variantId.isNotBlank() && shelfId.isNotBlank()) { Text("Potvrdi") } },
        dismissButton = { TextButton(dismiss) { Text("Odustani") } },
    )
}

internal fun variantDisplayText(variant: ProductVariant): String = listOfNotNull(
    variant.manufacturer.takeIf(String::isNotBlank),
    variant.packageLabel.takeIf(String::isNotBlank),
    variant.displayName.takeIf { it.isNotBlank() && it != variant.manufacturer },
).joinToString(" · ").ifBlank { "Varijanta" }

@Composable
fun ProductEditor(
    current: Product?,
    recognizePhoto: (suspend (String) -> hr.smocnica.core.domain.PhotoProductSuggestion)? = null,
    currentVariant: ProductVariant? = null,
    currentItem: ProductWithStock? = null,
    shelves: List<Shelf>,
    categories: List<Category>,
    onDismiss: () -> Unit,
    initialShelfId: String = "",
    activeProducts: List<ProductWithStock> = emptyList(),
    deletedProducts: List<ProductWithStock> = emptyList(),
    synonymRules: List<SynonymRule> = emptyList(),
    catalogLookup: CatalogLookupState = CatalogLookupState(),
    requestCatalogLookup: (String) -> Unit = {},
    continueManually: () -> Unit = {},
    showInventoryMatchInitially: Boolean = false,
    barcodeScanner: (@Composable ((String) -> Unit, () -> Unit) -> Unit)? = null,
    onAddExisting: (ProductWithStock, ProductVariant, String, Int, (Boolean) -> Unit) -> Unit = { _, _, _, _, done -> done(false) },
    onRestoreDeleted: (ProductWithStock, ProductVariant, String, Int, (Boolean) -> Unit) -> Unit = { _, _, _, _, done -> done(false) },
    notificationPermissionRequiredOverride: Boolean? = null,
    requestNotificationPermissionOverride: (() -> Unit)? = null,
    onSave: (ProductEditorSubmission, String, Int, String?, PhotoSource?, (Boolean) -> Unit) -> Unit,
) {
    val isNew = current == null || current.id.isBlank()
    var detailsExpanded by rememberSaveable(current?.id) { mutableStateOf(!isNew) }
    var recognizing by remember { mutableStateOf(false) }
    var recognitionAttempt by remember { mutableStateOf(0) }
    var recognitionMessage by remember { mutableStateOf<String?>(null) }
    var name by rememberSaveable(current?.id) { mutableStateOf(current?.name.orEmpty()) }
    var variantName by rememberSaveable(currentVariant?.id, current?.id) { mutableStateOf(currentVariant?.displayName ?: current?.name.orEmpty()) }
    var manufacturer by rememberSaveable(currentVariant?.id) { mutableStateOf(currentVariant?.manufacturer.orEmpty()) }
    var barcode by rememberSaveable(currentVariant?.id, current?.id) { mutableStateOf(currentVariant?.barcode ?: current?.barcode.orEmpty()) }
    var description by rememberSaveable(currentVariant?.id, current?.id) { mutableStateOf(currentVariant?.description ?: current?.description.orEmpty()) }
    var packageAmount by rememberSaveable(currentVariant?.id) {
        mutableStateOf(currentVariant?.packageAmountBase?.let { amount ->
            java.math.BigDecimal.valueOf(amount).divide(java.math.BigDecimal.valueOf(currentVariant.packageUnit.multiplierToBase)).stripTrailingZeros().toPlainString()
        }.orEmpty())
    }
    var packageUnit by rememberSaveable(currentVariant?.id) { mutableStateOf(currentVariant?.packageUnit?.name ?: PackageUnit.UNKNOWN.name) }
    var variantMinimum by rememberSaveable(currentVariant?.id) { mutableStateOf(currentVariant?.minimumPackages?.toString().orEmpty()) }
    var category by rememberSaveable(current?.id) { mutableStateOf(current?.category.orEmpty()) }
    var categoryId by rememberSaveable(current?.id) { mutableStateOf(current?.categoryId.orEmpty()) }
    var categoryManuallySelected by rememberSaveable(current?.id) { mutableStateOf(current != null) }
    var minimum by rememberSaveable(current?.id) { mutableStateOf(
        when (current?.minimumMode) {
            MinimumMode.MASS_MG -> java.math.BigDecimal.valueOf(current.minimumAmountBase).divide(java.math.BigDecimal.valueOf(1_000)).stripTrailingZeros().toPlainString()
            MinimumMode.VOLUME_ML, MinimumMode.COUNT -> current.minimumAmountBase.toString()
            else -> (current?.minimumQuantity ?: 0).toString()
        },
    ) }
    var minimumMode by rememberSaveable(current?.id) { mutableStateOf(current?.minimumMode?.name ?: MinimumMode.PACKAGES.name) }
    var minimumUnit by rememberSaveable(current?.id) { mutableStateOf(
        when (current?.minimumMode) {
            MinimumMode.MASS_MG -> PackageUnit.G.name
            MinimumMode.VOLUME_ML -> PackageUnit.ML.name
            else -> PackageUnit.PIECE.name
        },
    ) }
    var autoShopping by rememberSaveable(current?.id) { mutableStateOf(current?.autoShopping ?: true) }
    var shelfId by rememberSaveable(initialShelfId) { mutableStateOf(initialShelfId) }
    var quantity by rememberSaveable(current?.id) { mutableStateOf("1") }
    var remotePhotoUri by rememberSaveable(currentVariant?.id, current?.id) { mutableStateOf(currentVariant?.photoUri ?: current?.photoUri) }
    var remotePhotoSource by rememberSaveable(currentVariant?.id, current?.id) { mutableStateOf((currentVariant?.photoSource ?: current?.photoSource)?.name ?: PhotoSource.NONE.name) }
    var targetProductId by rememberSaveable(current?.id) { mutableStateOf("") }
    var groupingConfirmed by rememberSaveable(current?.id) { mutableStateOf(false) }
    var showBarcodeScanner by rememberSaveable { mutableStateOf(false) }
    var showInventoryMatch by rememberSaveable { mutableStateOf(showInventoryMatchInitially) }
    var lookupAttempted by rememberSaveable { mutableStateOf(false) }
    var submitting by rememberSaveable { mutableStateOf(false) }
    var operationError by rememberSaveable { mutableStateOf<String?>(null) }
    var notificationExplanationShown by rememberSaveable(current?.id) { mutableStateOf(false) }
    var showNotificationExplanation by rememberSaveable(current?.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shelfPreferences = remember(context) { context.getSharedPreferences("product-entry", android.content.Context.MODE_PRIVATE) }
    val pantryKey = shelves.firstOrNull()?.pantryId.orEmpty()
    var notificationPermissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationPermissionGranted = granted
    }
    val notificationPermissionRequired = notificationPermissionRequiredOverride
        ?: (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionGranted)
    val requestNotificationPermission = requestNotificationPermissionOverride
        ?: { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    var selectedPhotoPath by rememberProductPhotoDraftPath(current?.id)
    var selectedSourceName by rememberSaveable { mutableStateOf<String?>(null) }
    var saveSelectedPhoto by rememberSaveable(current?.id) { mutableStateOf(true) }
    val selectedSource = selectedSourceName?.let(PhotoSource::valueOf)
    var photoError by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraPath by rememberSaveable(current?.id) { mutableStateOf<String?>(null) }
    val parsedMinimum = parseProductQuantity(minimum)
    val parsedMinimumBase: Long? = when (MinimumMode.valueOf(minimumMode)) {
        MinimumMode.PACKAGES, MinimumMode.COUNT -> parseProductQuantity(minimum)?.toLong()
        MinimumMode.MASS_MG, MinimumMode.VOLUME_ML -> runCatching { PackageAmountPolicy.baseAmount(minimum, PackageUnit.valueOf(minimumUnit)) }.getOrNull()
    }
    val parsedInitialQuantity = parseProductQuantity(quantity)
    val parsedVariantMinimum = if (variantMinimum.isBlank()) null else parseProductQuantity(variantMinimum)
    val parsedPackageAmount = if (packageAmount.isBlank() || packageUnit == PackageUnit.UNKNOWN.name) null else runCatching {
        PackageAmountPolicy.baseAmount(packageAmount, PackageUnit.valueOf(packageUnit))
    }.getOrNull()
    val packageSizeChangePreview = remember(currentItem, currentVariant, parsedPackageAmount, packageUnit) {
        if (currentItem == null || currentVariant == null || parsedPackageAmount == null ||
            (currentVariant.packageAmountBase == parsedPackageAmount && currentVariant.packageUnit.name == packageUnit)
        ) null
        else {
            val updatedVariant = currentVariant.copy(
                packageAmountBase = parsedPackageAmount,
                packageUnit = PackageUnit.valueOf(packageUnit),
            )
            val updatedItem = currentItem.copy(
                variants = currentItem.variants.map { if (it.id == currentVariant.id) updatedVariant else it },
            )
            "Promjena veličine: ${GenericStockPolicy.summarize(currentItem).display()} → ${GenericStockPolicy.summarize(updatedItem).display()}."
        }
    }
    val groupingSuggestion = remember(variantName, activeProducts, synonymRules) {
        variantName.takeIf(String::isNotBlank)?.let { raw ->
            GenericNamePolicy.suggest(raw, activeProducts.map { it.product.id to it.product.name }, synonymRules)
        }
    }
    LaunchedEffect(name, variantName) { targetProductId = ""; groupingConfirmed = false }
    fun replaceSelectedPhoto(path: String, source: PhotoSource) {
        deleteTemporaryProductPhoto(context.cacheDir, selectedPhotoPath)
        selectedPhotoPath = path
        selectedSourceName = source.name
        saveSelectedPhoto = true
        photoError = null
        recognitionMessage = null
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
    LaunchedEffect(selectedPhotoPath, recognitionAttempt) {
        val path = selectedPhotoPath
        val recognize = recognizePhoto
        if (isNew && path != null && recognize != null) {
            recognizing = true
            recognitionMessage = null
            val startingName = name
            val startingManufacturer = manufacturer
            val startingAmount = packageAmount
            val startingUnit = packageUnit
            try {
                val suggestion = recognize(path)
                if (name == startingName) { name = suggestion.name; variantName = suggestion.name }
                if (manufacturer == startingManufacturer) manufacturer = suggestion.manufacturer
                if (packageAmount == startingAmount && packageUnit == startingUnit) {
                    packageAmount = suggestion.packageAmount
                    packageUnit = suggestion.packageUnit.name
                }
                recognitionMessage = "Provjerite prepoznate podatke prije spremanja."
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                recognitionMessage = when ((failure as? com.google.firebase.functions.FirebaseFunctionsException)?.code) {
                    com.google.firebase.functions.FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> "Dosegnuto je ograničenje prepoznavanja. Pokušajte kasnije ili unesite naziv ručno."
                    com.google.firebase.functions.FirebaseFunctionsException.Code.FAILED_PRECONDITION,
                    com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND -> "Prepoznavanje fotografija još nije dostupno. Možete unijeti naziv ručno."
                    else -> "Prepoznavanje nije uspjelo. Provjerite internet, ponovite fotografiju ili unesite naziv ručno."
                }
            } finally { recognizing = false }
        }
    }
    LaunchedEffect(shelves, initialShelfId) {
        if (shelves.none { it.id == shelfId }) {
            shelfId = preferredEntryShelf(initialShelfId, shelfPreferences.getString(pantryKey, "").orEmpty(), shelves.map { it.id })
        }
    }
    LaunchedEffect(categories, current?.id) {
        val canonical = categories.firstOrNull { it.id == categoryId }
            ?: categories.firstOrNull { it.name.equals(category, ignoreCase = true) }
            ?: categories.firstOrNull { it.isDefault }
            ?: categories.firstOrNull { it.name.equals("Ostalo", ignoreCase = true) }
            ?: categories.firstOrNull()
        if (canonical != null) {
            categoryId = canonical.id
            category = canonical.name
        }
    }
    LaunchedEffect(catalogLookup.barcode, catalogLookup.outcome, catalogLookup.product) {
        val catalog = catalogLookup.product
        if (catalogLookup.barcode == barcode && catalogLookup.outcome == CatalogLookupOutcome.SUCCESS && catalog != null) {
            val mappedCategory = categories.firstOrNull { it.name.equals(catalog.category, ignoreCase = true) }
                ?: categories.firstOrNull { it.isDefault }
                ?: categories.firstOrNull { it.name == "Ostalo" }
            val merged = ProductEntryDraft(
                name = name, barcode = barcode, description = description,
                category = if (categoryManuallySelected) category else "",
                categoryId = if (categoryManuallySelected) categoryId else "",
                photoUri = remotePhotoUri,
                photoSource = PhotoSource.valueOf(remotePhotoSource),
                manufacturer = manufacturer,
            ).mergeEmptyFields(catalog, mappedCategory)
            name = merged.name
            description = merged.description
            category = merged.category
            categoryId = merged.categoryId
            remotePhotoUri = merged.photoUri
            remotePhotoSource = merged.photoSource.name
            manufacturer = merged.manufacturer
            variantName = variantName.ifBlank { catalog.name }
            if (packageAmount.isBlank()) PackageAmountPolicy.parse(catalog.description)?.let { parsed ->
                packageAmount = java.math.BigDecimal.valueOf(parsed.amountBase)
                    .divide(java.math.BigDecimal.valueOf(parsed.unit.multiplierToBase)).stripTrailingZeros().toPlainString()
                packageUnit = parsed.unit.name
            }
        }
    }
    val inventoryMatch = remember(barcode, activeProducts, deletedProducts, current?.id) {
        findBarcodeInventoryMatch(barcode, activeProducts, deletedProducts, currentVariant?.id)
    }
    val missingRequired = ProductEntryDraft(name = name, category = category, categoryId = categoryId).missingRequiredFields

    fun acceptScannedBarcode(code: String) {
        barcode = code
        lookupAttempted = true
        showBarcodeScanner = false
        val match = findBarcodeInventoryMatch(code, activeProducts, deletedProducts, currentVariant?.id)
        if (match != null) showInventoryMatch = true else requestCatalogLookup(code)
    }

    AlertDialog(
        onDismissRequest = ::dismissEditor,
        title = { Text(if (isNew) "Novi artikl" else "Uredi artikl") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (selectedPhotoPath == null) remotePhotoUri?.let { ProductPhoto(it, currentVariant?.updatedAt ?: current?.updatedAt ?: 0, "Fotografija artikla", Modifier.fillMaxWidth().height(120.dp)) }
                        selectedPhotoPath?.let { path ->
                            ProductPhoto(
                                Uri.fromFile(File(path)).toString(),
                                0,
                                "Nova fotografija artikla",
                                Modifier.fillMaxWidth().height(120.dp),
                            )
                            ProductPhotoSaveOption(saveSelectedPhoto) { saveSelectedPhoto = it }
                            Text(if (saveSelectedPhoto) "Fotografija će se spremiti uz proizvod kada dodirnete Spremi."
                                else "Ova fotografija služi samo za prepoznavanje i neće se spremiti uz proizvod.")
                        }
                        if (isNew && recognizePhoto != null) Text("Fotografirajte prednju stranu ambalaže. Fotografija se šalje Google Geminiju za prijedlog podataka.")
                        if (recognizing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Prepoznajem proizvod…") }
                        recognitionMessage?.let { Text(it) }
                        if (selectedPhotoPath != null && recognizePhoto != null && !recognizing && isNew) {
                            TextButton({ recognitionAttempt += 1 }) { Text("Ponovi prepoznavanje") }
                        }
                        photoError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (cameraPermissionDenied) OutlinedButton({
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                        }) { Text("Otvori postavke aplikacije") }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({
                                if (cameraPermissionGranted) launchCameraCapture()
                                else cameraPermission.launch(Manifest.permission.CAMERA)
                            }, Modifier.weight(1f)) { Text(if (isNew && recognizePhoto != null) "Fotografiraj proizvod" else "Snimi") }
                            OutlinedButton({ gallery.launch("image/*") }, Modifier.weight(1f)) { Text("Odaberi fotografiju") }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        name,
                        { value ->
                            val previousName = name
                            name = value.take(100)
                            if (variantName.isBlank() || variantName == previousName) variantName = name
                        },
                        label = { Text("Naziv *") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (detailsExpanded) item { OutlinedTextField(variantName, { variantName = it.take(100) }, label = { Text("Naziv varijante *") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(manufacturer, { manufacturer = it.take(100) }, label = { Text("Proizvođač (opcionalno)") }, modifier = Modifier.fillMaxWidth()) }
                if (detailsExpanded || barcode.isBlank()) item {
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
                if (detailsExpanded) item { OutlinedTextField(description, { description = it.take(500) }, label = { Text("Pakiranje / opis") }, modifier = Modifier.fillMaxWidth()) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            packageAmount,
                            { packageAmount = it.filter { char -> char.isDigit() || char == ',' || char == '.' }.take(20) },
                            label = { Text("Količina u pakiranju") },
                            isError = packageAmount.isNotBlank() && parsedPackageAmount == null,
                            modifier = Modifier.weight(1f),
                        )
                        Box(Modifier.weight(1f)) {
                            PairPicker(
                                "Jedinica",
                                PackageUnit.entries.map { it.name to packageUnitLabel(it) },
                                packageUnit,
                            ) { packageUnit = it }
                        }
                    }
                    if ((packageAmount.isBlank()) != (packageUnit == PackageUnit.UNKNOWN.name)) {
                        Text("Za poznatu veličinu unesite količinu i odaberite jedinicu.", color = MaterialTheme.colorScheme.error)
                    }
                    packageSizeChangePreview?.let { preview ->
                        Text(preview, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (detailsExpanded) item {
                    OutlinedTextField(
                        variantMinimum,
                        { variantMinimum = it.filter(Char::isDigit) },
                        label = { Text("Minimum ove varijante (pakiranja, opcionalno)") },
                        isError = variantMinimum.isNotBlank() && parsedVariantMinimum == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (isNew) {
                    item { PairPicker("Početna polica", shelves.map { it.id to it.name }, shelfId) { shelfId = it } }
                    item {
                        OutlinedTextField(
                            quantity,
                            { quantity = it.filter(Char::isDigit) },
                            label = { Text("Početna količina") },
                            isError = parsedInitialQuantity == null,
                            supportingText = if (parsedInitialQuantity == null) ({ Text("Unesite broj od 0 do 1 000 000.") }) else null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (!showInventoryMatch) {
                    operationError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                }
                item { TextButton({ detailsExpanded = !detailsExpanded }) { Text(if (detailsExpanded) "Sakrij dodatne postavke" else "Dodatne postavke") } }
                if (isNew && groupingSuggestion != null && (detailsExpanded || groupingSuggestion.existingProductId != null)) item {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Prikaži zajedno", fontWeight = FontWeight.Bold)
                            Text(groupingSuggestion.suggestedGenericName)
                            val suggestedId = groupingSuggestion.existingProductId
                            if (suggestedId != null && activeProducts.any { it.product.id == suggestedId && !it.product.doNotGroup }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(groupingConfirmed && targetProductId == suggestedId, { checked ->
                                        groupingConfirmed = checked
                                        targetProductId = if (checked) suggestedId else ""
                                    }, Modifier.semantics { contentDescription = "Potvrdi grupiranje" })
                                    Text("Dodaj postojećoj zalihi: "+groupingSuggestion.suggestedGenericName)
                                }
                            }
                            if (detailsExpanded) {
                                OutlinedButton({ name = groupingSuggestion.suggestedGenericName }) { Text("Primijeni predloženi naziv") }
                                PairPicker("Grupiranje", listOf("" to "Nova samostalna grupa") + activeProducts.filterNot { it.product.doNotGroup }.map { it.product.id to it.product.name }, targetProductId) { selected ->
                                    targetProductId = selected; groupingConfirmed = false
                                }
                                if (targetProductId.isNotBlank() && targetProductId != suggestedId) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(groupingConfirmed, { groupingConfirmed = it }, Modifier.semantics { contentDescription = "Potvrdi grupiranje" })
                                    Text("Potvrđujem dodavanje odabranom artiklu")
                                }
                            }
                        }
                    }
                }
                if (detailsExpanded) {
                item {
                    PairPicker("Kategorija *", categories.map { it.id to it.name }, categoryId) { selectedId ->
                        categories.firstOrNull { it.id == selectedId }?.let {
                            categoryId = it.id
                            category = it.name
                            categoryManuallySelected = true
                        }
                    }
                }
                if (lookupAttempted && !catalogLookup.isLoading && missingRequired.isNotEmpty()) item {
                    Text("Još ispunite obvezna polja: ${missingRequired.joinToString()}.", color = MaterialTheme.colorScheme.error)
                }
                item {
                    PairPicker(
                        "Minimum se vodi u",
                        listOf(
                            MinimumMode.PACKAGES.name to "Broju pakiranja",
                            MinimumMode.MASS_MG.name to "Masi (kg/g)",
                            MinimumMode.VOLUME_ML.name to "Volumenu (l/ml)",
                            MinimumMode.COUNT.name to "Brojivim jedinicama",
                        ),
                        minimumMode,
                    ) { selected ->
                        minimumMode = selected
                        minimumUnit = when (MinimumMode.valueOf(selected)) {
                            MinimumMode.MASS_MG -> PackageUnit.G.name
                            MinimumMode.VOLUME_ML -> PackageUnit.ML.name
                            else -> PackageUnit.PIECE.name
                        }
                    }
                    if (minimumMode == MinimumMode.MASS_MG.name || minimumMode == MinimumMode.VOLUME_ML.name) {
                        PairPicker(
                            "Jedinica minimuma",
                            (if (minimumMode == MinimumMode.MASS_MG.name) listOf(PackageUnit.G, PackageUnit.KG) else listOf(PackageUnit.ML, PackageUnit.L))
                                .map { it.name to packageUnitLabel(it) },
                            minimumUnit,
                        ) { minimumUnit = it }
                    }
                }
                item {
                    OutlinedTextField(
                        minimum,
                        { value ->
                            val filtered = value.filter { it.isDigit() || it == ',' || it == '.' }
                            if (shouldExplainNotificationPermission(minimum, filtered, notificationPermissionRequired, notificationExplanationShown)) {
                                notificationExplanationShown = true
                                showNotificationExplanation = true
                            }
                            minimum = filtered
                        },
                        label = { Text("Minimalna količina") },
                        isError = parsedMinimumBase == null,
                        supportingText = if (parsedMinimumBase == null) ({ Text("Unesite broj od 0 do 1 000 000.") }) else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Automatski dodaj na kupnju", Modifier.weight(1f))
                        Switch(autoShopping, { enabled ->
                            if (enabled && !autoShopping && (parsedMinimumBase ?: 0) > 0 &&
                                notificationPermissionRequired && !notificationExplanationShown
                            ) {
                                notificationExplanationShown = true
                                showNotificationExplanation = true
                            }
                            autoShopping = enabled
                        })
                    }
                }
                if (notificationPermissionRequired && (parsedMinimumBase ?: 0) > 0) item {
                    TextButton({
                        notificationExplanationShown = true
                        showNotificationExplanation = true
                    }) { Text("Uključi obavijesti o minimalnoj zalihi") }
                }

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
                        ProductEditorSubmission(
                        product = (current ?: Product("", "", name, createdAt = now, updatedAt = now)).copy(
                            name = name,
                            barcode = null,
                            description = "",
                            category = category,
                            categoryId = categoryId,
                            photoUri = remotePhotoUri,
                            photoSource = PhotoSource.valueOf(remotePhotoSource),
                            minimumQuantity = if (minimumMode == MinimumMode.PACKAGES.name) requireNotNull(parsedMinimumBase).toInt() else 0,
                            minimumMode = MinimumMode.valueOf(minimumMode),
                            minimumAmountBase = requireNotNull(parsedMinimumBase),
                            autoShopping = autoShopping,
                            updatedAt = now,
                        ),
                        variant = (currentVariant ?: ProductVariant(
                            id = if (current == null) "" else current.id,
                            pantryId = current?.pantryId.orEmpty(),
                            productId = current?.id.orEmpty(),
                            displayName = variantName,
                            createdAt = now,
                            updatedAt = now,
                        )).copy(
                            displayName = variantName,
                            manufacturer = manufacturer,
                            barcode = barcode.ifBlank { null },
                            packageAmountBase = parsedPackageAmount,
                            packageUnit = if (parsedPackageAmount == null) PackageUnit.UNKNOWN else PackageUnit.valueOf(packageUnit),
                            packageLabel = if (parsedPackageAmount == null) "" else "$packageAmount ${packageUnitLabel(PackageUnit.valueOf(packageUnit))}",
                            description = description,
                            photoUri = remotePhotoUri,
                            photoSource = PhotoSource.valueOf(remotePhotoSource),
                            minimumPackages = parsedVariantMinimum,
                            updatedAt = now,
                        ),
                        targetProductId = targetProductId.ifBlank { null },
                        ),
                        shelfId,
                        requireNotNull(parsedInitialQuantity),
                        selectedPhotoPath.takeIf { saveSelectedPhoto },
                        selectedSource.takeIf { saveSelectedPhoto },
                    ) { success ->
                        submitting = false
                        if (success) { shelfPreferences.edit().putString(pantryKey, shelfId).apply(); finishEditor() } else operationError = "Spremanje nije uspjelo. Pokušajte ponovno."
                    }
                },
                enabled = !submitting && !recognizing && !catalogLookup.isLoading && name.trim().length in 1..100 && variantName.trim().length in 1..100 &&
                    categories.any { it.id == categoryId && it.name == category } &&
                    parsedMinimumBase != null && (!isNew || parsedInitialQuantity != null) &&
                    (variantMinimum.isBlank() || parsedVariantMinimum != null) &&
                    ((packageAmount.isBlank() && packageUnit == PackageUnit.UNKNOWN.name) || parsedPackageAmount != null) &&
                    (targetProductId.isBlank() || groupingConfirmed) &&
                    (barcode.isBlank() || hr.smocnica.core.domain.BarcodePolicy.isSupported(barcode)) && (!isNew || shelves.any { it.id == shelfId }),
            ) { Text(if (submitting) "Spremanje…" else "Spremi") }
        },
        dismissButton = { TextButton(::dismissEditor, enabled = !submitting) { Text("Odustani") } },
    )

    if (showBarcodeScanner) {
        val scanner = barcodeScanner
        if (scanner == null) SharedBarcodeScannerDialog("Skeniraj barkod artikla", { showBarcodeScanner = false }, ::acceptScannedBarcode)
        else scanner(::acceptScannedBarcode) { showBarcodeScanner = false }
    }
    if (showNotificationExplanation) {
        NotificationPermissionExplanationDialog(
            requestPermission = {
                showNotificationExplanation = false
                requestNotificationPermission()
            },
            dismiss = { showNotificationExplanation = false },
        )
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
                if (success) { shelfPreferences.edit().putString(pantryKey, selectedShelfId).apply(); finishEditor() } else operationError = "Dodavanje količine nije uspjelo. Pokušajte ponovno."
            }
            when (inventoryMatch) {
                is BarcodeInventoryMatch.Active -> onAddExisting(inventoryMatch.item, inventoryMatch.variant, selectedShelfId, selectedQuantity, done)
                is BarcodeInventoryMatch.Deleted -> onRestoreDeleted(inventoryMatch.item, inventoryMatch.variant, selectedShelfId, selectedQuantity, done)
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
    val variant = match.variant
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
                        variant.photoUri?.let { ProductPhoto(it, variant.updatedAt, variant.displayName, Modifier.size(72.dp)) }
                        Column {
                            Text(item.product.name, fontWeight = FontWeight.Bold)
                            if (GenericNamePolicy.normalize(variant.displayName) != GenericNamePolicy.normalize(item.product.name)) {
                                Text(variant.displayName)
                            }
                            if (variant.manufacturer.isNotBlank()) Text(variant.manufacturer)
                            if (variant.packageLabel.isNotBlank()) Text(variant.packageLabel)
                            Text("Ukupno: ${item.totalQuantity} kom")
                        }
                    }
                }
                item {
                    val locations = item.stocks.filter { it.variantId == variant.id && it.quantity > 0 }.joinToString("\n") { stock ->
                        "${shelves.firstOrNull { it.id == stock.shelfId }?.name ?: "Polica"}: ${stock.quantity} kom"
                    }
                    Text(locations.ifBlank { "Artikl trenutačno nema količinu ni na jednoj polici." })
                }
                item {
                    PairPicker("Polica", shelves.map { it.id to it.name }, shelfId) { shelfId = it }
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
                PairPicker("Polica", shelves.map { it.id to it.name }, shelfId) { shelfId = it }
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
    val products by viewModel.allProducts.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val sync by viewModel.syncSummary.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ShoppingItem?>(null) }
    var deleting by remember { mutableStateOf<ShoppingItem?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(
        modifier = Modifier.padding(padding),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        preferredVariantLabel = products.firstOrNull { it.product.id == item.productId }
                            ?.let { product ->
                                val preferredId = item.preferredVariantId ?: product.product.preferredVariantId
                                product.variants.firstOrNull { it.id == preferredId }
                                    ?.let { variant -> listOf(variant.manufacturer, variant.displayName, variant.packageLabel).filter(String::isNotBlank).distinct().joinToString(", ") }
                            },
                        checked = { viewModel.setChecked(item, it) },
                        scanAndStore = { scanAndStore(item) },
                        edit = { if (item.manual) editing = item },
                        delete = { if (item.manual) deleting = item },
                    )
                }
            }
            if (items.isEmpty()) item { EmptyState("Popis za kupnju je prazan.") }
        }
    }
    if (showAdd) ManualShoppingDialog(null, categories, { showAdd = false }) { name, categoryId, qty -> viewModel.addShopping(name, categoryId, qty); showAdd = false }
    editing?.let { item -> ManualShoppingDialog(item, categories, { editing = null }) { name, categoryId, qty ->
        viewModel.updateManualShopping(item, name, categoryId, qty)
        editing = null
    } }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Obriši stavku?") },
            text = { Text("Stavka „${item.name}” uklonit će se s popisa za kupnju.") },
            confirmButton = {
                Button({
                    deleting = null
                    viewModel.deleteManualShopping(item) { deleted ->
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "Stavka je obrisana.",
                                actionLabel = "Poništi",
                            )
                            if (result == SnackbarResult.ActionPerformed) viewModel.restoreManualShopping(deleted)
                        }
                    }
                }) { Text("Obriši") }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("Odustani") } },
        )
    }
}

@Composable
internal fun ShoppingRow(
    item: ShoppingItem,
    preferredVariantLabel: String? = null,
    checked: (Boolean) -> Unit,
    scanAndStore: () -> Unit,
    edit: () -> Unit,
    delete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)).padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(item.checked, checked)
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, textDecoration = if (item.checked) TextDecoration.LineThrough else null, color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                Text("${item.requiredQuantity} kom · ${if (item.manual) "ručno" else "automatski manjak"}", style = MaterialTheme.typography.bodySmall)
                if (!item.manual && !preferredVariantLabel.isNullOrBlank()) {
                    Text("Preporučeno: $preferredVariantLabel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (item.manual) {
                Box {
                    IconButton({ menuExpanded = true }) { Icon(Icons.Outlined.MoreVert, "Dodatne radnje") }
                    DropdownMenu(menuExpanded, { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Uredi") },
                            onClick = { menuExpanded = false; edit() },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Obriši") },
                            onClick = { menuExpanded = false; delete() },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                        )
                    }
                }
            }
        }
        OutlinedButton(scanAndStore, Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.QrCodeScanner, null)
            Text("Skeniraj i spremi", Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
internal fun ManualShoppingDialog(
    current: ShoppingItem?,
    categories: List<Category>,
    dismiss: () -> Unit,
    save: (String, String, Int) -> Unit,
) {
    val initialCategoryId = current?.categoryId?.takeIf { selected -> categories.any { it.id == selected } }
        ?: current?.category?.let { legacyName -> categories.firstOrNull { it.name == legacyName }?.id }
        ?: categories.firstOrNull { it.isDefault }?.id
        ?: categories.firstOrNull()?.id.orEmpty()
    var name by remember(current?.id) { mutableStateOf(current?.name.orEmpty()) }
    var categoryId by remember(current?.id, categories) { mutableStateOf(initialCategoryId) }
    var quantity by remember(current?.id) { mutableStateOf(current?.requiredQuantity?.toString() ?: "1") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (current == null) "Ručna stavka" else "Uredi ručnu stavku") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it.take(100) }, label = { Text("Naziv") })
        PairPicker("Kategorija", categories.map { it.id to it.name }, categoryId) { categoryId = it }
        OutlinedTextField(quantity, { quantity = it.filter(Char::isDigit) }, label = { Text("Količina") })
    } }, confirmButton = { Button({ save(name, categoryId, quantity.toIntOrNull() ?: 1) }, enabled = name.trim().length in 1..100 && categories.any { it.id == categoryId } && quantity.toIntOrNull() in 1..1_000_000) { Text(if (current == null) "Dodaj" else "Spremi") } }, dismissButton = { TextButton(dismiss) { Text("Odustani") } })
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
    Column(Modifier.padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState(text: String) { Text(text, Modifier.fillMaxWidth().padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
