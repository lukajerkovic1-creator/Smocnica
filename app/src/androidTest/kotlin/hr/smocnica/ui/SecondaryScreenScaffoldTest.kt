package hr.smocnica.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import hr.smocnica.ui.theme.SmocnicaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SecondaryScreenScaffoldTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun topAppBarShowsTitleAndNavigatesBack() {
        var wentBack = false
        compose.setContent {
            SmocnicaTheme {
                SecondaryScreenScaffold(
                    title = "Povijest aktivnosti",
                    outerPadding = PaddingValues(),
                    onBack = { wentBack = true },
                ) { inner ->
                    Box(Modifier.padding(inner)) { Text("Sadržaj ekrana") }
                }
            }
        }

        compose.onNodeWithText("Povijest aktivnosti").assertIsDisplayed()
        compose.onNodeWithContentDescription("Natrag").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue("Strelica mora pozvati povratnu navigaciju.", wentBack) }
    }

    @Test
    fun mandatoryUpdateCanHideBackNavigation() {
        compose.setContent {
            SmocnicaTheme {
                SecondaryScreenScaffold(
                    title = "Obavezno ažuriranje",
                    outerPadding = PaddingValues(),
                    onBack = null,
                ) { }
            }
        }

        compose.onNodeWithContentDescription("Natrag").assertDoesNotExist()
    }
}
