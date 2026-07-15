package hr.smocnica.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import hr.smocnica.MainViewModel
import hr.smocnica.BuildConfig
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import hr.smocnica.ui.theme.ThemeMode

private data class Destination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val bottomDestinations = listOf(
    Destination("home", "Početno", Icons.Outlined.Home),
    Destination("scanner", "Skeniraj", Icons.Outlined.QrCodeScanner),
    Destination("shopping", "Kupnja", Icons.Outlined.ShoppingCart),
    Destination("shelves", "Police", Icons.Outlined.Inventory2),
    Destination("menu", "Izbornik", Icons.Outlined.Menu),
)

@Composable
fun MainNavigation(
    viewModel: MainViewModel,
    requestedRoute: String? = null,
    onRouteConsumed: () -> Unit = {},
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
) {
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val availableUpdate by viewModel.latestUpdate.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.checkUpdate(BuildConfig.VERSION_CODE.toLong()) }
    if (availableUpdate?.isMandatory(BuildConfig.VERSION_CODE.toLong()) == true) {
        UpdateScreen(viewModel, PaddingValues())
        return
    }
    val navController = rememberNavController()
    LaunchedEffect(requestedRoute) {
        requestedRoute?.takeIf { route -> route in setOf("shopping", "stocks") }?.let { route ->
            navController.navigate(route) { launchSingleTop = true }
            onRouteConsumed()
        }
    }
    val current by navController.currentBackStackEntryAsState()
    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = current?.destination?.route == destination.route,
                        onClick = {
                            if (destination.route == "home") {
                                navController.popBackStack("home", inclusive = false)
                            } else {
                                navController.navigate(destination.route) {
                                    popUpTo("home") { saveState = false }
                                    launchSingleTop = true
                                    restoreState = false
                                }
                            }
                        },
                        icon = { Icon(destination.icon, destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = "home", modifier = Modifier) {
            composable("home") { DashboardScreen(viewModel, padding, navController::navigate) }
            composable("scanner") { ScannerScreen(viewModel, padding) }
            composable("shopping") { ShoppingScreen(viewModel, padding) }
            composable("shelves") {
                ShelvesScreen(viewModel, padding) { shelfId ->
                    navController.navigate("stocks?shelfId=${Uri.encode(shelfId)}") { launchSingleTop = true }
                }
            }
            composable("menu") { MenuScreen(viewModel, padding, navController::navigate, themeMode, onThemeModeChange) }
            composable(
                route = "stocks?shelfId={shelfId}",
                arguments = listOf(navArgument("shelfId") { type = NavType.StringType; defaultValue = "" }),
            ) { entry -> StocksScreen(viewModel, padding, entry.arguments?.getString("shelfId").orEmpty()) }
            composable("inventory") { InventoryScreen(viewModel, padding) }
            composable("history") { HistoryScreen(viewModel, padding) }
            composable("categories") { CategoriesScreen(viewModel, padding) }
            composable("members") { MembersScreen(viewModel, padding) }
            composable("trash") { TrashScreen(viewModel, padding) }
            composable("backup") { BackupScreen(viewModel, padding) }
            composable("update") { UpdateScreen(viewModel, padding) }
            composable("about") { AboutScreen(padding) }
            composable("conflicts") { ConflictsScreen(viewModel, padding) }
        }
    }
}
