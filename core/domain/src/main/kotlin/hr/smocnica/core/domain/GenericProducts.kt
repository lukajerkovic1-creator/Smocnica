package hr.smocnica.core.domain

import hr.smocnica.core.model.GroupingSuggestion
import hr.smocnica.core.model.MeasurementKind
import hr.smocnica.core.model.MinimumMode
import hr.smocnica.core.model.PackageUnit
import hr.smocnica.core.model.ProductVariant
import hr.smocnica.core.model.ProductWithStock
import hr.smocnica.core.model.Stock
import hr.smocnica.core.model.SynonymRule
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Locale

data class ParsedPackage(
    val amountBase: Long,
    val unit: PackageUnit,
    val label: String,
)

object GenericNamePolicy {
    private val locale = Locale.forLanguageTag("hr")
    private val builtIn = mapOf(
        "pšenično brašno t-550" to "Glatko brašno",
        "pšenično brašno t 550" to "Glatko brašno",
        "brašno glatko" to "Glatko brašno",
        "glatko brašno" to "Glatko brašno",
        "mlijeko trajno" to "Trajno mlijeko",
        "trajno mlijeko" to "Trajno mlijeko",
        "paradajz pasiran" to "Pasirana rajčica",
        "pasirana rajčica" to "Pasirana rajčica",
        "šećer kristal" to "Kristal šećer",
        "kristal šećer" to "Kristal šećer",
        "ulje suncokretovo" to "Suncokretovo ulje",
        "suncokretovo ulje" to "Suncokretovo ulje",
    )

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(locale)

    fun displayName(value: String): String {
        val normalized = value.trim().replace(Regex("\\s+"), " ")
        require(normalized.isNotEmpty()) { "Naziv artikla je obvezan." }
        return normalized.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    fun suggest(
        rawName: String,
        existing: List<Pair<String, String>>,
        sharedRules: List<SynonymRule>,
    ): GroupingSuggestion {
        val source = normalize(rawName)
        val confirmed = sharedRules.firstOrNull { it.deletedAt == null && it.sourceNormalized == source }
        if (confirmed != null) {
            return GroupingSuggestion(rawName, displayName(confirmed.genericName), confirmed.productId, 100, confirmed.id)
        }
        builtIn[source]?.let { target ->
            val productId = existing.firstOrNull { normalize(it.second) == normalize(target) }?.first
            return GroupingSuggestion(rawName, target, productId, 95)
        }
        existing.firstOrNull { normalize(it.second) == source }?.let { (id, name) ->
            return GroupingSuggestion(rawName, displayName(name), id, 90)
        }
        val stripped = source
            .replace(Regex("\\b\\d+(?:[.,]\\d+)?\\s*(?:mg|g|kg|ml|l|kom|komada|rola|vrećica|kapsula)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        existing.map { (id, name) -> Triple(id, name, tokenSimilarity(stripped, normalize(name))) }
            .maxByOrNull { it.third }
            ?.takeIf { it.third >= 0.75 }
            ?.let { return GroupingSuggestion(rawName, displayName(it.second), it.first, (it.third * 100).toInt()) }
        return GroupingSuggestion(rawName, displayName(rawName), null, 50)
    }

    private fun tokenSimilarity(left: String, right: String): Double {
        val a = left.split(' ').filter(String::isNotBlank).toSet()
        val b = right.split(' ').filter(String::isNotBlank).toSet()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size
    }
}

object PackageAmountPolicy {
    private val packagePattern = Regex(
        "(?:^|\\s)(\\d+(?:[.,]\\d+)?)\\s*(mg|kg|g|ml|l|kom(?:ad(?:a)?)?|rola|role|vrećica|vrećice|kapsula|kapsule)(?:\\s|$)",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): ParsedPackage? {
        val match = packagePattern.find(text) ?: return null
        val value = match.groupValues[1].replace(',', '.').toBigDecimalOrNull() ?: return null
        if (value <= BigDecimal.ZERO) return null
        val unit = when (match.groupValues[2].lowercase(Locale.forLanguageTag("hr"))) {
            "mg" -> PackageUnit.MG
            "g" -> PackageUnit.G
            "kg" -> PackageUnit.KG
            "ml" -> PackageUnit.ML
            "l" -> PackageUnit.L
            "rola", "role" -> PackageUnit.ROLL
            "vrećica", "vrećice" -> PackageUnit.BAG
            "kapsula", "kapsule" -> PackageUnit.CAPSULE
            else -> PackageUnit.PIECE
        }
        val base = value.multiply(BigDecimal.valueOf(unit.multiplierToBase))
        val exact = runCatching { base.setScale(0, RoundingMode.UNNECESSARY).longValueExact() }.getOrNull() ?: return null
        return ParsedPackage(exact, unit, match.value.trim())
    }

    fun baseAmount(value: String, unit: PackageUnit): Long {
        require(unit != PackageUnit.UNKNOWN) { "Odaberite mjernu jedinicu." }
        val decimal = value.trim().replace(',', '.').toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Količina pakiranja nije ispravna.")
        require(decimal > BigDecimal.ZERO) { "Količina pakiranja mora biti veća od nule." }
        return decimal.multiply(BigDecimal.valueOf(unit.multiplierToBase))
            .setScale(0, RoundingMode.UNNECESSARY)
            .longValueExact()
    }

    fun formatBase(amount: Long, kind: MeasurementKind, countUnit: String? = null): String = when (kind) {
        MeasurementKind.MASS -> if (amount >= 1_000_000 && amount % 1_000L == 0L) {
            formatDecimal(BigDecimal.valueOf(amount, 6)) + " kg"
        } else if (amount >= 1_000) {
            formatDecimal(BigDecimal.valueOf(amount, 3)) + " g"
        } else "$amount mg"
        MeasurementKind.VOLUME -> if (amount >= 1_000) formatDecimal(BigDecimal.valueOf(amount, 3)) + " l" else "$amount ml"
        MeasurementKind.COUNT -> "$amount ${countUnit ?: "kom"}"
        MeasurementKind.UNKNOWN -> "nepoznato"
    }

    private fun formatDecimal(value: BigDecimal): String = value.stripTrailingZeros().toPlainString().replace('.', ',')
}

data class GenericQuantitySummary(
    val packages: Int,
    val knownAmountBase: Long,
    val kind: MeasurementKind,
    val hasUnknownPackage: Boolean,
    val countUnit: String? = null,
) {
    fun display(): String {
        val packageLabel = "$packages ${if (packages == 1) "pakiranje" else "pakiranja"}"
        if (kind == MeasurementKind.UNKNOWN || knownAmountBase <= 0) return packageLabel
        val amount = PackageAmountPolicy.formatBase(knownAmountBase, kind, countUnit)
        return "$packageLabel (${if (hasUnknownPackage) "najmanje " else ""}$amount)"
    }
}

object GenericStockPolicy {
    fun summarize(item: ProductWithStock): GenericQuantitySummary {
        val variants = item.variants.associateBy(ProductVariant::id)
        var known = 0L
        var unknown = false
        val kinds = mutableSetOf<MeasurementKind>()
        val countUnits = mutableSetOf<PackageUnit>()
        item.stocks.filter { it.quantity > 0 }.forEach { stock ->
            val variant = variants[stock.variantId]
            val amount = variant?.packageAmountBase
            val kind = variant?.packageUnit?.kind ?: MeasurementKind.UNKNOWN
            if (amount == null || kind == MeasurementKind.UNKNOWN) {
                unknown = true
            } else {
                val row = runCatching { Math.multiplyExact(amount, stock.quantity.toLong()) }.getOrDefault(Long.MAX_VALUE)
                known = runCatching { Math.addExact(known, row) }.getOrDefault(Long.MAX_VALUE)
                kinds += kind
                if (kind == MeasurementKind.COUNT) countUnits += requireNotNull(variant).packageUnit
            }
        }
        val countLabel = countUnits.singleOrNull()?.let {
            when (it) {
                PackageUnit.ROLL -> "rola"
                PackageUnit.BAG -> "vrećica"
                PackageUnit.CAPSULE -> "kapsula"
                else -> "kom"
            }
        }
        return GenericQuantitySummary(
            item.totalQuantity,
            known,
            kinds.singleOrNull() ?: MeasurementKind.UNKNOWN,
            unknown,
            countLabel,
        )
    }

    fun requiredPackages(item: ProductWithStock): Int {
        if (!item.product.autoShopping) return 0
        val preferred = preferredVariantForShopping(item)
        val missingBase = (item.product.minimumAmountBase - item.minimumCurrentBase).coerceAtLeast(0)
        val genericRequired = when {
            missingBase == 0L -> 0
            item.product.minimumMode == MinimumMode.PACKAGES -> missingBase.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            else -> {
                val packageBase = preferred?.packageAmountBase?.takeIf { it > 0 }
                if (packageBase == null) 1 else ((missingBase + packageBase - 1) / packageBase)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
        }
        val totals = item.stocks.groupBy(Stock::variantId).mapValues { (_, rows) -> rows.sumOf(Stock::quantity) }
        val variantRequired = item.variants.sumOf { variant ->
            ((variant.minimumPackages ?: 0) - (totals[variant.id] ?: 0)).coerceAtLeast(0)
        }
        return maxOf(genericRequired, variantRequired)
    }

    fun preferredVariantForShopping(item: ProductWithStock): ProductVariant? {
        val totals = item.stocks.groupBy(Stock::variantId).mapValues { (_, rows) -> rows.sumOf(Stock::quantity) }
        val shortage = item.variants
            .map { variant -> variant to ((variant.minimumPackages ?: 0) - (totals[variant.id] ?: 0)).coerceAtLeast(0) }
            .filter { it.second > 0 }
            .maxWithOrNull(compareBy<Pair<ProductVariant, Int>> { it.second }.thenBy { it.first.purchaseCount }.thenBy { it.first.id })
            ?.first
        return shortage
            ?: item.product.preferredVariantId?.let { id -> item.variants.firstOrNull { it.id == id } }
            ?: item.variants.maxWithOrNull(compareBy<ProductVariant> { it.purchaseCount }.thenBy { it.id })
    }
}
