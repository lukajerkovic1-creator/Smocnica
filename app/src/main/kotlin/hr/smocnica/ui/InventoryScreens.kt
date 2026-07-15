package hr.smocnica.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import hr.smocnica.MainViewModel
import hr.smocnica.core.model.Category
import hr.smocnica.core.model.Product
import hr.smocnica.core.model.ProductFilter
import hr.smocnica.core.model.ProductWithStock
import hr.smocnica.core.model.Shelf
import hr.smocnica.core.model.ShoppingItem
import hr.smocnica.core.model.PhotoSource
import hr.smocnica.ui.theme.Purple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

@Composable
fun StocksScreen(viewModel: MainViewModel, padding: PaddingValues, initialShelfId: String = "") {
    val products by viewModel.products.collectAsStateWithLifecycle()
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    var activeFilter by remember(initialShelfId) {
        mutableStateOf(ProductFilter(shelfIds = initialShelfId.takeIf(String::isNotBlank)?.let(::setOf) ?: emptySet()))
    }
    var showFilters by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf<Product?>(null) }
    var creating by remember { mutableStateOf(false) }
    var actionProduct by remember { mutableStateOf<ProductWithStock?>(null) }
    var deletingProduct by remember { mutableStateOf<Product?>(null) }
    val selectedShelf = shelves.firstOrNull { it.id == initialShelfId }

    LaunchedEffect(initialShelfId) { viewModel.updateFilter(activeFilter) }
    DisposableEffect(Unit) {
        onDispose { viewModel.updateFilter(ProductFilter()) }
    }

    Scaffold(
        modifier = Modifier.padding(padding),
        floatingActionButton = {
            ExtendedFloatingActionButton(text = { Text("Novi artikl") }, icon = { Icon(Icons.Outlined.Add, null) }, onClick = { creating = true })
        },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = inner.calculateTopPadding() + 12.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ScreenTitle(
                    selectedShelf?.name ?: "Sve zalihe",
                    if (selectedShelf != null) "Artikli i količine na ovoj polici" else "Pretražite i uredite artikle",
                )
            }
            item {
                OutlinedTextField(
                    activeFilter.query,
                    {
                        activeFilter = activeFilter.copy(query = it)
                        viewModel.updateFilter(activeFilter)
                    },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    label = { Text("Pretraži naziv") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item { AssistChip({ showFilters = true }, { Text("Filtri") }, leadingIcon = { Icon(Icons.Outlined.FilterList, null) }) }
            items(products, key = { it.product.id }) { item ->
                ProductCard(item, shelves, initialShelfId.takeIf(String::isNotBlank), { actionProduct = item }, { showEditor = item.product }, { deletingProduct = item.product })
            }
            if (products.isEmpty()) item { EmptyState("Nema artikala za odabrane filtre.") }
        }
    }
    if (creating) ProductEditor(null, shelves, categories, onDismiss = { creating = false }) { product, shelf, quantity, photo, source ->
        viewModel.createProductAndStock(product, shelf, quantity, photo, source)
        creating = false
    }
    showEditor?.let { current ->
        ProductEditor(current, shelves, categories, onDismiss = { showEditor = null }) { product, _, _, photo, source ->
            viewModel.saveProduct(product, photo, source)
            showEditor = null
        }
    }
    actionProduct?.let { item -> StockActionDialog(item, shelves, { actionProduct = null }) { shelfId, delta ->
        viewModel.adjustStock(item.product.id, shelfId, delta)
        actionProduct = null
    } }
    if (showFilters) ProductFilterDialog(activeFilter, shelves, categories, { showFilters = false }) { value ->
        activeFilter = value
        viewModel.updateFilter(value)
        showFilters = false
    }
    deletingProduct?.let { product -> ConfirmDialog(
        "Obrisati artikl ${product.name}?",
        "Artikl, njegove lokacije i fotografija ostaju dostupni za vraćanje iz koša 30 dana.",
        { deletingProduct = null },
    ) { viewModel.deleteProduct(product); deletingProduct = null } }
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

@Composable
private fun ProductCard(item: ProductWithStock, shelves: List<Shelf>, selectedShelfId: String?, adjust: () -> Unit, edit: () -> Unit, delete: () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable { adjust() }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.product.photoUri != null) ProductPhoto(item.product.photoUri, item.product.updatedAt, null, Modifier.size(58.dp))
            else Icon(Icons.Outlined.Add, null, Modifier.size(48.dp), tint = Purple)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(item.product.name, fontWeight = FontWeight.Bold)
                Text(item.product.description.ifBlank { item.product.category }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(productQuantityText(item, shelves, selectedShelfId), style = MaterialTheme.typography.bodySmall)
                if (item.isBelowMinimum) Text("Nedostaje ${item.shortfall} kom", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
            IconButton(edit) { Icon(Icons.Outlined.Edit, "Uredi") }
            IconButton(delete) { Icon(Icons.Outlined.DeleteOutline, "Obriši") }
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
    onSave: (Product, String, Int, ByteArray?, PhotoSource?) -> Unit,
) {
    val isNew = current == null || current.id.isBlank()
    var name by remember { mutableStateOf(current?.name.orEmpty()) }
    var barcode by remember { mutableStateOf(current?.barcode.orEmpty()) }
    var description by remember { mutableStateOf(current?.description.orEmpty()) }
    var category by remember { mutableStateOf(current?.category ?: categories.firstOrNull()?.name ?: "Ostalo") }
    var minimum by remember { mutableStateOf((current?.minimumQuantity ?: 0).toString()) }
    var autoShopping by remember { mutableStateOf(current?.autoShopping ?: true) }
    var shelfId by remember { mutableStateOf(shelves.firstOrNull()?.id.orEmpty()) }
    var quantity by remember { mutableStateOf("1") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedPhoto by remember { mutableStateOf<ByteArray?>(null) }
    var selectedSource by remember { mutableStateOf<PhotoSource?>(null) }
    var photoError by remember { mutableStateOf<String?>(null) }
    val cameraUri = remember {
        FileProvider.getUriForFile(context, "${context.packageName}.files", File.createTempFile("product-photo-", ".jpg", context.cacheDir))
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { runCatching { resizeJpeg(context, uri) }.onSuccess { selectedPhoto = it; selectedSource = PhotoSource.GALLERY; photoError = null }.onFailure { photoError = it.message } }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) scope.launch { runCatching { resizeJpeg(context, cameraUri) }.onSuccess { selectedPhoto = it; selectedSource = PhotoSource.CAMERA; photoError = null }.onFailure { photoError = it.message } }
    }
    var cameraPermissionDenied by remember { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPermissionDenied = !granted
        if (granted) camera.launch(cameraUri) else photoError = "Kamera nije dopuštena. Omogućite je u postavkama aplikacije."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Novi artikl" else "Uredi artikl") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(name, { name = it.take(100) }, label = { Text("Naziv *") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(barcode, { barcode = it.filter(Char::isDigit) }, label = { Text("Barkod (opcionalno)") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(description, { description = it.take(500) }, label = { Text("Pakiranje / opis") }, modifier = Modifier.fillMaxWidth()) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        current?.photoUri?.let { ProductPhoto(it, current.updatedAt, "Fotografija artikla", Modifier.fillMaxWidth().height(120.dp)) }
                        selectedPhoto?.let { Text("Odabrana je nova fotografija (${it.size / 1024} KiB).", color = MaterialTheme.colorScheme.primary) }
                        photoError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (cameraPermissionDenied) OutlinedButton({
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }) { Text("Otvori postavke aplikacije") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera.launch(cameraUri)
                                else cameraPermission.launch(Manifest.permission.CAMERA)
                            }) { Text("Snimi") }
                            OutlinedButton({ gallery.launch("image/*") }) { Text("Odaberi") }
                        }
                    }
                }
                item { SimpleDropdown("Kategorija", category, (categories.map { it.name } + "Ostalo").distinct(), { category = it }) }
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
                    val now = System.currentTimeMillis()
                    onSave(
                        (current ?: Product("", "", name, createdAt = now, updatedAt = now)).copy(
                            name = name,
                            barcode = barcode.ifBlank { null },
                            description = description,
                            category = category,
                            minimumQuantity = minimum.toIntOrNull() ?: 0,
                            autoShopping = autoShopping,
                            updatedAt = now,
                        ),
                        shelfId,
                        quantity.toIntOrNull() ?: 0,
                        selectedPhoto,
                        selectedSource,
                    )
                },
                enabled = name.trim().length in 1..100 && (barcode.isBlank() || hr.smocnica.core.domain.BarcodePolicy.isSupported(barcode)) && (!isNew || shelves.isNotEmpty()),
            ) { Text("Spremi") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Odustani") } },
    )
}

private suspend fun resizeJpeg(context: android.content.Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        val scale = minOf(1f, 2048f / maxOf(width, height).toFloat())
        decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
    }
    ByteArrayOutputStream().use { output ->
        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, output)) { "Fotografiju nije moguće obraditi." }
        output.toByteArray().also { require(it.size <= 5 * 1024 * 1024) { "Fotografija nakon obrade prelazi 5 MiB." } }
    }
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
fun ShoppingScreen(viewModel: MainViewModel, padding: PaddingValues) {
    val items by viewModel.shopping.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ShoppingItem?>(null) }
    Scaffold(
        modifier = Modifier.padding(padding),
        floatingActionButton = { FloatingActionButton({ showAdd = true }) { Icon(Icons.Outlined.Add, "Dodaj") } },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = inner.calculateTopPadding() + 12.dp, bottom = 100.dp)) {
            item { ScreenTitle("Popis za kupnju", "Stavke ostaju dok roba stvarno ne uđe u smočnicu") }
            items.groupBy { it.category }.forEach { (category, group) ->
                item { Text(category, Modifier.padding(top = 18.dp, bottom = 6.dp), color = Purple, fontWeight = FontWeight.Bold) }
                items(group, key = { it.id }) { item -> ShoppingRow(item, { viewModel.setChecked(item, it) }) { if (item.manual) editing = item } }
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
private fun ShoppingRow(item: ShoppingItem, checked: (Boolean) -> Unit, edit: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(item.checked, checked)
        Column(Modifier.weight(1f)) {
            Text(item.name, fontWeight = FontWeight.SemiBold, textDecoration = if (item.checked) TextDecoration.LineThrough else null, color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Text("${item.requiredQuantity} kom · ${if (item.manual) "ručno" else "automatski manjak"}", style = MaterialTheme.typography.bodySmall)
        }
        if (item.manual) IconButton(edit) { Icon(Icons.Outlined.Edit, "Uredi ručnu stavku") }
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
