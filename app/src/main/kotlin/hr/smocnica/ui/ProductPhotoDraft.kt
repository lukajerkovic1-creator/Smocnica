package hr.smocnica.ui

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_PHOTO_BYTES = 5L * 1024 * 1024
private const val DRAFT_PREFIX = "product-photo-draft-"
private const val CAPTURE_PREFIX = "product-photo-capture-"

internal fun createProductPhotoCaptureFile(cacheDir: File): File =
    File.createTempFile(CAPTURE_PREFIX, ".jpg", cacheDir)

@Composable
internal fun rememberProductPhotoDraftPath(key: String?): MutableState<String?> =
    rememberSaveable(key) { mutableStateOf(null) }

internal suspend fun resizeJpegToTempFile(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val output = File.createTempFile(DRAFT_PREFIX, ".jpg", context.cacheDir)
    try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val scale = minOf(1f, 2048f / maxOf(width, height).toFloat())
            decoder.setTargetSize(
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
            )
        }
        try {
            output.outputStream().buffered().use { stream ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)) {
                    "Fotografiju nije moguće obraditi."
                }
            }
        } finally {
            bitmap.recycle()
        }
        require(output.length() in 1..MAX_PHOTO_BYTES) { "Fotografija nakon obrade prelazi 5 MiB." }
        output
    } catch (failure: Throwable) {
        output.delete()
        throw failure
    }
}

internal fun deleteTemporaryProductPhoto(cacheDir: File, path: String?): Boolean {
    if (path.isNullOrBlank()) return false
    val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
    val cache = runCatching { cacheDir.canonicalFile }.getOrNull() ?: return false
    if (candidate.parentFile != cache) return false
    if (!candidate.name.startsWith(DRAFT_PREFIX) && !candidate.name.startsWith(CAPTURE_PREFIX)) return false
    return !candidate.exists() || candidate.delete()
}
