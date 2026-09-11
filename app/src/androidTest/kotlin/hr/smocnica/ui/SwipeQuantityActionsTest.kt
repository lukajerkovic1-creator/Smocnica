package hr.smocnica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import hr.smocnica.ui.theme.SmocnicaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SwipeQuantityActionsTest {
    @get:Rule val compose = createComposeRule()
    private var added = 0
    private var removed = 0

    private fun show(available: Int = 2, enabled: Boolean = true, largeText: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 2f else 1f)) {
                SmocnicaTheme {
                    Box(Modifier.width(360.dp).testTag("swipe")) {
                        SwipeQuantityActions(enabled, available, { added++ }, { removed++ }) {
                            Surface(Modifier.fillMaxWidth().height(120.dp).testTag("front")) {
                                Text("Fusilli · 2 pakiranja", Modifier.padding(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun onlyLeftRevealIsAllowedAndFullSwipeCannotDismissOrChangeQuantity() {
        show()
        val start = compose.onNodeWithTag("front").fetchSemanticsNode().positionInRoot.x
        compose.onNodeWithTag("swipe").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(start, compose.onNodeWithTag("front").fetchSemanticsNode().positionInRoot.x, 1f)
        compose.onNodeWithContentDescription("Dodaj jedan gestom").assertDoesNotExist()
        compose.onNodeWithTag("swipe").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val expected = start - 144f * compose.density.density
        assertEquals(expected, compose.onNodeWithTag("front").fetchSemanticsNode().positionInRoot.x, 1f)
        compose.onNodeWithTag("swipe").performTouchInput { swipe(Offset(width * .4f, centerY), Offset(1f, centerY)) }
        compose.waitForIdle()
        assertEquals(expected, compose.onNodeWithTag("front").fetchSemanticsNode().positionInRoot.x, 1f)
        assertEquals(0, added)
        assertEquals(0, removed)
        compose.onNodeWithContentDescription("Dodaj jedan gestom").assertIsDisplayed()
        compose.onNodeWithContentDescription("Izvadi jedan gestom").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.cacheDir, "swipe-quantity.png").outputStream().use {
            compose.onNodeWithTag("swipe").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("swipe").performTouchInput { swipe(Offset(1f, centerY), Offset(width - 1f, centerY)) }
        compose.waitForIdle()
        assertEquals(start, compose.onNodeWithTag("front").fetchSemanticsNode().positionInRoot.x, 1f)
    }

    @Test fun emptyStockStillOffersAddAndDisablesRemoveAtLargeFont() {
        show(available = 0, largeText = true)
        compose.onNodeWithTag("swipe").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("Izvadi jedan gestom").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Dodaj jedan gestom").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(1, added)
        assertEquals(0, removed)
        compose.onNodeWithContentDescription("Dodaj jedan gestom").assertDoesNotExist()
    }

    @Test fun selectionModeDoesNotRevealActions() {
        show(enabled = false)
        compose.onNodeWithTag("swipe").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("Dodaj jedan gestom").assertDoesNotExist()
        assertEquals(0, added)
    }
}
