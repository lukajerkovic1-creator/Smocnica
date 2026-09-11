package hr.smocnica.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class DrawerDestination(val route: String, val label: String, val icon: ImageVector)

private val drawerDestinations = listOf(
    DrawerDestination("home", "Svi artikli", Icons.Outlined.Inventory2),
    DrawerDestination("shopping", "Popis za kupnju", Icons.Outlined.ShoppingCart),
    DrawerDestination("scanner", "Skeniraj barkod", Icons.Outlined.QrCodeScanner),
    DrawerDestination("inventory", "Inventura", Icons.Outlined.FactCheck),
    DrawerDestination("history", "Povijest", Icons.Outlined.History),
    DrawerDestination("trash", "Koš", Icons.Outlined.DeleteOutline),
    DrawerDestination("menu", "Postavke", Icons.Outlined.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PantryNavigationShell(
    currentRoute: String?,
    navigate: (String) -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width((androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * .82f).dp.coerceAtMost(360.dp)),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 16.dp)) {
                    Text("Smočnica", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    drawerDestinations.forEach { destination ->
                        if (destination.route == "inventory") HorizontalDivider(Modifier.padding(vertical = 16.dp, horizontal = 16.dp))
                        NavigationDrawerItem(
                            label = { Text(destination.label) },
                            icon = { Icon(destination.icon, null) },
                            selected = currentRoute == destination.route || (destination.route == "home" && currentRoute?.startsWith("stocks") == true),
                            onClick = { scope.launch { drawer.close(); navigate(destination.route) } },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text("Smočnica", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                    navigationIcon = {
                        IconButton({ scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, "Otvori izbornik") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            },
            snackbarHost = snackbarHost,
            content = content,
        )
    }
}
