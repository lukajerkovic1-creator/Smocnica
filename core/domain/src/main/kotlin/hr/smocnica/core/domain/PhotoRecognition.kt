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
    suspend fun recognize(pantryId: String, jpeg: ByteArray, additionalJpeg: ByteArray? = null): PhotoProductSuggestion
}

/** Recognition can fill missing back-label data, but never overwrite an intervening edit. */
data class PhotoEntryFields(val name: String, val manufacturer: String, val amount: String, val unit: PackageUnit) {
    fun merge(starting: PhotoEntryFields, suggestion: PhotoProductSuggestion, additional: Boolean): PhotoEntryFields = copy(
        name = if (name == starting.name && (!additional || name.isBlank())) suggestion.name else name,
        manufacturer = if (manufacturer == starting.manufacturer && (!additional || manufacturer.isBlank())) suggestion.manufacturer else manufacturer,
        amount = if (amount == starting.amount && unit == starting.unit && (!additional || amount.isBlank())) suggestion.packageAmount else amount,
        unit = if (amount == starting.amount && unit == starting.unit && (!additional || amount.isBlank())) suggestion.packageUnit else unit,
    )
}
