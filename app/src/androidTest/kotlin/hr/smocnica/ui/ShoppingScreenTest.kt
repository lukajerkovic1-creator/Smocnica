package hr.smocnica.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import hr.smocnica.core.model.Category
import hr.smocnica.core.model.ShoppingItem
import hr.smocnica.ui.theme.SmocnicaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShoppingScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun manualItemExposesDeleteActionInOverflowMenu() {
        var deleteRequested = false
        compose.setContent {
            SmocnicaTheme {
                ShoppingRow(
                    item = ShoppingItem(
                        id = "manual-1", pantryId = "p1", name = "Kruh", category = "Ostalo",
                        requiredQuantity = 1, manual = true, createdAt = 1, updatedAt = 1,
                    ),
                    checked = {}, scanAndStore = {}, edit = {}, delete = { deleteRequested = true },
                )
            }
        }

        compose.onNodeWithContentDescription("Dodatne radnje").performClick()
        compose.onNodeWithText("Obriši").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(deleteRequested) }
    }

    @Test
    fun manualItemUsesAnExistingCategoryInsteadOfFreeText() {
        var savedCategoryId = ""
        val categories = listOf(
            Category("cat-snacks", "p1", "Grickalice", 1),
            Category("cat-other", "p1", "Ostalo", 9, isDefault = true),
        )
        compose.setContent {
            SmocnicaTheme {
                ManualShoppingDialog(
                    current = null,
                    categories = categories,
                    dismiss = {},
                    save = { _, categoryId, _ -> savedCategoryId = categoryId },
                )
            }
        }

        compose.onNodeWithText("Naziv").performTextInput("Čips")
        compose.onNodeWithText("Kategorija: Ostalo").performClick()
        compose.onNodeWithText("Grickalice").performClick()
        compose.onNodeWithText("Kategorija: Grickalice").assertIsDisplayed()
        compose.onNodeWithText("Kategorija").assertDoesNotExist()
        compose.onNodeWithText("Dodaj").performClick()
        compose.runOnIdle { assertEquals("cat-snacks", savedCategoryId) }
    }
}
