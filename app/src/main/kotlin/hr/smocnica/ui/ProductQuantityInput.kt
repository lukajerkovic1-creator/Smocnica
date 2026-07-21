package hr.smocnica.ui

internal const val MAX_PRODUCT_QUANTITY: Int = 1_000_000

internal fun parseProductQuantity(value: String): Int? =
    value.toIntOrNull()?.takeIf { it in 0..MAX_PRODUCT_QUANTITY }
