package hr.smocnica.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import hr.smocnica.ui.theme.SmocnicaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccountDeletionDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun explainsConsequencesAndRequiresExplicitConfirmation() {
        var confirmed = false
        compose.setContent {
            SmocnicaTheme {
                AccountDeletionDialog(onDismiss = {}, onConfirm = { confirmed = true })
            }
        }

        compose.onNodeWithText("Trajno izbrisati korisnički račun?").assertIsDisplayed()
        compose.onNodeWithText("Potvrdi").performClick()
        compose.runOnIdle { assertTrue(confirmed) }
    }
}
