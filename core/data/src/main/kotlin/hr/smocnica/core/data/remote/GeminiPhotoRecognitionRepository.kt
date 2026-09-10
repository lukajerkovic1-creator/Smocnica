package hr.smocnica.core.data.remote

import hr.smocnica.core.domain.PhotoProductSuggestion
import hr.smocnica.core.domain.PhotoRecognitionRepository
import hr.smocnica.core.model.PackageUnit
import java.util.Base64
import javax.inject.Inject

class GeminiPhotoRecognitionRepository @Inject constructor(
    private val callable: FirebaseCallableClient,
) : PhotoRecognitionRepository {
    override suspend fun recognize(pantryId: String, jpeg: ByteArray): PhotoProductSuggestion {
        require(pantryId.isNotBlank())
        require(jpeg.size in 4..5 * 1024 * 1024)
        val result = callable.call("recognizeProductPhoto", mapOf(
            "pantryId" to pantryId,
            "photoBase64" to Base64.getEncoder().encodeToString(jpeg),
        ))
        return PhotoProductSuggestion(
            name = result["name"] as? String ?: error("Neispravan prijedlog proizvoda."),
            manufacturer = result["manufacturer"] as? String ?: "",
            packageAmount = result["packageAmount"] as? String ?: "",
            packageUnit = (result["packageUnit"] as? String)?.takeIf { it.isNotBlank() }
                ?.let(PackageUnit::valueOf) ?: PackageUnit.UNKNOWN,
        )
    }
}
