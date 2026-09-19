package hr.smocnica.ui

import hr.smocnica.core.model.ProductVariant
import java.math.BigDecimal
import java.util.Locale

internal fun inventoryPackageLabel(productName: String, variant: ProductVariant): String {
    val amount = variant.packageAmountBase
    val hasSize = amount != null && amount > 0 && variant.packageUnit.multiplierToBase > 0
    val size = if (hasSize) {
        val number = BigDecimal.valueOf(requireNotNull(amount))
            .divide(BigDecimal.valueOf(variant.packageUnit.multiplierToBase))
            .stripTrailingZeros().toPlainString().replace('.', ',')
        "$number ${packageUnitLabel(variant.packageUnit)}"
    } else variant.packageLabel.trim().ifBlank { "Nepoznata veličina" }
    return listOf(
        productName.trim().ifBlank { variant.displayName.trim().ifBlank { "Artikl" } },
        size,
        variant.manufacturer,
        variant.displayName,
        if (!hasSize && !variant.barcode.isNullOrBlank()) "Barkod: ${variant.barcode}" else "",
    ).map(String::trim).filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.forLanguageTag("hr")) }
        .joinToString(" · ")
}
