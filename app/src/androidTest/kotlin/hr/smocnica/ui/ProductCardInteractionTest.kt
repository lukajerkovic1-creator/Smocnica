package hr.smocnica.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import hr.smocnica.core.model.Product
import hr.smocnica.core.model.ProductWithStock
import hr.smocnica.core.model.Shelf
import hr.smocnica.core.model.Stock
import hr.smocnica.ui.theme.SmocnicaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProductCardInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun narrowCardKeepsQuantityActionsVisibleAndDisablesInvalidRemoval() {
        var added = 0
        compose.setContent {
            SmocnicaTheme {
                Box(Modifier.width(360.dp)) {
                    ProductCard(
                        item = ProductWithStock(
                            Product("a1", "p1", "Jabuka", createdAt = 1, updatedAt = 1),
                            listOf(Stock("p1", "a1", "s1", 0, updatedAt = 1)),
                        ),
                        shelves = listOf(Shelf("s1", "p1", "Polica 1", 0, createdAt = 1, updatedAt = 1)),
                        selectedShelfId = "s1",
                        selected = false,
                        selectionMode = false,
                        open = {},
                        select = {},
                        increment = { added++ },
                        decrement = {},
                        move = {},
                        edit = {},
                        delete = {},
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Dodaj jedan").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Izvadi jedan").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Dodatne radnje").assertIsDisplayed()
        assertEquals(1, added)
    }

    @Test
    fun swipeRevealsAndRunsMoveAction() {
        var moved = 0
        compose.setContent {
            SmocnicaTheme {
                ProductCard(
                    item = ProductWithStock(
                        Product("a1", "p1", "Jabuka", createdAt = 1, updatedAt = 1),
                        listOf(Stock("p1", "a1", "s1", 2, updatedAt = 1)),
                    ),
                    shelves = listOf(
                        Shelf("s1", "p1", "Polica 1", 0, createdAt = 1, updatedAt = 1),
                        Shelf("s2", "p1", "Polica 2", 1, createdAt = 1, updatedAt = 1),
                    ),
                    selectedShelfId = "s1",
                    selected = false,
                    selectionMode = false,
                    open = {},
                    select = {},
                    increment = {},
                    decrement = {},
                    move = { moved++ },
                    edit = {},
                    delete = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Dodatne radnje").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("Izvadi jedan gestom").assertIsDisplayed()
        compose.onNodeWithContentDescription("Premjesti gestom").performClick()
        compose.runOnIdle { assertEquals(1, moved) }
    }

    @Test
    fun swipeRevealsAndRunsRemoveAction() {
        var removed = 0
        compose.setContent {
            SmocnicaTheme {
                ProductCard(
                    item = ProductWithStock(
                        Product("a1", "p1", "Jabuka", createdAt = 1, updatedAt = 1),
                        listOf(Stock("p1", "a1", "s1", 2, updatedAt = 1)),
                    ),
                    shelves = listOf(
                        Shelf("s1", "p1", "Polica 1", 0, createdAt = 1, updatedAt = 1),
                        Shelf("s2", "p1", "Polica 2", 1, createdAt = 1, updatedAt = 1),
                    ),
                    selectedShelfId = "s1",
                    selected = false,
                    selectionMode = false,
                    open = {},
                    select = {},
                    increment = {},
                    decrement = { removed++ },
                    move = {},
                    edit = {},
                    delete = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Dodatne radnje").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("Premjesti gestom").assertIsDisplayed()
        compose.onNodeWithContentDescription("Izvadi jedan gestom").performClick()
        compose.runOnIdle { assertEquals(1, removed) }
    }
}
