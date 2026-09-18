package hr.smocnica.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun ProductCameraDialog(additional: Boolean, dismiss: () -> Unit, fallback: () -> Unit, captured: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val previewView = remember { createBarcodePreviewView(context) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val onCaptured by rememberUpdatedState(captured)
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(lifecycle) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        var disposed = false
        future.addListener({
            if (!disposed) runCatching {
                provider = future.get()
                provider!!.unbindAll()
                provider!!.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                ready = true
            }.onFailure { error = "Kamera nije dostupna. Upotrijebite sistemsku kameru ili nastavite ručno." }
        }, ContextCompat.getMainExecutor(context))
        onDispose { disposed = true; provider?.unbind(preview, capture) }
    }
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (additional) "Snimi stranu s gramažom ili volumenom" else "Fotografiraj prednju stranu pakiranja", style = MaterialTheme.typography.titleLarge)
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().weight(1f))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Fotografija se šalje Google Geminiju radi prepoznavanja. Podatke potvrđujete prije dodavanja.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    busy = true
                    val file = createProductPhotoCaptureFile(context.cacheDir)
                    capture.targetRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                    capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) { onCaptured(file.absolutePath) }
                        override fun onError(exception: ImageCaptureException) {
                            deleteTemporaryProductPhoto(context.cacheDir, file.absolutePath)
                            busy = false; error = "Snimanje nije uspjelo. Pokušajte ponovno."
                        }
                    })
                }, enabled = ready && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Snimanje…" else "Snimi fotografiju") }
                TextButton(fallback, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Upotrijebi sistemsku kameru") }
                TextButton(dismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Nastavi bez fotografiranja") }
            }
        }
    }
}
