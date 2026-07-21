package hr.smocnica.core.data.repository

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import hr.smocnica.core.data.remote.FirebaseNotConfiguredException
import hr.smocnica.core.domain.ProductPhotoRepository
import kotlinx.coroutines.tasks.await
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseProductPhotoRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ProductPhotoRepository {
    override suspend fun uploadJpeg(pantryId: String, productId: String, jpegPath: String): String {
        require(pantryId.isNotBlank() && productId.isNotBlank()) { "Nedostaje identitet artikla." }
        val jpeg = File(jpegPath)
        require(jpeg.isFile && jpeg.length() in 1..MAX_PHOTO_BYTES) { "Fotografija mora biti JPEG do 5 MiB." }
        val signature = jpeg.inputStream().use { input -> byteArrayOf(input.read().toByte(), input.read().toByte()) }
        require(signature[0] == 0xFF.toByte() && signature[1] == 0xD8.toByte()) { "Datoteka nije valjana JPEG fotografija." }
        if (FirebaseApp.getApps(context).isEmpty()) throw FirebaseNotConfiguredException()
        val reference = FirebaseStorage.getInstance().reference
            .child("pantries/$pantryId/products/$productId/main.jpg")
        val metadata = StorageMetadata.Builder()
            .setContentType("image/jpeg")
            .setCustomMetadata("productId", productId)
            .build()
        reference.putFile(android.net.Uri.fromFile(jpeg), metadata).await()
        return reference.toString()
    }

    private companion object { const val MAX_PHOTO_BYTES = 5 * 1024 * 1024 }
}
