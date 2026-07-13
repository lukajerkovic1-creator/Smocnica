package hr.smocnica.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import hr.smocnica.core.model.Product
import hr.smocnica.core.model.Shelf
import hr.smocnica.ui.theme.SmocnicaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class ProductEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun productCannotBeSavedWithoutNameAndCapturesPackageData() {
        var saved: Product? = null
        compose.setContent {
            SmocnicaTheme {
                ProductEditor(
                    current = null,
                    shelves = listOf(Shelf("s1", "p1", "Polica 1", 0, createdAt = 1, updatedAt = 1)),
                    categories = emptyList(),
                    onDismiss = {},
                    onSave = { product, _, _, _, _ -> saved = product },
                )
            }
        }
        compose.onNodeWithText("Spremi").assertIsNotEnabled()
        compose.onNodeWithText("Naziv *").performTextInput("Glatko brašno")
        compose.onNodeWithText("Pakiranje / opis").performTextInput("1 kg")
        compose.onNodeWithText("Spremi").performClick()
        assertNotNull(saved)
        assertEquals("Glatko brašno", saved?.name)
        assertEquals("1 kg", saved?.description)
    }

    @Test
    fun invalidBarcodeCannotBeSaved() {
        compose.setContent {
            SmocnicaTheme {
                ProductEditor(
                    current = Product("", "p1", "", barcode = "4006381333932", createdAt = 1, updatedAt = 1),
                    shelves = listOf(Shelf("s1", "p1", "Polica 1", 0, createdAt = 1, updatedAt = 1)),
                    categories = emptyList(),
                    onDismiss = {},
                    onSave = { _, _, _, _, _ -> error("Neispravan barkod ne smije biti spremljen.") },
                )
            }
        }
        compose.onNodeWithText("Naziv *").performTextInput("Artikl")
        compose.onNodeWithText("Spremi").assertIsNotEnabled()
    }
}
