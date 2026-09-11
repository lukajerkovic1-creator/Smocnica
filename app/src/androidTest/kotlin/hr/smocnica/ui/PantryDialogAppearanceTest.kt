package hr.smocnica.ui

import androidx.compose.foundation.layout.*
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

class PantryDialogAppearanceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun lightDialogs() = verify(false)
    @Test fun darkDialogs() = verify(true)

    @Test fun largeTextStacksPairedFields() {
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.5f)) {
                SmocnicaTheme {
                    AdaptiveFormRow { field -> Text("Fotografiraj proizvod", field); Text("Odaberi fotografiju", field) }
                }
            }
        }
        val first = compose.onNodeWithText("Fotografiraj proizvod").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("Odaberi fotografiju").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(first.bottom <= second.top)
        assertEquals(first.width, second.width)
    }

    private fun verify(dark: Boolean) {
        var stage by mutableStateOf(0)
        var savedName = ""
        var filter: ProductFilter? = null
        var adjustedShelf = ""
        val shelves = listOf(Shelf("s1", "p", "Polica 1 - špajza", 0, createdAt = 1, updatedAt = 1),
            Shelf("s2", "p", "Polica 2", 1, createdAt = 1, updatedAt = 1))
        val product = Product("a", "p", "Glatko brašno", createdAt = 1, updatedAt = 1)
        val item = ProductWithStock(product, listOf(Stock("p", "a", "s1", 2, updatedAt = 1, variantId = "v")),
            listOf(ProductVariant("v", "p", "a", "Glatko brašno 1 kg", createdAt = 1, updatedAt = 1)))
        compose.setContent {
            SmocnicaTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize()) { Text("Smočnica", Modifier.padding(24.dp), style = MaterialTheme.typography.headlineMedium) }
                when (stage) {
                    0 -> NameDialog("Nova polica", "", { stage = 1 }, { savedName = it; stage = 1 })
                    1 -> ProductFilterDialog(ProductFilter(), shelves, emptyList(), { stage = 2 }, { filter = it; stage = 2 })
                    2 -> VariantQuickActionDialog(item, shelves, "s1", 1, { stage = 3 }, { _, shelf -> adjustedShelf = shelf; stage = 3 })
                    3 -> ProductEditor(current = null, shelves = shelves, categories = emptyList(), onDismiss = { stage = 4 },
                        recognizePhoto = { error("Potrebna je fotografija.") }, onSave = { _, _, _, _, _, _ -> error("Nevaljani unos se ne smije spremiti.") })
                }
            }
        }
        compose.onNodeWithText("Spremi").assertIsNotEnabled()
        compose.onNodeWithText("Naziv").performTextInput("Nova polica")
        capture("name", dark)
        compose.onNodeWithText("Spremi").performClick()
        compose.runOnIdle { assertEquals("Nova polica", savedName) }
        capture("filters", dark)
        compose.onNodeWithText("Polica: Sve").performClick()
        compose.onNodeWithText("Polica 2").performClick()
        compose.onNodeWithText("Primijeni").performClick()
        compose.runOnIdle { assertEquals(setOf("s2"), filter?.shelfIds) }
        capture("quantity", dark)
        compose.onNodeWithText("Polica: Polica 1 - špajza").performClick()
        capture("picker", dark)
        compose.onNodeWithText("Polica 2").performClick()
        compose.onNodeWithText("Potvrdi").performClick()
        compose.runOnIdle { assertEquals("s2", adjustedShelf) }
        compose.onNodeWithText("Fotografiraj proizvod").assertIsDisplayed()
        compose.onNodeWithText("Spremi").assertIsDisplayed().assertIsNotEnabled()
        capture("product", dark)
        compose.onNodeWithText("Odustani").performClick()
        compose.onNodeWithText("Novi artikl").assertDoesNotExist()
    }

    private fun capture(name: String, dark: Boolean) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.cacheDir, "dialog-$name-${if (dark) "dark" else "light"}.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
