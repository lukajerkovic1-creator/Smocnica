package hr.smocnica.core.domain

import hr.smocnica.core.model.InventoryCount
import hr.smocnica.core.model.InventoryDifference
import hr.smocnica.core.model.InventoryDifferenceType
import hr.smocnica.core.model.ProductWithStock
import hr.smocnica.core.model.Stock
import java.security.MessageDigest

data class QuantityOutcome(
    val previousTotal: Int,
    val newTotal: Int,
    val shortfall: Int,
    val crossedBelowMinimum: Boolean,
)

object StockPolicy {
    fun adjust(currentOnShelf: Int, currentTotal: Int, delta: Int, minimum: Int): QuantityOutcome {
        require(currentOnShelf >= 0 && currentTotal >= currentOnShelf) { "Neispravno postojeće stanje." }
        require(minimum >= 0) { "Minimalna količina ne može biti negativna." }
        require(currentOnShelf + delta >= 0) { "Nije moguće izvaditi više od dostupne količine." }
        val updatedTotal = currentTotal + delta
        require(updatedTotal >= 0) { "Ukupna količina ne može biti negativna." }
        return QuantityOutcome(
            previousTotal = currentTotal,
            newTotal = updatedTotal,
            shortfall = (minimum - updatedTotal).coerceAtLeast(0),
            crossedBelowMinimum = currentTotal >= minimum && updatedTotal < minimum,
        )
    }

    fun move(fromQuantity: Int, quantity: Int) {
        require(quantity > 0) { "Količina za premještanje mora biti veća od nule." }
        require(quantity <= fromQuantity) { "Nema dovoljno artikala na izvornoj polici." }
    }
}

object BarcodePolicy {
    private val lengths = setOf(8, 12, 13)

    fun normalize(value: String): String = value.filterNot(Char::isWhitespace)

    fun isSupported(value: String): Boolean {
        val code = normalize(value)
        return code.length in lengths && code.all(Char::isDigit) &&
            (hasValidGtinChecksum(code) || (code.length == 8 && hasValidUpcEChecksum(code)))
    }

    fun requireSupported(value: String): String = normalize(value).also {
        require(isSupported(it)) { "Podržani su EAN-8, EAN-13, UPC-A i UPC-E s ispravnom kontrolnom znamenkom." }
    }

    private fun hasValidGtinChecksum(code: String): Boolean {
        val digits = code.map(Char::digitToInt)
        val expected = digits.last()
        val sum = digits.dropLast(1).reversed().mapIndexed { index, digit ->
            if (index % 2 == 0) digit * 3 else digit
        }.sum()
        return (10 - sum % 10) % 10 == expected
    }

    private fun hasValidUpcEChecksum(code: String): Boolean {
        if (code.first() !in "01") return false
        val numberSystem = code[0]
        val d = code.substring(1, 7)
        val body = when (d[5]) {
            '0', '1', '2' -> "$numberSystem${d[0]}${d[1]}${d[5]}0000${d[2]}${d[3]}${d[4]}"
            '3' -> "$numberSystem${d[0]}${d[1]}${d[2]}00000${d[3]}${d[4]}"
            '4' -> "$numberSystem${d[0]}${d[1]}${d[2]}${d[3]}00000${d[4]}"
            else -> "$numberSystem${d[0]}${d[1]}${d[2]}${d[3]}${d[4]}0000${d[5]}"
        }
        return hasValidGtinChecksum(body + code.last())
    }
}

object InventoryPolicy {
    fun snapshotVersion(stocks: List<Stock>): Long {
        val canonical = stocks.sortedBy { it.productId }
            .joinToString("|") { "${it.productId}:${it.quantity}" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .take(6)
            .fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }
    }

    fun differences(
        expected: List<ProductWithStock>,
        counts: List<InventoryCount>,
        shelfId: String,
    ): List<InventoryDifference> {
        val expectedByProduct = expected.associate { item ->
            item.product.id to (item.product.name to item.stocks
                .filter { it.shelfId == shelfId }
                .sumOf { it.quantity })
        }
        val actualByProduct = counts.groupBy(InventoryCount::productId)
            .mapValues { (_, productCounts) -> productCounts.sumOf(InventoryCount::actualQuantity) }
        return (expectedByProduct.keys + actualByProduct.keys).mapNotNull { productId ->
            val expectedQuantity = expectedByProduct[productId]?.second ?: 0
            val actualQuantity = actualByProduct[productId] ?: 0
            if (expectedQuantity == actualQuantity) return@mapNotNull null
            InventoryDifference(
                productId = productId,
                productName = expectedByProduct[productId]?.first ?: "Nepoznati artikl",
                expectedQuantity = expectedQuantity,
                actualQuantity = actualQuantity,
                type = when {
                    expectedQuantity > 0 && actualQuantity == 0 -> InventoryDifferenceType.MISSING
                    expectedQuantity == 0 && actualQuantity > 0 -> InventoryDifferenceType.UNEXPECTED
                    else -> InventoryDifferenceType.QUANTITY
                },
            )
        }.sortedWith(compareBy(InventoryDifference::type, InventoryDifference::productName))
    }
}

object ShelfPolicy {
    fun requireCanDelete(stockQuantities: List<Int>) {
        require(stockQuantities.none { it > 0 }) { "Polica se može obrisati tek kada je prazna." }
    }
}

class DuplicateScanGuard(private val windowMillis: Long = 1_500L) {
    private var lastBarcode: String? = null
    private var lastAcceptedAt: Long = Long.MIN_VALUE

    @Synchronized
    fun accept(barcode: String, now: Long): Boolean {
        val normalized = BarcodePolicy.requireSupported(barcode)
        if (normalized == lastBarcode && now - lastAcceptedAt < windowMillis) return false
        lastBarcode = normalized
        lastAcceptedAt = now
        return true
    }
}
