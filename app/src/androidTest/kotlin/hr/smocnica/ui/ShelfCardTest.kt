package hr.smocnica.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import hr.smocnica.core.model.Shelf
import hr.smocnica.ui.theme.SmocnicaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShelfCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun quantityStaysOnOneLineOnNarrowScreen() {
        var opened = false
        compose.setContent {
            SmocnicaTheme {
                Box(Modifier.width(320.dp)) {
                    ShelfCard(
                        shelf = Shelf("s1", "p1", "Polica 1", 0, createdAt = 1, updatedAt = 1),
                        count = 0,
                        canMoveUp = false,
                        canMoveDown = true,
                        canMoveStock = false,
                        onOpen = { opened = true },
                        onMoveUp = {},
                        onMoveDown = {},
                        onMoveStock = {},
                        onEdit = {},
                        onDelete = {},
                    )
                }
            }
        }

        val quantity = compose.onNodeWithText("0 komada").assertIsDisplayed().fetchSemanticsNode()
        assertTrue("Količina mora ostati u jednom retku.", quantity.boundsInRoot.width > quantity.boundsInRoot.height)
        compose.onNodeWithContentDescription("Preimenuj").assertIsDisplayed()
        compose.onNodeWithContentDescription("Obriši").assertIsDisplayed()
        compose.onNodeWithText("Polica 1").performClick()
        assertTrue("Dodir kartice mora otvoriti sadržaj police.", opened)
    }
}
