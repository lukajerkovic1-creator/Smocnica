package hr.smocnica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import hr.smocnica.core.model.*
import hr.smocnica.ui.theme.SmocnicaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MinimalInventoryTest {
    @get:Rule val compose = createComposeRule()
    private val shelves = listOf(Shelf("s1", "p", "Polica 1", 0, createdAt = 1, updatedAt = 1), Shelf("s2", "p", "Polica 2", 1, createdAt = 1, updatedAt = 1))
    private val products = listOf("Glatko brašno", "Fusilli", "Mlijeko", "Riža", "Suncokretovo ulje", "Šećer").mapIndexed { i, name ->
        ProductWithStock(Product("p$i", "p", name, createdAt = i.toLong(), updatedAt = i.toLong()),
            listOf(Stock("p", "p$i", if (i == 2 || i == 4) "s2" else "s1", listOf(2, 1, 3, 2, 1, 1)[i], updatedAt = 1, variantId = "v$i")),
            listOf(ProductVariant("v$i", "p", "p$i", name, packageLabel = listOf("1 kg", "500 g", "1 l", "1 kg", "1 l", "1 kg")[i], createdAt = 1, updatedAt = 1)))
    }

    @Test fun homeDrawerAndAddSheetMatchReferenceStructure() {
        var destination = "home"
        var manual = 0
        var photo = 0
        compose.setContent {
            SmocnicaTheme(darkTheme = false) {
                var showAdd by remember { mutableStateOf(false) }
                PantryNavigationShell("home", { destination = it }) { outer ->
                    Scaffold(Modifier.padding(outer), containerColor = MaterialTheme.colorScheme.surface, contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        floatingActionButton = { InventoryAddButton { showAdd = true } }) { inner ->
                        LazyColumn(contentPadding = PaddingValues(top = inner.calculateTopPadding(), bottom = inner.calculateBottomPadding() + 96.dp)) {
                            item { InventorySearchField("") {} }
                            item { Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { InventoryListControls(shelves, null, InventoryOrder.NAME, {}, {}, {}) } }
                            items(products) { item -> ProductCard(item, shelves, null, false, false, {}, {}, {}, {}, {}, {}, {}) }
                        }
                    }
                }
                if (showAdd) AddArticleChoice({ showAdd = false }, { manual++; showAdd = false }, { photo++; showAdd = false })
            }
        }
        compose.onNodeWithText("Pretraži artikle").assertIsDisplayed()
        val search = compose.onNodeWithText("Pretraži artikle").fetchSemanticsNode().boundsInRoot
        val menuButton = compose.onNodeWithContentDescription("Otvori izbornik").fetchSemanticsNode().boundsInRoot
        assertTrue("Pretraga mora ostati neposredno ispod zaglavlja.", search.top - menuButton.bottom < 40 * compose.density.density)
        compose.onNodeWithText("Sve police").assertIsDisplayed()
        compose.onNodeWithText("Početno").assertDoesNotExist()
        compose.onNodeWithText("Skeniraj").assertDoesNotExist()
        val plus = compose.onNodeWithContentDescription("Dodaj artikl").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(plus.center.x > screen.center.x && plus.center.y > screen.center.y)
        capture("minimal-home")
        compose.onNodeWithContentDescription("Dodaj artikl").performClick()
        compose.onNodeWithText("Dodaj ručno").assertIsDisplayed()
        compose.onNodeWithText("Fotografiraj").assertIsDisplayed()
        capture("minimal-add")
        compose.onNodeWithText("Dodaj ručno").performClick()
        compose.runOnIdle { assertEquals(1, manual) }
        compose.onNodeWithContentDescription("Dodaj artikl").performClick()
        compose.onNodeWithText("Fotografiraj").performClick()
        compose.runOnIdle { assertEquals(1, photo) }
        compose.onNodeWithContentDescription("Otvori izbornik").performClick()
        compose.onNodeWithText("Postavke").assertIsDisplayed()
        compose.onNodeWithText("Popis za kupnju").assertIsDisplayed()
        capture("minimal-drawer")
        compose.onNodeWithText("Police").performClick()
        compose.runOnIdle { assertEquals("shelves", destination) }
        compose.onNodeWithText("Postavke").assertIsNotDisplayed()
    }

    @Test fun filtersAndSortingAreSelectableDirectlyAboveList() {
        var chosenShelf: String? = null
        var chosenOrder = InventoryOrder.NAME
        compose.setContent { SmocnicaTheme { InventoryListControls(shelves, null, InventoryOrder.NAME, { chosenShelf = it }, { chosenOrder = it }, {}) } }
        compose.onNodeWithText("Sve police").performClick()
        compose.onNodeWithText("Polica 2").performClick()
        compose.runOnIdle { assertEquals("s2", chosenShelf) }
        compose.onNodeWithText("Abecedno").performClick()
        compose.onNodeWithText("Najnovije dodano").performClick()
        compose.runOnIdle { assertEquals(InventoryOrder.NEWEST, chosenOrder) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
