package hr.smocnica.ui

import hr.smocnica.core.model.AggregateType
import hr.smocnica.core.model.TrashItem

internal fun trashTypeLabel(item: TrashItem): String = when (item.type) {
    AggregateType.PRODUCT -> "Artikl"
    AggregateType.VARIANT -> "Pakiranje"
    AggregateType.SHELF -> "Polica"
    AggregateType.CATEGORY -> "Kategorija"
    else -> "Zapis"
}

internal fun trashDetails(item: TrashItem): List<String> = buildList {
    item.packages.forEach { add(inventoryPackageLabel(item.parentName.ifBlank { item.label }, it)) }
    if (item.parentName.isNotBlank()) add("Pripada artiklu: ${item.parentName}")
    if (item.type == AggregateType.PRODUCT && item.packages.isNotEmpty()) add("Artikl se vraća zajedno s prikazanim pakiranjima.")
}
