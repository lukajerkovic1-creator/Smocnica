package hr.smocnica.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

@Composable
internal fun ProductPhoto(
    uri: String?,
    version: Long,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    if (uri == null) {
        Icon(Icons.Outlined.Inventory2, contentDescription, modifier)
        return
    }
    if (!uri.startsWith("gs://")) {
        AsyncImage(uri, contentDescription, modifier, contentScale = ContentScale.Crop)
        return
    }
    val context = LocalContext.current
    val bytes by produceState<ByteArray?>(initialValue = null, uri, version) {
        value = runCatching {
            withContext(Dispatchers.IO) {
                val key = MessageDigest.getInstance("SHA-256")
                    .digest("$uri:$version".toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                val file = File(context.cacheDir, "product-image-$key.jpg")
                if (file.isFile && file.length() in 1..MAX_PHOTO_BYTES) file.readBytes()
                else FirebaseStorage.getInstance().getReferenceFromUrl(uri).getBytes(MAX_PHOTO_BYTES).await()
                    .also { downloaded -> file.writeBytes(downloaded) }
            }
        }.getOrNull()
    }
    val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription, modifier, contentScale = ContentScale.Crop)
    else Icon(Icons.Outlined.Inventory2, contentDescription, modifier)
}

private const val MAX_PHOTO_BYTES = 5L * 1024 * 1024
