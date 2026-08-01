package hr.smocnica.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hr.smocnica.MainViewModel
import hr.smocnica.core.domain.GenericNamePolicy
import hr.smocnica.core.model.GroupingSuggestion
import hr.smocnica.core.model.ProductVariant

private data class ReviewCandidate(
    val variant: ProductVariant,
    val sourceProductId: String,
    val suggestion: GroupingSuggestion,
)

@Composable
fun GroupingReviewScreen(viewModel: MainViewModel, padding: PaddingValues, onBack: () -> Unit) {
    val products by viewModel.allProducts.collectAsStateWithLifecycle()
    val rules by viewModel.synonymRules.collectAsStateWithLifecycle()
    val pantry by viewModel.selectedPantry.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val isOwner = pantry?.ownerUid == session?.uid
    val candidates = remember(products, rules) {
        products.filterNot { it.product.doNotGroup }.flatMap { source ->
            source.variants.filter { it.deletedAt == null }.mapNotNull { variant ->
                val suggestion = GenericNamePolicy.suggest(
                    variant.displayName,
                    products.map { it.product.id to it.product.name },
                    rules,
                )
                val target = suggestion.existingProductId
                    ?: products.firstOrNull { GenericNamePolicy.normalize(it.product.name) == GenericNamePolicy.normalize(suggestion.suggestedGenericName) }?.product?.id
                if (target == null || target == source.product.id) null
                else ReviewCandidate(variant, source.product.id, suggestion.copy(existingProductId = target))
            }
        }
    }
    val selected = remember(candidates) { mutableStateMapOf<String, Boolean>().also { map -> candidates.forEach { map[it.variant.id] = false } } }
    val targets = remember(candidates) { mutableStateMapOf<String, String>().also { map -> candidates.forEach { map[it.variant.id] = it.suggestion.existingProductId.orEmpty() } } }
    val rememberRules = remember(candidates) { mutableStateMapOf<String, Boolean>().also { map -> candidates.forEach { map[it.variant.id] = true } } }

    SecondaryScreenScaffold("Pregled grupiranja", padding, onBack) { inner ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = inner.calculateTopPadding() + 12.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Nijedna varijanta neće se grupirati bez vaše potvrde. Istodobne promjene na drugom uređaju završavaju kao konflikt sinkronizacije.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!isOwner) item { Text("Samo vlasnik potvrđuje trajna pravila i oznaku Ne grupiraj.", color = MaterialTheme.colorScheme.error) }
            items(candidates, key = { it.variant.id }) { candidate ->
                val source = products.first { it.product.id == candidate.sourceProductId }
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(selected[candidate.variant.id] == true, { selected[candidate.variant.id] = it })
                            Column(Modifier.weight(1f)) {
                                Text(candidate.variant.displayName, fontWeight = FontWeight.Bold)
                                Text("Sada: ${source.product.name}")
                                Text("Prijedlog: ${candidate.suggestion.suggestedGenericName} (${candidate.suggestion.confidencePercent} %)")
                            }
                        }
                        PairPicker(
                            "Premjesti u",
                            products.filter { it.product.id != source.product.id && !it.product.doNotGroup }.map { it.product.id to it.product.name },
                            targets[candidate.variant.id].orEmpty(),
                        ) { targets[candidate.variant.id] = it }
                        if (isOwner) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Zapamti kao zajedničko pravilo", Modifier.weight(1f))
                            Switch(rememberRules[candidate.variant.id] == true, { rememberRules[candidate.variant.id] = it })
                        }
                        if (isOwner) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Ne predlaži grupiranje izvornog artikla", Modifier.weight(1f))
                            Switch(source.product.doNotGroup, { viewModel.setDoNotGroup(source.product.id, it) })
                        }
                    }
                }
            }
            if (candidates.isEmpty()) item { EmptyState("Nema nepotvrđenih prijedloga grupiranja.") }
            if (candidates.isNotEmpty()) item {
                Button(
                    onClick = {
                        candidates.filter { selected[it.variant.id] == true }.forEach { candidate ->
                            val targetId = targets[candidate.variant.id].orEmpty()
                            val target = products.firstOrNull { it.product.id == targetId } ?: return@forEach
                            viewModel.moveVariant(candidate.variant.id, targetId)
                            if (isOwner && rememberRules[candidate.variant.id] == true) {
                                viewModel.rememberGroupingRule(candidate.variant.displayName, target.product)
                            }
                        }
                    },
                    enabled = candidates.any { selected[it.variant.id] == true && targets[it.variant.id].orEmpty().isNotBlank() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Potvrdi odabrana grupiranja") }
            }
        }
    }
}
