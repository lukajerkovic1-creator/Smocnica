package hr.smocnica.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BackendCompatibilityScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun blockedBackendExplainsProblemAndOffersRetry() {
        var retried = false
        compose.setContent {
            MaterialTheme {
                BackendCompatibilityScreen("Poslužitelju nedostaje obvezna mogućnost.") { retried = true }
            }
        }

        compose.onNodeWithText("Poslužitelj treba ažurirati").assertIsDisplayed()
        compose.onNodeWithText("Poslužitelju nedostaje obvezna mogućnost.").assertIsDisplayed()
        compose.onNodeWithText("Pokušaj ponovno").performClick()
        assertTrue(retried)
    }
}
