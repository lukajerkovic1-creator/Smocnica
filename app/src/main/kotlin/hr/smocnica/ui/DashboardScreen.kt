package hr.smocnica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hr.smocnica.MainViewModel
import hr.smocnica.core.model.Activity
import hr.smocnica.core.model.ActivityType
import hr.smocnica.ui.theme.Amber
import hr.smocnica.ui.theme.Purple
import hr.smocnica.ui.theme.Rose
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(viewModel: MainViewModel, padding: PaddingValues, navigate: (String) -> Unit) {
    val products by viewModel.allProducts.collectAsStateWithLifecycle()
    val deletedProducts by viewModel.deletedProducts.collectAsStateWithLifecycle()
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    val shopping by viewModel.shopping.collectAsStateWithLifecycle()
    val activities by viewModel.activities.collectAsStateWithLifecycle()
    val sync by viewModel.syncSummary.collectAsStateWithLifecycle()
    val below = products.count { it.isBelowMinimum }
    val productNames = (products + deletedProducts).associate { it.product.id to it.product.name }
    val shelfNames = shelves.associate { it.id to it.name }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 18.dp, bottom = padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(56.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Outlined.Inventory2, null, Modifier.padding(12.dp), tint = Purple)
                }
                Text("Smočnica", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                IconButton({ navigate("menu") }, Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)) {
                    Icon(Icons.Outlined.PersonOutline, "Profil i postavke")
                }
            }
        }
        item {
            OverviewCard(
                below = below,
                shopping = shopping.size,
                products = products.size,
                openBelowMinimum = { navigate(stocksRoute(filter = "belowMinimum")) },
                openShopping = { navigate("shopping") },
                openProducts = { navigate(stocksRoute()) },
            )
        }
        item { SyncBanner(sync.pending, sync.syncing, sync.conflicts, sync.failed) {
            if (sync.conflicts + sync.failed > 0) navigate("conflicts") else viewModel.synchronize()
        } }
        item {
            Button(
                onClick = { navigate("scanner") },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Purple),
            ) {
                Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(30.dp))
                Text("Skeniraj proizvod", Modifier.padding(start = 14.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DashboardTile("Police", "Organiziraj po lokacijama", Icons.Outlined.Inventory2, "shelves", Modifier.weight(1f), navigate)
                    DashboardTile("Sve zalihe", "Pregled svih artikala", Icons.Outlined.AssignmentTurnedIn, "stocks", Modifier.weight(1f), navigate)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DashboardTile("Popis za kupnju", "${shopping.size} stavki na popisu", Icons.Outlined.ShoppingCart, "shopping", Modifier.weight(1f), navigate)
                    DashboardTile("Inventura", "Provjeri i ažuriraj zalihe", Icons.Outlined.AssignmentTurnedIn, "inventory", Modifier.weight(1f), navigate)
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f))) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Zadnje aktivnosti", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Prikaži sve", Modifier.clickable { navigate("history") }, color = Purple, fontWeight = FontWeight.SemiBold)
                    }
                    activities.take(4).forEachIndexed { index, activity ->
                        ActivityRow(
                            activity,
                            productNames = productNames,
                            shelfNames = shelfNames,
                        )
                        if (index < minOf(3, activities.lastIndex)) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                    if (activities.isEmpty()) Text("Još nema zabilježenih aktivnosti.", Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(
    below: Int,
    shopping: Int,
    products: Int,
    openBelowMinimum: () -> Unit,
    openShopping: () -> Unit,
    openProducts: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .38f)),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pregled zaliha", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Brz pregled vaše smočnice", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                    listOf(44, 54, 38, 48).forEachIndexed { index, height ->
                        Box(
                            Modifier.size(width = 24.dp, height = height.dp)
                                .background(listOf(Color(0xFFD6B38B), Color(0xFFE9D7B8), Color(0xFFC88455), Color(0xFFB99AD8))[index], RoundedCornerShape(7.dp)),
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Metric(below, "Ispod minimuma", Icons.Outlined.ErrorOutline, Rose, Modifier.weight(1f), openBelowMinimum)
                Metric(shopping, "Na popisu za kupnju", Icons.Outlined.ShoppingCart, Amber, Modifier.weight(1f), openShopping)
                Metric(products, "Ukupno artikala", Icons.Outlined.Inventory2, Purple, Modifier.weight(1f), openProducts)
            }
        }
    }
}

@Composable
private fun Metric(value: Int, label: String, icon: ImageVector, color: Color, modifier: Modifier, open: () -> Unit) {
    Column(modifier.clickable(onClick = open).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = CircleShape, color = color.copy(alpha = .12f)) { Icon(icon, null, Modifier.padding(8.dp).size(22.dp), tint = color) }
        Text(value.toString(), style = MaterialTheme.typography.headlineMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
private fun DashboardTile(title: String, subtitle: String, icon: ImageVector, route: String, modifier: Modifier, navigate: (String) -> Unit) {
    Card(modifier.aspectRatio(1.42f).clickable { navigate(route) }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, Modifier.size(42.dp), tint = Purple)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, null, Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ActivityRow(activity: Activity, productNames: Map<String, String>, shelfNames: Map<String, String>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(Icons.Outlined.Inventory2, null, Modifier.padding(9.dp), tint = Purple)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(activityDisplayLabel(activity, productNames), fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(activityDescription(activity, shelfNames), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(SimpleDateFormat("HH:mm", Locale.forLanguageTag("hr-HR")).format(Date(activity.createdAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun activityDisplayLabel(activity: Activity, productNames: Map<String, String> = emptyMap()): String =
    activity.productId?.let(productNames::get) ?: activity.displayLabel

internal fun activityDescription(activity: Activity, shelfNames: Map<String, String> = emptyMap()): String {
    val quantity = kotlin.math.abs(activity.quantityDelta ?: 0)
    val oldShelfId = activity.fromShelfId ?: activity.shelfId
    val newShelfId = activity.toShelfId ?: activity.shelfId
    val oldShelf = oldShelfId?.let(shelfNames::get) ?: activity.oldValue.asShelfName()
    val newShelf = newShelfId?.let(shelfNames::get) ?: activity.newValue.asShelfName()
    val action = when (activity.type) {
        ActivityType.STOCK_ADDED -> "Dodano $quantity kom${newShelf.orEmpty().withShelfPrefix()}"
        ActivityType.STOCK_REMOVED -> "Izvađeno $quantity kom${oldShelf.orEmpty().ifBlank { newShelf.orEmpty() }.withShelfPrefix(from = true)}"
        ActivityType.STOCK_MOVED -> "Premješteno $quantity kom: ${oldShelf ?: "izvorna polica"} → ${newShelf ?: "odredišna polica"}"
        ActivityType.PRODUCT_CREATED -> "Dodan artikl"
        ActivityType.PRODUCT_UPDATED -> "Uređen artikl"
        ActivityType.SHELF_CREATED -> "Dodana polica"
        ActivityType.SHELF_RENAMED -> if (oldShelf != null && newShelf != null) "Preimenovana polica: $oldShelf → $newShelf" else "Preimenovana polica"
        ActivityType.SHELF_REORDERED -> "Promijenjen redoslijed polica"
        ActivityType.SHELF_DELETED -> "Obrisana polica"
        ActivityType.CATEGORY_CREATED -> "Dodana kategorija"
        ActivityType.CATEGORY_UPDATED -> "Uređena kategorija"
        ActivityType.CATEGORY_REORDERED -> "Promijenjen redoslijed kategorija"
        ActivityType.CATEGORY_DELETED -> "Obrisana kategorija"
        ActivityType.INVENTORY_APPLIED -> "Primijenjena inventura"
        ActivityType.ITEM_DELETED -> "Premješteno u koš"
        ActivityType.ITEM_RESTORED -> "Vraćeno iz koša"
        ActivityType.SHOPPING_UPDATED -> "Ažuriran popis za kupnju"
        ActivityType.IMPORT_APPLIED -> "Uvezena sigurnosna kopija"
        ActivityType.PANTRY_CREATED -> "Stvorena smočnica"
        ActivityType.MEMBER_JOINED -> "Pridružen član"
        ActivityType.MEMBER_REMOVED -> "Uklonjen član"
        ActivityType.OWNERSHIP_TRANSFERRED -> "Preneseno vlasništvo"
        ActivityType.UNKNOWN -> "Promjena"
    }
    return "$action · ${activity.deviceName}"
}

private fun String?.asShelfName(): String? = this?.trim()?.takeIf { value ->
    value.isNotBlank() && value.toIntOrNull() == null
}

private fun String.withShelfPrefix(from: Boolean = false): String = when {
    isBlank() -> ""
    from -> " s police $this"
    else -> " na policu $this"
}

@Composable
private fun SyncBanner(pending: Int, syncing: Int, conflicts: Int, failed: Int, retry: () -> Unit) {
    val hasProblem = conflicts + failed > 0
    val hasWork = pending + syncing > 0
    Surface(
        color = when {
            hasProblem -> MaterialTheme.colorScheme.errorContainer
            hasWork -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.primaryContainer
        },
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(enabled = hasProblem || hasWork) { retry() },
    ) {
        Text(
            when {
                conflicts > 0 -> "$conflicts konflikata traži odluku"
                failed > 0 -> "$failed promjena treba pregled"
                syncing > 0 -> "Sinkronizacija u tijeku"
                pending > 0 -> "$pending promjena čeka sinkronizaciju"
                else -> "Sinkronizirano"
            },
            Modifier.padding(14.dp),
            color = when {
                hasProblem -> MaterialTheme.colorScheme.onErrorContainer
                hasWork -> MaterialTheme.colorScheme.onTertiaryContainer
                else -> MaterialTheme.colorScheme.onPrimaryContainer
            },
        )
    }
}
