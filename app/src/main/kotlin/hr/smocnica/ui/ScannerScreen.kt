package hr.smocnica.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dagger.hilt.android.lifecycle.HiltViewModel
import hr.smocnica.MainViewModel
import hr.smocnica.core.domain.BarcodePolicy
import hr.smocnica.core.domain.CatalogProduct
import hr.smocnica.core.domain.DuplicateScanGuard
import hr.smocnica.core.domain.ProductCatalogRepository
import hr.smocnica.core.model.Product
import hr.smocnica.core.model.ProductWithStock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import javax.inject.Inject

@HiltViewModel
class ScannerLookupViewModel @Inject constructor(private val catalog: ProductCatalogRepository) : ViewModel() {
    private val _result = MutableStateFlow<CatalogProduct?>(null)
    val result = _result.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    fun lookup(barcode: String) {
        viewModelScope.launch {
            _loading.value = true
            _result.value = runCatching { catalog.findByBarcode(barcode) }.getOrNull()
            _loading.value = false
        }
    }
}

@Composable
fun ScannerScreen(viewModel: MainViewModel, padding: PaddingValues, lookup: ScannerLookupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val products by viewModel.allProducts.collectAsStateWithLifecycle()
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val catalog by lookup.result.collectAsStateWithLifecycle()
    val loading by lookup.loading.collectAsStateWithLifecycle()
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it; permissionDenied = !it }
    var detected by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf("") }
    var flash by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val local = detected?.let { code -> products.firstOrNull { it.product.barcode == code } }

    Column(Modifier.fillMaxSize().padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Skeniraj proizvod", "EAN-8, EAN-13, UPC-A i UPC-E")
        if (!permission) {
            Box(Modifier.fillMaxWidth().height(360.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.QrCodeScanner, null)
                    Text("Za skeniranje je potreban pristup kameri.", Modifier.padding(12.dp))
                    Button({
                        if (permissionDenied) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        else permissionLauncher.launch(Manifest.permission.CAMERA)
                    }) { Text(if (permissionDenied) "Otvori postavke" else "Dopusti kameru") }
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                BarcodeCamera(
                    modifier = Modifier.fillMaxSize(),
                    onCamera = { camera = it; it.cameraControl.enableTorch(flash) },
                    formats = intArrayOf(Barcode.FORMAT_EAN_8, Barcode.FORMAT_EAN_13, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E),
                    accepts = BarcodePolicy::isSupported,
                    onError = { cameraError = it },
                    onBarcode = { code -> detected = code; if (products.none { it.product.barcode == code }) lookup.lookup(code) },
                )
                IconButton(
                    onClick = { flash = !flash; camera?.cameraControl?.enableTorch(flash) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color.Black.copy(alpha = .5f), RoundedCornerShape(50)),
                ) { Icon(if (flash) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff, "Bljeskalica", tint = Color.White) }
            }
            cameraError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(manual, { manual = it.filter(Char::isDigit) }, label = { Text("Ručni unos barkoda") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(
                onClick = { val code = BarcodePolicy.requireSupported(manual); detected = code; if (products.none { it.product.barcode == code }) lookup.lookup(code) },
                enabled = BarcodePolicy.isSupported(manual),
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Potvrdi") }
        }
    }
    detected?.let { code ->
        when {
            local != null -> ScannerStockDialog(local, shelves.map { it.id to it.name }, { detected = null }) { shelfId, delta ->
                viewModel.adjustStock(local.product.id, shelfId, delta); detected = null
            }
            loading -> AlertDialog(onDismissRequest = { detected = null }, confirmButton = { TextButton({ detected = null }) { Text("Odustani") } }, title = { Text("Dohvat proizvoda") }, text = { Text("Provjeravam Open Food Facts…") })
            else -> {
                val now = System.currentTimeMillis()
                val suggested = Product(
                    id = "", pantryId = "", name = catalog?.name.orEmpty(), barcode = code,
                    description = catalog?.description.orEmpty(), category = catalog?.category ?: "Ostalo",
                    photoUri = catalog?.imageUrl,
                    photoSource = if (catalog?.imageUrl != null) hr.smocnica.core.model.PhotoSource.OPEN_FOOD_FACTS else hr.smocnica.core.model.PhotoSource.NONE,
                    createdAt = now, updatedAt = now,
                )
                ProductEditor(suggested, shelves, categories, { detected = null }) { product, shelf, quantity, photo, source ->
                    viewModel.createProductAndStock(product, shelf, quantity, photo, source); detected = null
                }
            }
        }
    }
}

@Composable
private fun ScannerStockDialog(item: ProductWithStock, shelves: List<Pair<String, String>>, dismiss: () -> Unit, apply: (String, Int) -> Unit) {
    var shelfId by remember { mutableStateOf(item.stocks.firstOrNull { it.quantity > 0 }?.shelfId ?: shelves.firstOrNull()?.first.orEmpty()) }
    var adding by remember { mutableStateOf(true) }
    var quantity by remember { mutableIntStateOf(1) }
    val available = item.stocks.firstOrNull { it.shelfId == shelfId }?.quantity ?: 0
    AlertDialog(onDismissRequest = dismiss, title = { Text(item.product.name) }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ adding = true }) { Text("Dodaj u smočnicu") }; Button({ adding = false }) { Text("Izvadi") } }
        SimpleScannerShelfPicker(shelves, shelfId) { shelfId = it }
        OutlinedTextField(quantity.toString(), { quantity = it.filter(Char::isDigit).toIntOrNull() ?: 1 }, label = { Text("Količina") })
        if (!adding) Text("Dostupno: $available kom")
    } }, confirmButton = { Button({ apply(shelfId, if (adding) quantity else -quantity) }, enabled = shelfId.isNotBlank() && quantity > 0 && (adding || quantity <= available)) { Text("Potvrdi") } }, dismissButton = { TextButton(dismiss) { Text("Odustani") } })
}

@Composable
private fun SimpleScannerShelfPicker(options: List<Pair<String, String>>, selected: String, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column { androidx.compose.material3.OutlinedButton({ expanded = true }) { Text(options.firstOrNull { it.first == selected }?.second ?: "Odaberite policu") }
        androidx.compose.material3.DropdownMenu(expanded, { expanded = false }) { options.forEach { option -> androidx.compose.material3.DropdownMenuItem({ Text(option.second) }, { select(option.first); expanded = false }) } }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
internal fun BarcodeCamera(
    modifier: Modifier,
    onCamera: ((Camera) -> Unit)? = null,
    formats: IntArray,
    accepts: (String) -> Boolean,
    onError: (String) -> Unit = {},
    onBarcode: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val guard = remember { DuplicateScanGuard() }
    val previewView = remember(context) { createBarcodePreviewView(context) }
    val providerFuture = remember(context) { ProcessCameraProvider.getInstance(context) }
    val currentAccepts by rememberUpdatedState(accepts)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnBarcode by rememberUpdatedState(onBarcode)
    val currentOnCamera by rememberUpdatedState(onCamera)
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(formats.first(), *formats.drop(1).toIntArray()).build(),
        )
    }
    DisposableEffect(Unit) { onDispose { executor.shutdown(); scanner.close() } }
    DisposableEffect(lifecycle, previewView, providerFuture) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null

        providerFuture.addListener({
            if (disposed) return@addListener
            runCatching {
                val activeProvider = providerFuture.get()
                val activePreview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val activeAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                activeAnalysis.setAnalyzer(executor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage == null) {
                        imageProxy.close()
                    } else {
                        scanner.process(InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees))
                            .addOnSuccessListener { barcodes ->
                                barcodes.firstNotNullOfOrNull { it.rawValue }?.let { value ->
                                    if (currentAccepts(value) && guard.accept(value, System.currentTimeMillis())) {
                                        currentOnBarcode(value)
                                    }
                                }
                            }
                            .addOnFailureListener { currentOnError("Barkod nije moguće očitati. Pokušajte ponovno.") }
                            .addOnCompleteListener { imageProxy.close() }
                    }
                }
                activeProvider.unbindAll()
                if (!disposed) {
                    provider = activeProvider
                    preview = activePreview
                    analysis = activeAnalysis
                    currentOnCamera?.invoke(
                        activeProvider.bindToLifecycle(
                            lifecycle,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            activePreview,
                            activeAnalysis,
                        ),
                    )
                } else {
                    activeAnalysis.clearAnalyzer()
                }
            }.onFailure { currentOnError("Kamera nije dostupna na ovom uređaju.") }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            analysis?.clearAnalyzer()
            val activeProvider = provider
            val activePreview = preview
            val activeAnalysis = analysis
            if (activeProvider != null && activePreview != null && activeAnalysis != null) {
                activeProvider.unbind(activePreview, activeAnalysis)
            }
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

internal fun createBarcodePreviewView(context: Context): PreviewView = PreviewView(context).apply {
    // SurfaceView previews can render behind a Compose dialog on some Android devices.
    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    scaleType = PreviewView.ScaleType.FILL_CENTER
}
