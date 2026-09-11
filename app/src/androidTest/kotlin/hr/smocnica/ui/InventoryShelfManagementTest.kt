package hr.smocnica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import org.junit.Rule
import org.junit.Test

class InventoryShelfManagementTest {
    @get:Rule val compose = createComposeRule()
    private val first = Shelf("s1", "p", "Polica 1 - špajza", 0, createdAt = 1, updatedAt = 1)
    private val empty = Shelf("s2", "p", "Polica 2", 1, createdAt = 1, updatedAt = 1)
    private val products = listOf(ProductWithStock(Product("a", "p", "Glatko brašno", createdAt = 1, updatedAt = 1),
        listOf(Stock("p", "a", "s1", 2, updatedAt = 1))))

    @Test fun darkManagementFromInventory() = verify(true)
    @Test fun lightManagementFromInventory() = verify(false)

    private fun verify(dark: Boolean) {
        var shelves by mutableStateOf(listOf(first, empty))
        var selected by mutableStateOf<String?>(null)
        var new by mutableStateOf(false)
        var rename by mutableStateOf<Shelf?>(null)
        var delete by mutableStateOf<Shelf?>(null)
        var scanShelf = "unset"
        compose.setContent { SmocnicaTheme(darkTheme = dark) {
            val actions = ShelfManagementActions({ new = true }, { rename = it }, { delete = it })
            PantryNavigationShell("home", {}) { outer ->
                Scaffold(Modifier.padding(outer), contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    floatingActionButton = { InventoryAddButton { scanShelf = selected.orEmpty() } }) { inner ->
                    LazyColumn(contentPadding = inner) {
                        item { InventorySearchField("") {} }
                        item { Box(Modifier.padding(horizontal = 16.dp)) {
                            InventoryListControls(shelves, selected, InventoryOrder.SHELF, { selected = it }, {}, {}, products, actions)
                        } }
                        inventoryRows(products, shelves, InventoryOrder.SHELF, shelfActions = actions) {
                            ProductCard(it, shelves, null, false, false, {}, {}, {}, {}, {}, {}, {})
                        }
                    }
                }
            }
            if (new) NameDialog("Nova polica", "", { new = false }) { name -> shelves = shelves + Shelf("s3", "p", name, 2, createdAt = 1, updatedAt = 1); new = false }
            rename?.let { shelf -> NameDialog("Preimenuj policu", shelf.name, { rename = null }) { name -> shelves = shelves.map { if (it.id == shelf.id) it.copy(name = name) else it }; rename = null } }
            delete?.let { shelf -> InventoryShelfDeleteDialog(shelf, products, shelves, { delete = null }, { shelves = shelves.filterNot { it.id == shelf.id }; delete = null }, { error("Prazna polica se ne premješta.") }) }
        } }
        capture("home", dark)
        compose.onNodeWithText("Sve police").performClick()
        compose.onNodeWithText("Prazna polica").assertIsDisplayed()
        compose.onNodeWithText("1 artikl").assertIsDisplayed()
        capture("selector", dark)
        compose.onNodeWithText("Dodaj policu").performClick()
        compose.onNodeWithText("Naziv").performTextInput("Polica 3")
        capture("new", dark)
        compose.onNodeWithText("Spremi").performClick()
        compose.runOnIdle { assertEquals(3, shelves.size) }
        compose.onNodeWithText("Sve police").performClick()
        compose.onNodeWithContentDescription("Radnje police Polica 2").performClick()
        capture("actions", dark)
        compose.onNodeWithText("Preimenuj").performClick()
        compose.onNodeWithText("Naziv").performTextClearance()
        compose.onNodeWithText("Naziv").performTextInput("Rezervna polica")
        compose.onNodeWithText("Spremi").performClick()
        compose.onNodeWithText("Sve police").performClick()
        compose.onNodeWithContentDescription("Radnje police Rezervna polica").performClick()
        compose.onNodeWithText("Obriši policu").performClick()
        compose.onNodeWithText("Obrisati policu?").assertIsDisplayed()
        compose.onNodeWithText("Obriši policu").performClick()
        compose.runOnIdle { assertEquals(listOf("s1", "s3"), shelves.map { it.id }) }
        compose.onNodeWithText("Sve police").performClick()
        compose.onNodeWithText("Polica 3").performClick()
        compose.onNodeWithContentDescription("Dodaj artikl").performClick()
        compose.runOnIdle { assertEquals("s3", scanShelf) }
        compose.onNodeWithContentDescription("Radnje police Polica 1 - špajza").performClick()
        compose.onNodeWithText("Preimenuj").performClick()
        compose.onNodeWithText("Preimenuj policu").assertIsDisplayed()
    }

    @Test fun occupiedShelfOffersMoveAndNeverDeletesStock() {
        var moved = ""
        compose.setContent { SmocnicaTheme {
            InventoryShelfDeleteDialog(first, products, listOf(first, empty), {}, { error("Zauzeta polica ne smije se obrisati.") }, { moved = it })
        } }
        compose.onNodeWithText("Polica nije prazna").assertIsDisplayed()
        compose.onNodeWithText("Obriši policu").assertDoesNotExist()
        compose.onNodeWithText("Premjesti artikle").performClick()
        compose.runOnIdle { assertEquals("s2", moved) }
    }

    @Test fun occupiedLastShelfRequiresAnotherShelf() {
        compose.setContent { SmocnicaTheme {
            InventoryShelfDeleteDialog(first, products, listOf(first), {}, { error("Ne smije obrisati.") }, { error("Nema odredišta.") })
        } }
        compose.onNodeWithText("Najprije dodajte drugu policu.").assertIsDisplayed()
        compose.onNodeWithText("Premjesti artikle").assertIsNotEnabled()
    }

    private fun capture(name: String, dark: Boolean) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // UIAutomator captures the system compositor, which may trail Compose's first frame.
        android.os.SystemClock.sleep(200)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.cacheDir, "shelf-management-$name-${if (dark) "dark" else "light"}.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
