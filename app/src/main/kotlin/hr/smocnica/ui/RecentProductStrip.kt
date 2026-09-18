package hr.smocnica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hr.smocnica.MainViewModel
import hr.smocnica.core.model.*
import kotlinx.coroutines.launch

internal data class RecentEntry(val product: Product, val variant: ProductVariant, val usedAt: Long)

internal fun recentEntries(products: List<ProductWithStock>): List<RecentEntry> = products
    .filter { it.product.deletedAt == null }
    .flatMap { item -> item.variants.filter { it.deletedAt == null }.map { variant ->
        RecentEntry(item.product, variant, maxOf(variant.createdAt,
            item.stocks.filter { it.variantId == variant.id }.maxOfOrNull { it.updatedAt } ?: 0))
    } }
    .sortedWith(compareByDescending<RecentEntry> { it.usedAt }.thenBy { it.variant.id }).take(8)

@Composable
internal fun RecentProductStrip(products: List<ProductWithStock>, shelves: List<Shelf>, selectedShelf: String,
    viewModel: MainViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("product-entry", android.content.Context.MODE_PRIVATE) }
    val pantryId = shelves.firstOrNull()?.pantryId.orEmpty()
    val entries = remember(products) { recentEntries(products) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (entries.isEmpty()) return
    Column(Modifier.padding(vertical = 8.dp)) {
        Text("Dodaj ponovno · 1 pakiranje", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.variant.id }) { entry ->
                OutlinedButton(onClick = {
                    val shelf = preferredEntryShelf(selectedShelf, preferences.getString(pantryId, "").orEmpty(), shelves.map { it.id })
                    busy = true
                    viewModel.adjustVariantStock(entry.variant.id, shelf, 1, onAdjusted = {
                        busy = false
                        preferences.edit().putString(pantryId, shelf).apply()
                        scope.launch {
                            if (snackbar.showSnackbar("Dodano 1 pakiranje: ${entry.product.name}", "Poništi") == SnackbarResult.ActionPerformed)
                                viewModel.adjustVariantStock(entry.variant.id, shelf, -1)
                        }
                    }, onFailure = { busy = false })
                }, enabled = !busy && shelves.isNotEmpty(), modifier = Modifier.widthIn(max = 190.dp)) {
                    ProductPhoto(entry.variant.photoUri, entry.variant.updatedAt, null, Modifier.size(38.dp), ContentScale.Fit)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(entry.product.name, maxLines = 2)
                        Text(entry.variant.packageLabel.ifBlank {
                            entry.variant.packageAmountBase?.let { amount ->
                                "${java.math.BigDecimal.valueOf(amount).divide(java.math.BigDecimal.valueOf(entry.variant.packageUnit.multiplierToBase)).stripTrailingZeros().toPlainString()} ${packageUnitLabel(entry.variant.packageUnit)}"
                            } ?: "Veličina nije poznata"
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
