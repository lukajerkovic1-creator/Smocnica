package hr.smocnica.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import hr.smocnica.MainViewModel
import hr.smocnica.core.domain.BarcodePolicy
import hr.smocnica.core.model.InventoryDifferenceType
import hr.smocnica.core.model.InventorySession
import kotlinx.coroutines.launch

@Composable
fun InventoryScreen(viewModel: MainViewModel, padding: PaddingValues) {
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    val products by viewModel.allProducts.collectAsStateWithLifecycle()
    val savedDraft by viewModel.inventoryDraft.collectAsStateWithLifecycle()
    val sync by viewModel.syncSummary.collectAsStateWithLifecycle()
    var shelfId by remember { mutableStateOf(shelves.firstOrNull()?.id.orEmpty()) }
    val counts = remember { mutableStateMapOf<String, Int>() }
    var preview by remember { mutableStateOf<InventorySession?>(null) }
    var started by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var lastScan by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 80.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ScreenTitle("Inventura", "Promjene se primjenjuju tek nakon završne potvrde") }
        item { OperationSyncState(sync) }
        savedDraft?.let { draft ->
            item {
                androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Postoji spremljeni nacrt inventure.", fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({
                                shelfId = draft.shelfId
                                counts.clear()
                                draft.counts.forEach { counts[it.productId] = it.actualQuantity }
                                started = true
                            }) { Text("Nastavi") }
                            TextButton({ viewModel.discardInventoryDraft(draft.id) }) { Text("Odbaci nacrt") }
                        }
                    }
                }
            }
        }
        item {
            SimpleShelfPicker(shelves.map { it.id to it.name }, shelfId) {
                shelfId = it
                counts.clear()
                started = false
            }
        }
        item {
            Button({
                if (!started) {
                    counts.clear()
                    products.forEach { counts[it.product.id] = 0 }
                    started = true
                    viewModel.persistInventoryDraft(shelfId, counts.toMap())
                }
                scanning = true
                lastScan = null
                scanError = null
            }, enabled = shelfId.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.QrCodeScanner, null)
                Text(if (started) "Nastavi skeniranje" else "Započni inventuru skeniranjem", Modifier.padding(start = 8.dp))
            }
        }
        if (started) item { Text("Skenirano: ${counts.values.sum()} kom. Za količinske ispravke koristite +/−.", color = MaterialTheme.colorScheme.primary) }
        items(products, key = { it.product.id }) { product ->
            val expected = product.stocks.firstOrNull { it.shelfId == shelfId }?.quantity ?: 0
            val actual = counts[product.product.id] ?: if (started) 0 else expected
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(product.product.name, fontWeight = FontWeight.SemiBold)
                    Text("Evidentirano: $expected kom", style = MaterialTheme.typography.bodySmall)
                }
                IconButton({
                    counts[product.product.id] = (actual - 1).coerceAtLeast(0)
                    viewModel.persistInventoryDraft(shelfId, counts.toMap())
                }) { Icon(Icons.Outlined.Remove, null) }
                Text("$actual", Modifier.padding(horizontal = 10.dp), fontWeight = FontWeight.Bold)
                IconButton({
                    counts[product.product.id] = actual + 1
                    viewModel.persistInventoryDraft(shelfId, counts.toMap())
                }) { Icon(Icons.Outlined.Add, null) }
            }
        }
        item {
            Button(
                onClick = {
                    scope.launch {
                        preview = viewModel.previewInventory(
                            shelfId,
                            products.associate { product ->
                                product.product.id to (counts[product.product.id]
                                    ?: if (started) 0 else product.stocks.firstOrNull { it.shelfId == shelfId }?.quantity
                                    ?: 0)
                            },
                        )
                    }
                },
                enabled = shelfId.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("Pregledaj razlike") }
        }
    }
    preview?.let { session ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Potvrda inventure") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (session.differences.isEmpty()) Text("Nema razlika. Stanje police odgovara evidenciji.")
                    session.differences.forEach { difference ->
                        Text(
                            "${when (difference.type) {
                                InventoryDifferenceType.MISSING -> "Nedostaje"
                                InventoryDifferenceType.UNEXPECTED -> "Neočekivano"
                                InventoryDifferenceType.QUANTITY -> "Razlika"
                            }} · ${difference.productName}: ${difference.expectedQuantity} → ${difference.actualQuantity}",
                        )
                    }
                }
            },
            confirmButton = {
                Button({
                    if (session.differences.isEmpty()) viewModel.discardInventoryDraft(session.id)
                    else viewModel.applyInventory(session)
                    preview = null
                    started = false
                    counts.clear()
                }) {
                    Text(if (session.differences.isEmpty()) "Završi bez promjena" else "Primijeni sve promjene")
                }
            },
            dismissButton = { TextButton({ preview = null }) { Text("Nastavi uređivati") } },
        )
    }
    if (scanning) InventoryScannerDialog(
        lastScan = lastScan,
        error = scanError,
        onError = { scanError = it },
        dismiss = { scanning = false },
    ) { code ->
        val item = products.firstOrNull { it.product.barcode == code }
        if (item == null) {
            scanError = "Barkod $code nije povezan ni s jednim artiklom u smočnici."
        } else {
            counts[item.product.id] = (counts[item.product.id] ?: 0) + 1
            viewModel.persistInventoryDraft(shelfId, counts.toMap())
            lastScan = "${item.product.name}: ${counts[item.product.id]} kom"
            scanError = null
        }
    }
}

@Composable
private fun InventoryScannerDialog(lastScan: String?, error: String?, onError: (String) -> Unit, dismiss: () -> Unit, scanned: (String) -> Unit) {
    val context = LocalContext.current
    var permission by rememberCameraPermissionState {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Skeniranje inventure") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (permission) Box(Modifier.fillMaxWidth().height(400.dp)) {
                BarcodeCamera(
                    modifier = Modifier.fillMaxSize(),
                    formats = intArrayOf(Barcode.FORMAT_EAN_8, Barcode.FORMAT_EAN_13, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E),
                    accepts = BarcodePolicy::isSupported,
                    onError = onError,
                    onBarcode = scanned,
                )
            } else Button({ launcher.launch(Manifest.permission.CAMERA) }) { Text("Dopusti kameru") }
            lastScan?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { Button(dismiss) { Text("Završi skeniranje") } },
    )
}

@Composable
private fun SimpleShelfPicker(options: List<Pair<String, String>>, selectedId: String, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        androidx.compose.material3.OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) {
            Text("Polica: ${options.firstOrNull { it.first == selectedId }?.second ?: "Odaberite"}")
        }
        androidx.compose.material3.DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (id, name) ->
                androidx.compose.material3.DropdownMenuItem({ Text(name) }, { select(id); expanded = false })
            }
        }
    }
}
