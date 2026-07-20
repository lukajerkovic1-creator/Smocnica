package hr.smocnica.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import hr.smocnica.ui.theme.SmocnicaTheme
import org.junit.Rule
import org.junit.Test

class AboutScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun showsPrivacyAccountDeletionAndOpenFoodFactsLicenses() {
        compose.setContent {
            SmocnicaTheme {
                AboutScreen(PaddingValues(), onBack = {})
            }
        }

        compose.onNodeWithText("Politika privatnosti").assertIsDisplayed()
        compose.onNodeWithText("Upute za brisanje računa").assertIsDisplayed()
        compose.onNodeWithText("Open Food Facts i licence").assertIsDisplayed()
        compose.onNodeWithText("Lokalne ispravke ostaju u Smočnici i ne šalju se u Open Food Facts.")
            .assertIsDisplayed()
    }
}
