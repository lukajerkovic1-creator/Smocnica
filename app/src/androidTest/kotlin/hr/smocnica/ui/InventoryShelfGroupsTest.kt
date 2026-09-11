package hr.smocnica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import hr.smocnica.core.model.*
import hr.smocnica.ui.theme.SmocnicaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InventoryShelfGroupsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun lightBands() = checkBands(false)
    @Test fun darkBands() = checkBands(true)

    private fun checkBands(dark: Boolean) {
        val shelves = listOf("Polica 1 - špajza", "Polica 2").mapIndexed { i, name -> Shelf("s$i", "p", name, i, createdAt = 1, updatedAt = 1) }
        val products = listOf("Brašno", "Fusilli", "Mlijeko", "Riža").mapIndexed { i, name ->
            ProductWithStock(Product("p$i", "p", name, createdAt = i.toLong(), updatedAt = 1),
                listOf(Stock("p", "p$i", "s${i / 2}", 1, updatedAt = 1)))
        }
        compose.setContent {
            SmocnicaTheme(darkTheme = dark) {
                var order by remember { mutableStateOf(InventoryOrder.SHELF) }
                PantryNavigationShell("home", {}) { outer ->
                    Scaffold(Modifier.padding(outer), contentWindowInsets = WindowInsets(0, 0, 0, 0), floatingActionButton = { InventoryAddButton {} }) { inner ->
                        LazyColumn(contentPadding = inner) {
                            item { InventorySearchField("") {} }
                            item { Box(Modifier.padding(horizontal = 16.dp)) { InventoryListControls(shelves, null, order, {}, { order = it }, {}) } }
                            inventoryRows(products, shelves, order) { ProductCard(it, shelves, null, false, false, {}, {}, {}, {}, {}, {}, {}) }
                        }
                    }
                }
            }
        }
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        compose.onAllNodes(heading).assertCountEquals(2)
        val first = compose.onNode(heading and hasText("Polica 1 - špajza")).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val second = compose.onNode(heading and hasText("Polica 2")).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val fusilli = compose.onNodeWithText("Fusilli").fetchSemanticsNode().boundsInRoot
        val milk = compose.onNodeWithText("Mlijeko").fetchSemanticsNode().boundsInRoot
        assertTrue("Zaglavlja moraju odvajati skupine: $first, $fusilli, $second, $milk",
            first.bottom <= fusilli.top && fusilli.bottom <= second.top && second.bottom <= milk.top)
        assertTrue(first.width > compose.onRoot().fetchSemanticsNode().boundsInRoot.width * .9f)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.cacheDir, "shelf-bands-${if (dark) "dark" else "light"}.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        for (label in listOf("Abecedno", "Najnovije dodano")) {
            compose.onNodeWithText(if (label == "Abecedno") "Po policama" else "Abecedno").performClick()
            compose.onNodeWithText(label).performClick()
            compose.onAllNodes(heading).assertCountEquals(0)
            products.forEach { compose.onAllNodesWithText(it.product.name).assertCountEquals(1) }
        }
    }
}
