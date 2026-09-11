package hr.smocnica.ui

import android.net.Uri
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.NavOptions
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import hr.smocnica.MainViewModel
import hr.smocnica.BuildConfig
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.PaddingValues
import hr.smocnica.ui.theme.ThemeMode
import kotlinx.coroutines.launch

internal fun sectionNavigationOptions(startDestinationId: Int): NavOptions =
    NavOptions.Builder()
        .setPopUpTo(startDestinationId, inclusive = false, saveState = false)
        .setLaunchSingleTop(true)
        .setRestoreState(false)
        .build()

@Composable
fun MainNavigation(
    viewModel: MainViewModel,
    requestedRoute: String? = null,
    onRouteConsumed: () -> Unit = {},
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
) {
    val availableUpdate by viewModel.latestUpdate.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.checkUpdate(BuildConfig.VERSION_CODE.toLong()) }
    if (availableUpdate?.isMandatory(BuildConfig.VERSION_CODE.toLong()) == true) {
        UpdateScreen(viewModel, PaddingValues(), onBack = null)
        return
    }
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun completeScanner(result: ScannerCompletion, returnToSource: Boolean) {
        if (returnToSource) navController.popBackStack()
        scope.launch {
            val response = snackbar.showSnackbar(result.message, actionLabel = "Poništi")
            if (response == SnackbarResult.ActionPerformed) {
                when (result) {
                    is ScannerCompletion.StockAdjusted -> viewModel.adjustStock(result.productId, result.shelfId, -result.delta)
                    is ScannerCompletion.StockMoved -> viewModel.moveStock(result.productId, result.toShelfId, result.fromShelfId, result.quantity)
                    is ScannerCompletion.ProductCreated -> viewModel.deleteProduct(result.product)
                    is ScannerCompletion.ProductRestored -> viewModel.undoRestoreProductAndAddStock(result.product, result.variantId, result.shelfId, result.quantity)
                }
            }
        }
    }
    LaunchedEffect(requestedRoute) {
        requestedRoute?.takeIf { route -> route in setOf("shopping", "stocks") }?.let { route ->
            navController.navigate(route) { launchSingleTop = true }
            onRouteConsumed()
        }
    }
    val current by navController.currentBackStackEntryAsState()
    var homeVisit by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    PantryNavigationShell(
        currentRoute = current?.destination?.route,
        navigate = { route ->
            if (route == "home") homeVisit++
            navController.navigate(route, sectionNavigationOptions(navController.graph.findStartDestination().id))
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        NavHost(navController, startDestination = "home", modifier = Modifier) {
            composable("home") {
                androidx.compose.runtime.key(homeVisit) {
                StocksScreen(viewModel, padding,
                    scan = { shelfId -> navController.navigate(scannerRoute(ScannerContext("Svi artikli", shelfId = shelfId))) },
                    openProduct = { productId -> navController.navigate("product/" + Uri.encode(productId)) },
                )
                }
            }
            composable("scanner") { ScannerScreen(viewModel, padding, onCompleted = { completeScanner(it, false) }) }
            composable(
                route = "scanner/context?source={source}&shelfId={shelfId}&productId={productId}&shoppingItemId={shoppingItemId}&mode={mode}",
                arguments = listOf(
                    navArgument("source") { type = NavType.StringType; defaultValue = "Skeniranje" },
                    navArgument("shelfId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("productId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("shoppingItemId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("mode") { type = NavType.StringType; defaultValue = ScannerMode.DEFAULT.name },
                ),
            ) { entry ->
                val context = ScannerContext(
                    sourceLabel = entry.arguments?.getString("source").orEmpty().ifBlank { "Skeniranje" },
                    shelfId = entry.arguments?.getString("shelfId").orEmpty(),
                    productId = entry.arguments?.getString("productId").orEmpty(),
                    shoppingItemId = entry.arguments?.getString("shoppingItemId").orEmpty(),
                    mode = runCatching { ScannerMode.valueOf(entry.arguments?.getString("mode").orEmpty()) }.getOrDefault(ScannerMode.DEFAULT),
                )
                ScannerScreen(viewModel, padding, context, onCompleted = { completeScanner(it, true) })
            }
            composable("shopping") {
                ShoppingScreen(viewModel, padding) { item ->
                    navController.navigate(scannerRoute(ScannerContext("Popis za kupnju", shoppingItemId = item.id, productId = item.productId.orEmpty(), mode = ScannerMode.ADD)))
                }
            }
            composable("shelves") {
                ShelvesScreen(
                    viewModel = viewModel,
                    padding = padding,
                    openShelf = { shelfId -> navController.navigate(stocksRoute(shelfId)) { launchSingleTop = true } },
                    scan = { shelfId ->
                        val shelfName = viewModel.shelves.value.firstOrNull { it.id == shelfId }?.name
                        navController.navigate(scannerRoute(ScannerContext(shelfName?.let { "Polica: $it" } ?: "Police", shelfId = shelfId)))
                    },
                    addManual = { shelfId -> navController.navigate(stocksRoute(shelfId, action = "new")) },
                    moveExisting = { shelfId -> navController.navigate(stocksRoute(shelfId, action = "move")) },
                )
            }
            composable("menu") { MenuScreen(viewModel, padding, navController::navigate, themeMode, onThemeModeChange) }
            composable(
                route = "stocks?shelfId={shelfId}&action={action}&filter={filter}",
                arguments = listOf(
                    navArgument("shelfId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("action") { type = NavType.StringType; defaultValue = "" },
                    navArgument("filter") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val shelfId = entry.arguments?.getString("shelfId").orEmpty()
                StocksScreen(
                    viewModel,
                    padding,
                    initialShelfId = shelfId,
                    initialAction = entry.arguments?.getString("action").orEmpty(),
                    initialFilter = entry.arguments?.getString("filter").orEmpty(),
                    scan = { selectedShelfId ->
                        val source = viewModel.shelves.value.firstOrNull { it.id == selectedShelfId }?.name?.let { "Polica: $it" } ?: "Sve zalihe"
                        navController.navigate(scannerRoute(ScannerContext(source, shelfId = selectedShelfId)))
                    },
                    openProduct = { productId -> navController.navigate("product/${Uri.encode(productId)}?shelfId=${Uri.encode(shelfId)}") },
                )
            }
            composable(
                route = "product/{productId}?shelfId={shelfId}",
                arguments = listOf(
                    navArgument("productId") { type = NavType.StringType },
                    navArgument("shelfId") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val productId = entry.arguments?.getString("productId").orEmpty()
                val shelfId = entry.arguments?.getString("shelfId").orEmpty()
                ProductDetailScreen(
                    viewModel,
                    padding,
                    productId,
                    shelfId,
                    scan = { mode -> navController.navigate(scannerRoute(ScannerContext("Detalj artikla", shelfId, productId, mode = mode))) },
                    close = { navController.popBackStack() },
                )
            }
            composable("inventory") { InventoryScreen(viewModel, padding) }
            composable("history") { HistoryScreen(viewModel, padding) { navController.popBackStack() } }
            composable("categories") { CategoriesScreen(viewModel, padding) { navController.popBackStack() } }
            composable("members") { MembersScreen(viewModel, padding) { navController.popBackStack() } }
            composable("trash") { TrashScreen(viewModel, padding) { navController.popBackStack() } }
            composable("backup") { BackupScreen(viewModel, padding) { navController.popBackStack() } }
            composable("update") { UpdateScreen(viewModel, padding) { navController.popBackStack() } }
            composable("about") { AboutScreen(padding) { navController.popBackStack() } }
            composable("conflicts") { ConflictsScreen(viewModel, padding) { navController.popBackStack() } }
            composable("grouping-review") { GroupingReviewScreen(viewModel, padding) { navController.popBackStack() } }
        }
    }
}
