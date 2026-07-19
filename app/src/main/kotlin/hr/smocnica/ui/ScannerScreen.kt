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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import javax.inject.Inject

@HiltViewModel
class ScannerLookupViewModel @Inject constructor(private val catalog: ProductCatalogRepository) : ViewModel() {
    private val _state = MutableStateFlow(CatalogLookupState())
    val state = _state.asStateFlow()
    private var lookupJob: Job? = null

    fun lookup(barcode: String) {
        if (_state.value.barcode == barcode && _state.value.isLoading) return
        lookupJob?.cancel()
        _state.value = CatalogLookupState(barcode, CatalogLookupOutcome.LOADING)
        lookupJob = viewModelScope.launch {
            _state.value = try {
                val product = withTimeout(8_000) { catalog.findByBarcode(barcode) }
                CatalogLookupState(
                    barcode,
                    if (product == null) CatalogLookupOutcome.EMPTY else CatalogLookupOutcome.SUCCESS,
                    product,
                )
            } catch (_: TimeoutCancellationException) {
                CatalogLookupState(barcode, CatalogLookupOutcome.TIMEOUT)
            } catch (_: Exception) {
                CatalogLookupState(barcode, CatalogLookupOutcome.ERROR)
            }
        }
    }

    fun continueManually() {
        lookupJob?.cancel()
        _state.value = CatalogLookupState(_state.value.barcode, CatalogLookupOutcome.IDLE)
    }

    fun skipLookup(barcode: String) {
        lookupJob?.cancel()
        _state.value = CatalogLookupState(barcode, CatalogLookupOutcome.IDLE)
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
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    val products by viewModel.allProducts.collectAsStateWithLifecycle()
    val deletedProducts by viewModel.deletedProducts.collectAsStateWithLifecycle()
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val lookupState by lookup.state.collectAsStateWithLifecycle()
    val catalog = lookupState.product
    val shopping by viewModel.shopping.collectAsStateWithLifecycle()
    var detected by remember { mutableStateOf<String?>(null) }
    var scannerError by remember { mutableStateOf<String?>(null) }
    val local = detected?.let { code -> products.firstOrNull { it.product.barcode == code } }
    fun acceptBarcode(code: String) {
        val matched = products.firstOrNull { it.product.barcode == code }
        if (scannerContext.productId.isNotBlank() && matched?.product?.id != scannerContext.productId) {
            scannerError = "Skenirani barkod ne pripada odabranom artiklu."
            return
        }
        scannerError = null
        detected = code
        if (matched == null) {
            if (deletedProducts.any { it.product.barcode == code }) lookup.skipLookup(code) else lookup.lookup(code)
        }
    }

    SharedBarcodeScannerContent(
        modifier = Modifier.fillMaxSize().padding(padding),
        title = "Skeniraj proizvod",
        subtitle = "${scannerContext.sourceLabel} · EAN-8, EAN-13, UPC-A i UPC-E",
        externalError = scannerError,
        onBarcode = ::acceptBarcode,
    )
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
            lookupState.barcode != code || lookupState.isLoading -> AlertDialog(onDismissRequest = { detected = null }, confirmButton = { TextButton({ detected = null; lookup.continueManually() }) { Text("Odustani") } }, title = { Text("Dohvat proizvoda") }, text = { Text("Provjeravam Open Food Facts…") })
            else -> {
                val now = System.currentTimeMillis()
                val suggested = Product(
                    id = "", pantryId = "", name = catalog?.name.orEmpty(), barcode = code,
                    description = catalog?.description.orEmpty(), category = catalog?.category ?: "Ostalo",
                    photoUri = catalog?.imageUrl,
                    photoSource = if (catalog?.imageUrl != null) hr.smocnica.core.model.PhotoSource.OPEN_FOOD_FACTS else hr.smocnica.core.model.PhotoSource.NONE,
                    createdAt = now, updatedAt = now,
                )
                ProductEditor(
                    current = suggested,
                    shelves = shelves,
                    categories = categories,
                    onDismiss = { detected = null },
                    initialShelfId = scannerContext.shelfId,
                    activeProducts = products,
                    deletedProducts = deletedProducts,
                    catalogLookup = lookupState,
                    requestCatalogLookup = lookup::lookup,
                    continueManually = lookup::continueManually,
                    showInventoryMatchInitially = deletedProducts.any { it.product.barcode == code },
                    onAddExisting = { item, shelf, quantity, done ->
                        viewModel.adjustStock(item.product.id, shelf, quantity, onAdjusted = {
                            done(true)
                            onCompleted(ScannerCompletion.StockAdjusted(item.product.id, shelf, quantity, "${item.product.name}: stanje je ažurirano."))
                        }, onFailure = { done(false) })
                    },
                    onRestoreDeleted = { item, shelf, quantity, done ->
                        viewModel.restoreProductAndAddStock(item.product.id, shelf, quantity, onRestored = {
                            done(true)
                            onCompleted(ScannerCompletion.ProductRestored(item.product, shelf, quantity, "${item.product.name}: vraćen iz koša i dodan u smočnicu."))
                        }, onFailure = { done(false) })
                    },
                ) { product, shelf, quantity, photo, source, done ->
                    viewModel.createProductAndStock(product, shelf, quantity, photo, source, onCreated = { created ->
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            view.playSoundEffect(SoundEffectConstants.CLICK)
                            done(true)
                            onCompleted(ScannerCompletion.ProductCreated(created, "${created.name}: dodano u smočnicu."))
                        }, onFailure = { done(false) })
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

@Composable
internal fun SharedBarcodeScannerDialog(
    title: String,
    onDismiss: () -> Unit,
    onBarcode: (String) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SharedBarcodeScannerContent(
                modifier = Modifier.fillMaxSize(),
                title = title,
                subtitle = "EAN-8, EAN-13, UPC-A i UPC-E",
                onDismiss = onDismiss,
                onBarcode = onBarcode,
            )
        }
    }
}

@Composable
internal fun SharedBarcodeScannerContent(
    modifier: Modifier,
    title: String,
    subtitle: String,
    externalError: String? = null,
    onDismiss: (() -> Unit)? = null,
    onBarcode: (String) -> Unit,
) {
    val context = LocalContext.current
    var permission by rememberCameraPermissionState {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission = granted
        permissionDenied = !granted
    }
    var manual by rememberSaveable { mutableStateOf("") }
    var flash by rememberSaveable { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var hasFlashUnit by remember { mutableStateOf(false) }
    var cameraError by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(permission) {
        if (permission) permissionDenied = false
        else {
            flash = false
            hasFlashUnit = false
            camera = null
        }
    }

    Column(modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { ScreenTitle(title, subtitle) }
            onDismiss?.let { close -> IconButton(close) { Icon(Icons.Outlined.Close, "Zatvori skener") } }
        }
        if (!permission) {
            Box(
                Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                CameraPermissionRequired(
                    permissionDenied,
                    requestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    openSettings = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                    },
                )
            }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                BarcodeCamera(
                    modifier = Modifier.fillMaxSize(),
                    onCamera = {
                        camera = it
                        hasFlashUnit = it.cameraInfo.hasFlashUnit()
                        if (hasFlashUnit) it.cameraControl.enableTorch(flash) else flash = false
                    },
                    formats = intArrayOf(Barcode.FORMAT_EAN_8, Barcode.FORMAT_EAN_13, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E),
                    accepts = BarcodePolicy::isSupported,
                    onError = { cameraError = it },
                    onBarcode = onBarcode,
                )
                ScannerFlashButton(
                    hasFlashUnit = hasFlashUnit,
                    flash = flash,
                    onToggle = { flash = !flash; camera?.cameraControl?.enableTorch(flash) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color.Black.copy(alpha = .5f), RoundedCornerShape(50)),
                )
            }
        }
        (externalError ?: cameraError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                manual,
                { manual = it.filter(Char::isDigit) },
                label = { Text("Ručni unos barkoda") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(
                onClick = { onBarcode(BarcodePolicy.requireSupported(manual)) },
                enabled = BarcodePolicy.isSupported(manual),
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Potvrdi") }
        }
    }
}

@Composable
internal fun ScannerFlashButton(
    hasFlashUnit: Boolean,
    flash: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!hasFlashUnit) return
    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(if (flash) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff, "Bljeskalica", tint = Color.White)
    }
}

@Composable
internal fun CameraPermissionRequired(
    permissionDenied: Boolean,
    requestPermission: () -> Unit,
    openSettings: () -> Unit,
) {
    Column(
        Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.QrCodeScanner, null)
        Text("Za skeniranje je potreban pristup kameri. Barkod i dalje možete upisati ručno.")
        Button(requestPermission) { Text(if (permissionDenied) "Ponovno zatraži dopuštenje" else "Dopusti kameru") }
        OutlinedButton(openSettings) { Text("Otvori postavke dopuštenja") }
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
