package hr.smocnica.core.data.repository

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import hr.smocnica.core.data.remote.FirebaseNotConfiguredException
import hr.smocnica.core.domain.ProductPhotoRepository
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseProductPhotoRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ProductPhotoRepository {
    override suspend fun uploadJpeg(pantryId: String, productId: String, jpeg: ByteArray): String {
        require(pantryId.isNotBlank() && productId.isNotBlank()) { "Nedostaje identitet artikla." }
        require(jpeg.isNotEmpty() && jpeg.size <= MAX_PHOTO_BYTES) { "Fotografija mora biti JPEG do 5 MiB." }
        require(jpeg.size >= 3 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "Datoteka nije valjana JPEG fotografija." }
        if (FirebaseApp.getApps(context).isEmpty()) throw FirebaseNotConfiguredException()
        val reference = FirebaseStorage.getInstance().reference
            .child("pantries/$pantryId/products/$productId/main.jpg")
        val metadata = StorageMetadata.Builder()
            .setContentType("image/jpeg")
            .setCustomMetadata("productId", productId)
            .build()
        reference.putBytes(jpeg, metadata).await()
        return reference.toString()
    }

    override suspend fun deleteMainPhoto(pantryId: String, productId: String) {
        if (FirebaseApp.getApps(context).isEmpty()) throw FirebaseNotConfiguredException()
        FirebaseStorage.getInstance().reference
            .child("pantries/$pantryId/products/$productId/main.jpg")
            .delete()
            .await()
    }

    private companion object { const val MAX_PHOTO_BYTES = 5 * 1024 * 1024 }
}
