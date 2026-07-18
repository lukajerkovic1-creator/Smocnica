package hr.smocnica.ui

import android.net.Uri
import hr.smocnica.core.model.Product

enum class ScannerMode { DEFAULT, ADD, REMOVE, MOVE }

data class ScannerContext(
    val sourceLabel: String = "Skeniranje",
    val shelfId: String = "",
    val productId: String = "",
    val shoppingItemId: String = "",
    val mode: ScannerMode = ScannerMode.DEFAULT,
)

sealed interface ScannerCompletion {
    val message: String

    data class StockAdjusted(
        val productId: String,
        val shelfId: String,
        val delta: Int,
        override val message: String,
    ) : ScannerCompletion

    data class StockMoved(
        val productId: String,
        val fromShelfId: String,
        val toShelfId: String,
        val quantity: Int,
        override val message: String,
    ) : ScannerCompletion

    data class ProductCreated(
        val product: Product,
        override val message: String,
    ) : ScannerCompletion
}

fun scannerRoute(context: ScannerContext): String = buildString {
    append("scanner/context?source=")
    append(Uri.encode(context.sourceLabel))
    append("&shelfId=")
    append(Uri.encode(context.shelfId))
    append("&productId=")
    append(Uri.encode(context.productId))
    append("&shoppingItemId=")
    append(Uri.encode(context.shoppingItemId))
    append("&mode=")
    append(context.mode.name)
}

fun stocksRoute(shelfId: String = "", action: String = "", filter: String = ""): String =
    "stocks?shelfId=${Uri.encode(shelfId)}&action=${Uri.encode(action)}&filter=${Uri.encode(filter)}"
