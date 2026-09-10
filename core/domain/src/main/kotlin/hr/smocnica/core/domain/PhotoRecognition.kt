package hr.smocnica.core.domain

import hr.smocnica.core.model.PackageUnit

data class PhotoProductSuggestion(
    val name: String,
    val manufacturer: String,
    val packageAmount: String,
    val packageUnit: PackageUnit,
) {
    init {
        require(name.isNotBlank() && name.length <= 100)
        require(manufacturer.length <= 100)
        require((packageAmount.isBlank()) == (packageUnit == PackageUnit.UNKNOWN))
        if (packageAmount.isNotBlank()) PackageAmountPolicy.baseAmount(packageAmount, packageUnit)
    }
}

interface PhotoRecognitionRepository {
    suspend fun recognize(pantryId: String, jpeg: ByteArray): PhotoProductSuggestion
}
