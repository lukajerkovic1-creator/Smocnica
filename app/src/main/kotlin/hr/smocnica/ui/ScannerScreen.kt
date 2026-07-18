package hr.smocnica.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.provider.Settings
import android.view.SoundEffectConstants
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
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
fun ScannerScreen(
    viewModel: MainViewModel,
    padding: PaddingValues,
    scannerContext: ScannerContext = ScannerContext(),
    onCompleted: (ScannerCompletion) -> Unit,
    lookup: ScannerLookupViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    val products by viewModel.allProducts.collectAsStateWithLifecycle()
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val catalog by lookup.result.collectAsStateWithLifecycle()
    val loading by lookup.loading.collectAsStateWithLifecycle()
    val shopping by viewModel.shopping.collectAsStateWithLifecycle()
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it; permissionDenied = !it }
    var detected by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf("") }
    var flash by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val local = detected?.let { code -> products.firstOrNull { it.product.barcode == code } }
    fun acceptBarcode(code: String) {
        val matched = products.firstOrNull { it.product.barcode == code }
        if (scannerContext.productId.isNotBlank() && matched?.product?.id != scannerContext.productId) {
            cameraError = "Skenirani barkod ne pripada odabranom artiklu."
            return
        }
        cameraError = null
        detected = code
        if (matched == null) lookup.lookup(code)
    }

    Column(Modifier.fillMaxSize().padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Skeniraj proizvod", "${scannerContext.sourceLabel} · EAN-8, EAN-13, UPC-A i UPC-E")
        if (!permission) {
            Box(Modifier.fillMaxWidth().height(360.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.QrCodeScanner, null)
                    Text("Za skeniranje je potreban pristup kameri.", Modifier.padding(12.dp))
                    Button({
                        if (permissionDenied) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
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
                    onBarcode = ::acceptBarcode,
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
                onClick = { acceptBarcode(BarcodePolicy.requireSupported(manual)) },
                enabled = BarcodePolicy.isSupported(manual),
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Potvrdi") }
        }
    }
    detected?.let { code ->
        when {
            local != null -> ScannerStockDialog(
                item = local,
                shelves = shelves.map { it.id to it.name },
                initialShelfId = scannerContext.shelfId,
                initialMode = scannerContext.mode,
                initialQuantity = shopping.firstOrNull { it.id == scannerContext.shoppingItemId }?.requiredQuantity ?: 1,
                dismiss = { detected = null },
                adjust = { shelfId, delta ->
                    viewModel.adjustStock(local.product.id, shelfId, delta) {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        view.playSoundEffect(SoundEffectConstants.CLICK)
                        onCompleted(ScannerCompletion.StockAdjusted(local.product.id, shelfId, delta, "${local.product.name}: stanje je ažurirano."))
                    }
                    detected = null
                },
                move = { fromShelfId, toShelfId, quantity ->
                    viewModel.moveStock(local.product.id, fromShelfId, toShelfId, quantity) {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        view.playSoundEffect(SoundEffectConstants.CLICK)
                        onCompleted(ScannerCompletion.StockMoved(local.product.id, fromShelfId, toShelfId, quantity, "${local.product.name}: premješteno $quantity kom."))
                    }
                    detected = null
                },
            )
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
                ProductEditor(suggested, shelves, categories, { detected = null }, initialShelfId = scannerContext.shelfId) { product, shelf, quantity, photo, source ->
                    viewModel.createProductAndStock(product, shelf, quantity, photo, source) { created ->
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        view.playSoundEffect(SoundEffectConstants.CLICK)
                        onCompleted(ScannerCompletion.ProductCreated(created, "${created.name}: dodano u smočnicu."))
                    }
                    detected = null
                }
            }
        }
    }
}

@Composable
private fun ScannerStockDialog(
    item: ProductWithStock,
    shelves: List<Pair<String, String>>,
    initialShelfId: String,
    initialMode: ScannerMode,
    initialQuantity: Int,
    dismiss: () -> Unit,
    adjust: (String, Int) -> Unit,
    move: (String, String, Int) -> Unit,
) {
    var shelfId by remember { mutableStateOf(initialShelfId.takeIf { id -> shelves.any { it.first == id } } ?: item.stocks.firstOrNull { it.quantity > 0 }?.shelfId ?: shelves.firstOrNull()?.first.orEmpty()) }
    var targetShelfId by remember { mutableStateOf(shelves.firstOrNull { it.first != shelfId }?.first.orEmpty()) }
    var mode by remember { mutableStateOf(initialMode.takeUnless { it == ScannerMode.DEFAULT } ?: ScannerMode.ADD) }
    var quantity by remember { mutableIntStateOf(initialQuantity.coerceAtLeast(1)) }
    var confirmRiskyAction by remember { mutableStateOf(false) }
    val available = item.stocks.firstOrNull { it.shelfId == shelfId }?.quantity ?: 0
    AlertDialog(onDismissRequest = dismiss, title = { Text(item.product.name) }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ mode = ScannerMode.ADD; confirmRiskyAction = false }) { Text("Dodaj") }
            Button({ mode = ScannerMode.REMOVE; confirmRiskyAction = false }) { Text("Izvadi") }
            Button({ mode = ScannerMode.MOVE; confirmRiskyAction = false }, enabled = item.totalQuantity > 0 && shelves.size > 1) { Text("Premjesti") }
        }
        SimpleScannerShelfPicker(shelves, shelfId) { selected ->
            shelfId = selected
            if (targetShelfId == selected) targetShelfId = shelves.firstOrNull { it.first != selected }?.first.orEmpty()
            confirmRiskyAction = false
        }
        if (mode == ScannerMode.MOVE) SimpleScannerShelfPicker(shelves.filterNot { it.first == shelfId }, targetShelfId) { targetShelfId = it }
        OutlinedTextField(quantity.toString(), { quantity = it.filter(Char::isDigit).toIntOrNull() ?: 1; confirmRiskyAction = false }, label = { Text("Količina") })
        if (mode != ScannerMode.ADD) Text("Dostupno: $available kom")
        if (confirmRiskyAction) Text(
            if (mode == ScannerMode.REMOVE) "Vadite zadnji komad. Ponovno pritisnite Potvrdi."
            else "Premještate veću ili cijelu količinu. Ponovno pritisnite Potvrdi.",
            color = MaterialTheme.colorScheme.error,
        )
    } }, confirmButton = {
        Button(
            onClick = {
                val risky = mode == ScannerMode.REMOVE && quantity == item.totalQuantity ||
                    mode == ScannerMode.MOVE && (quantity == available || quantity >= 5)
                if (risky && !confirmRiskyAction) confirmRiskyAction = true
                else if (mode == ScannerMode.MOVE) move(shelfId, targetShelfId, quantity)
                else adjust(shelfId, if (mode == ScannerMode.ADD) quantity else -quantity)
            },
            enabled = shelfId.isNotBlank() && quantity > 0 && (mode == ScannerMode.ADD || quantity <= available) &&
                (mode != ScannerMode.MOVE || targetShelfId.isNotBlank() && targetShelfId != shelfId),
        ) { Text("Potvrdi") }
    }, dismissButton = { TextButton(dismiss) { Text("Odustani") } })
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
    onError: (String) -> Unit,
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
