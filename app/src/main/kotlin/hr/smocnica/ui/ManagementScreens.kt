package hr.smocnica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hr.smocnica.MainViewModel
import hr.smocnica.core.model.Category
import hr.smocnica.core.model.Shelf
import hr.smocnica.ui.theme.Purple
import hr.smocnica.ui.theme.ThemeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ShelvesScreen(
    viewModel: MainViewModel,
    padding: PaddingValues,
    openShelf: (String) -> Unit,
    scan: (String) -> Unit,
    addManual: (String) -> Unit,
    moveExisting: (String) -> Unit,
) {
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    val products by viewModel.allProducts.collectAsStateWithLifecycle()
    val sync by viewModel.syncSummary.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Shelf?>(null) }
    var moving by remember { mutableStateOf<Shelf?>(null) }
    var deleting by remember { mutableStateOf<Shelf?>(null) }
    var showNew by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.padding(padding),
        floatingActionButton = { ExtendedFloatingActionButton(text = { Text("Dodaj policu") }, icon = { Icon(Icons.Outlined.Add, null) }, onClick = { showNew = true }) },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = inner.calculateTopPadding() + 12.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ScreenTitle("Police", "Artikle možete rasporediti na više lokacija") }
            item { OperationSyncState(sync) }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ scan("") }) { Icon(Icons.Outlined.QrCodeScanner, null); Text("Skeniraj artikl", Modifier.padding(start = 6.dp)) }
                    OutlinedButton({ addManual("") }) { Icon(Icons.Outlined.Add, null); Text("Dodaj ručno", Modifier.padding(start = 6.dp)) }
                }
            }
            items(shelves, key = { it.id }) { shelf ->
                val index = shelves.indexOfFirst { it.id == shelf.id }
                val count = products.sumOf { product -> product.stocks.firstOrNull { it.shelfId == shelf.id }?.quantity ?: 0 }
                ShelfCard(
                    shelf = shelf,
                    count = count,
                    canMoveUp = index > 0,
                    canMoveDown = index in 0 until shelves.lastIndex,
                    canMoveStock = count > 0 && shelves.size > 1,
                    onOpen = { openShelf(shelf.id) },
                    onMoveUp = {
                        val reordered = shelves.toMutableList().apply { add(index - 1, removeAt(index)) }
                        viewModel.reorderShelves(reordered)
                    },
                    onMoveDown = {
                        val reordered = shelves.toMutableList().apply { add(index + 1, removeAt(index)) }
                        viewModel.reorderShelves(reordered)
                    },
                    onMoveStock = { moving = shelf },
                    onEdit = { editing = shelf },
                    onDelete = { deleting = shelf },
                    onScan = { scan(shelf.id) },
                    onAdd = { addManual(shelf.id) },
                    onMoveHere = { moveExisting(shelf.id) },
                )
            }
            if (shelves.isEmpty()) item { ContextEmptyState("Dodajte prvu policu za raspodjelu zalihe.", { scan("") }, { showNew = true }) }
        }
    }
    if (showNew) NameDialog("Nova polica", "Naziv police", { showNew = false }) { viewModel.createShelf(it); showNew = false }
    editing?.let { shelf -> NameDialog("Preimenuj policu", shelf.name, { editing = null }) { viewModel.renameShelf(shelf, it); editing = null } }
    moving?.let { shelf ->
        ShelfMoveDialog(shelf, shelves.filterNot { it.id == shelf.id }, { moving = null }) { target ->
            viewModel.moveAllStock(shelf.id, target, products)
            moving = null
        }
    }
    deleting?.let { shelf -> ConfirmDialog(
        "Obrisati policu ${shelf.name}?",
        "Prazna polica bit će premještena u koš na 30 dana.",
        { deleting = null },
    ) { viewModel.deleteShelf(shelf); deleting = null } }
}

@Composable
internal fun ShelfCard(
    shelf: Shelf,
    count: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canMoveStock: Boolean,
    onOpen: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveStock: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onScan: () -> Unit,
    onAdd: () -> Unit,
    onMoveHere: () -> Unit,
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Otvori ${shelf.name}" },
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(bottom = 6.dp),
            ) {
                Text(shelf.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "$count komada",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AssistChip(onScan, { Text("Skeniraj") }, leadingIcon = { Icon(Icons.Outlined.QrCodeScanner, null) })
                AssistChip(onAdd, { Text("Dodaj") }, leadingIcon = { Icon(Icons.Outlined.Add, null) })
                AssistChip(onMoveHere, { Text("Premjesti ovamo") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onMoveUp, enabled = canMoveUp) { Icon(Icons.Outlined.ArrowUpward, "Pomakni gore") }
                IconButton(onMoveDown, enabled = canMoveDown) { Icon(Icons.Outlined.ArrowDownward, "Pomakni dolje") }
                IconButton(onMoveStock, enabled = canMoveStock) { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, "Premjesti sve") }
                IconButton(onEdit) { Icon(Icons.Outlined.Edit, "Preimenuj") }
                IconButton(onDelete, enabled = count == 0) { Icon(Icons.Outlined.DeleteOutline, if (count == 0) "Obriši" else "Polica nije prazna") }
            }
        }
    }
}

@Composable
fun CategoriesScreen(viewModel: MainViewModel, padding: PaddingValues) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    var showNew by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Category?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 80.dp)) {
        item { ScreenTitle("Kategorije", "Upravljajte grupama hrane i kućnih potrepština") }
        item { Button({ showNew = true }) { Icon(Icons.Outlined.Add, null); Text("Dodaj kategoriju", Modifier.padding(start = 8.dp)) } }
        items(categories, key = { it.id }) { category ->
            val index = categories.indexOfFirst { it.id == category.id }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(category.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                if (category.isDefault) Text("zadana", color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton({
                    val reordered = categories.toMutableList().apply { add(index - 1, removeAt(index)) }
                    viewModel.reorderCategories(reordered)
                }, enabled = index > 0) { Icon(Icons.Outlined.ArrowUpward, "Pomakni gore") }
                IconButton({
                    val reordered = categories.toMutableList().apply { add(index + 1, removeAt(index)) }
                    viewModel.reorderCategories(reordered)
                }, enabled = index in 0 until categories.lastIndex) { Icon(Icons.Outlined.ArrowDownward, "Pomakni dolje") }
                IconButton({ editing = category }) { Icon(Icons.Outlined.Edit, "Uredi") }
                IconButton({ deleting = category }, enabled = !category.isDefault && categories.size > 1) { Icon(Icons.Outlined.DeleteOutline, "Obriši") }
            }
            HorizontalDivider()
        }
    }
    if (showNew) NameDialog("Nova kategorija", "Naziv", { showNew = false }) { name ->
        viewModel.saveCategory(Category("", "", name, categories.size))
        showNew = false
    }
    editing?.let { category -> NameDialog("Preimenuj kategoriju", category.name, { editing = null }) { name ->
        viewModel.saveCategory(category.copy(name = name)); editing = null
    } }
    deleting?.let { category ->
        CategoryDeleteDialog(category, categories.filterNot { it.id == category.id }, { deleting = null }) { replacementId ->
            viewModel.deleteCategory(category, replacementId)
            deleting = null
        }
    }
}

@Composable
private fun ShelfMoveDialog(source: Shelf, targets: List<Shelf>, dismiss: () -> Unit, move: (String) -> Unit) {
    var selected by remember { mutableStateOf(targets.firstOrNull()?.id.orEmpty()) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Premjesti sve s police ${source.name}") },
        text = { SimpleManagementPicker("Odredišna polica", selected, targets.map { it.id to it.name }) { selected = it } },
        confirmButton = { Button({ move(selected) }, enabled = selected.isNotBlank()) { Text("Premjesti sve") } },
        dismissButton = { TextButton(dismiss) { Text("Odustani") } },
    )
}

@Composable
private fun CategoryDeleteDialog(category: Category, replacements: List<Category>, dismiss: () -> Unit, delete: (String) -> Unit) {
    var selected by remember { mutableStateOf(replacements.firstOrNull { it.isDefault }?.id ?: replacements.firstOrNull()?.id.orEmpty()) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Obriši kategoriju ${category.name}?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Svi artikli iz ove kategorije premjestit će se u odabranu kategoriju.")
            SimpleManagementPicker("Zamjenska kategorija", selected, replacements.map { it.id to it.name }) { selected = it }
        } },
        confirmButton = { Button({ delete(selected) }, enabled = selected.isNotBlank()) { Text("Premjesti i obriši") } },
        dismissButton = { TextButton(dismiss) { Text("Odustani") } },
    )
}

@Composable
private fun SimpleManagementPicker(label: String, selected: String, options: List<Pair<String, String>>, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        androidx.compose.material3.OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) {
            Text("$label: ${options.firstOrNull { it.first == selected }?.second ?: "Odaberite"}")
        }
        androidx.compose.material3.DropdownMenu(expanded, { expanded = false }) {
            options.forEach { option -> androidx.compose.material3.DropdownMenuItem({ Text(option.second) }, { select(option.first); expanded = false }) }
        }
    }
}

@Composable
fun HistoryScreen(viewModel: MainViewModel, padding: PaddingValues) {
    val activities by viewModel.activities.collectAsStateWithLifecycle()
    val products by viewModel.allProducts.collectAsStateWithLifecycle()
    val deletedProducts by viewModel.deletedProducts.collectAsStateWithLifecycle()
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    val productNames = (products + deletedProducts).associate { it.product.id to it.product.name }
    val shelfNames = shelves.associate { it.id to it.name }
    val formatter = remember { SimpleDateFormat("dd. MM. yyyy. HH:mm", Locale.forLanguageTag("hr-HR")) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 40.dp)) {
        item { ScreenTitle("Povijest aktivnosti", "Promjene u posljednjih 12 mjeseci") }
        items(activities, key = { it.id }) { activity ->
            Column(Modifier.padding(vertical = 10.dp)) {
                Text(activityDisplayLabel(activity, productNames), fontWeight = FontWeight.Bold)
                Text(activityDescription(activity, shelfNames), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatter.format(Date(activity.createdAt)), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
        if (activities.isEmpty()) item { EmptyState("Povijest je prazna.") }
    }
}

@Composable
fun MenuScreen(
    viewModel: MainViewModel,
    padding: PaddingValues,
    navigate: (String) -> Unit,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
) {
    val selected by viewModel.selectedPantry.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val summary by viewModel.syncSummary.collectAsStateWithLifecycle()
    var editDeviceName by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 30.dp)) {
        item { ScreenTitle("Izbornik", selected?.name ?: "Postavke smočnice") }
        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text(session?.displayName ?: "Korisnik", fontWeight = FontWeight.Bold)
                    Text(viewModel.deviceIdentity.displayName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Izgled", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }
            }
        }
        item { MenuEntry("Povijest aktivnosti", Icons.Outlined.History) { navigate("history") } }
        item { MenuEntry("Naziv uređaja: ${viewModel.deviceIdentity.displayName}", Icons.Outlined.Edit) { editDeviceName = true } }
        item { MenuEntry("Kategorije", Icons.Outlined.Category) { navigate("categories") } }
        item { MenuEntry("Članovi i pozivni kod", Icons.Outlined.Group) { navigate("members") } }
        item { MenuEntry("Koš (30 dana)", Icons.Outlined.DeleteOutline) { navigate("trash") } }
        item { MenuEntry("JSON / CSV sigurnosna kopija", Icons.Outlined.FileDownload) { navigate("backup") } }
        item { MenuEntry("Provjeri ažuriranje", Icons.Outlined.SystemUpdate) { navigate("update") } }
        item { MenuEntry("Sinkroniziraj sada", Icons.Outlined.CloudSync, viewModel::synchronize) }
        if (summary.conflicts + summary.failed > 0) item { MenuEntry("Riješi probleme sinkronizacije (${summary.conflicts + summary.failed})", Icons.Outlined.WarningAmber) { navigate("conflicts") } }
        item { MenuEntry("O aplikaciji", Icons.Outlined.Info) { navigate("about") } }
        item { MenuEntry("Odjava", Icons.AutoMirrored.Outlined.Logout, viewModel::signOut) }
    }
    if (editDeviceName) NameDialog("Naziv ovog uređaja", viewModel.deviceIdentity.displayName, { editDeviceName = false }) {
        viewModel.renameDevice(it)
        editDeviceName = false
    }
}

@Composable
fun ConflictsScreen(viewModel: MainViewModel, padding: PaddingValues) {
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 50.dp)) {
        item { ScreenTitle("Problemi sinkronizacije", "Pregledajte promjene koje nisu mogle biti automatski usklađene") }
        items(conflicts, key = { it.operationId }) { conflict ->
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(conflict.description, fontWeight = FontWeight.Bold)
                    Text(if (conflict.isRevisionConflict) "Drugi uređaj promijenio je isti zapis prije sinkronizacije." else "Poslužitelj je trajno odbio promjenu (${conflict.errorCode ?: "nepoznata pogreška"}).", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ viewModel.resolveConflict(conflict.operationId, true) }) { Text(if (conflict.isRevisionConflict) "Zadrži moje" else "Pokušaj ponovno") }
                        TextButton({ viewModel.resolveConflict(conflict.operationId, false) }) { Text(if (conflict.isRevisionConflict) "Prihvati udaljeno" else "Odbaci lokalno") }
                    }
                }
            }
        }
        if (conflicts.isEmpty()) item { EmptyState("Nema problema za rješavanje.") }
    }
}

@Composable
private fun MenuEntry(label: String, icon: ImageVector, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Purple)
        Text(label, Modifier.weight(1f).padding(start = 14.dp), fontWeight = FontWeight.Medium)
        Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, null)
    }
    HorizontalDivider()
}

@Composable
private fun NameDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it.take(100) }, label = { Text("Naziv") }) },
        confirmButton = { Button({ save(value.trim()) }, enabled = value.trim().length in 1..100) { Text("Spremi") } },
        dismissButton = { TextButton(dismiss) { Text("Odustani") } },
    )
}
