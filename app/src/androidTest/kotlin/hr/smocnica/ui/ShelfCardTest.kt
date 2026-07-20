package hr.smocnica.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
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
        var scanned = false
        var added = false
        var movedHere = false
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
                        onScan = { scanned = true },
                        onAdd = { added = true },
                        onMoveHere = { movedHere = true },
                    )
                }
            }
        }

        val quantity = compose.onNodeWithText("0 komada").assertIsDisplayed().fetchSemanticsNode()
        assertTrue("Količina mora ostati u jednom retku.", quantity.boundsInRoot.width > quantity.boundsInRoot.height)
        compose.onNodeWithContentDescription("Otvori Polica 1").assertIsDisplayed()
        compose.onNodeWithText("Polica 1").performTouchInput { click() }
        compose.runOnIdle { assertTrue("Dodir kartice mora otvoriti sadržaj police.", opened) }
        compose.onNodeWithContentDescription("Dodatne radnje police").assertIsDisplayed().performClick()
        compose.onNodeWithText("Preimenuj").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Dodatne radnje police").performClick()
        compose.onNodeWithText("Obriši").assertIsDisplayed()
        compose.onNodeWithText("Preimenuj").performClick()
        compose.onNodeWithText("Skeniraj").performClick()
        compose.onNodeWithText("Dodaj").performClick()
        compose.onNodeWithText("Premjesti ovamo").performClick()
        compose.runOnIdle { assertTrue("Kontekstne akcije police moraju biti povezane.", scanned && added && movedHere) }
    }

    @Test
    fun actionsRemainReadableAndAtLeast48DpAtTwoHundredPercentFontOn360Dp() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                SmocnicaTheme {
                    Box(Modifier.width(360.dp)) {
                        ShelfCard(
                            shelf = Shelf("s1", "p1", "Dugačak naziv police", 0, createdAt = 1, updatedAt = 1),
                            count = 123,
                            canMoveUp = true,
                            canMoveDown = true,
                            canMoveStock = true,
                            onOpen = {}, onMoveUp = {}, onMoveDown = {}, onMoveStock = {}, onEdit = {}, onDelete = {},
                            onScan = {}, onAdd = {}, onMoveHere = {},
                        )
                    }
                }
            }
        }

        listOf("Skeniraj", "Dodaj", "Premjesti ovamo").forEach { label ->
            val bounds = compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Dodirna površina za $label mora biti najmanje 48 dp.", bounds.height >= 48f * compose.density.density)
        }
        listOf("Pomakni gore", "Pomakni dolje", "Dodatne radnje police").forEach { label ->
            val bounds = compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Dodirna površina za $label mora biti najmanje 48 dp.", bounds.width >= 48f * compose.density.density && bounds.height >= 48f * compose.density.density)
        }
        compose.onNodeWithContentDescription("Dodatne radnje police").performClick()
        compose.onNodeWithText("Preimenuj").assertIsDisplayed()
        compose.onNodeWithText("Premjesti sve").assertIsDisplayed()
    }
}
