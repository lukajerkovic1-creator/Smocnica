package hr.smocnica.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import hr.smocnica.core.data.messaging.NotificationPrivacyMode
import hr.smocnica.ui.theme.SmocnicaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NotificationPrivacySettingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun privateAndDetailedModesExplainLockScreenContentAndInvokeSelection() {
        var selected: NotificationPrivacyMode? = null
        compose.setContent {
            SmocnicaTheme {
                NotificationPrivacySetting(NotificationPrivacyMode.PRIVATE, false) { selected = it }
            }
        }

        compose.onNodeWithText("Na zaključanom zaslonu piše samo da je jedan artikl ispod minimalne zalihe.")
            .assertIsDisplayed()
        compose.onNodeWithText("Detaljne").performClick()
        compose.runOnIdle { assertEquals(NotificationPrivacyMode.DETAILED, selected) }
    }

    @Test
    fun controlsAreDisabledWhileServerPreferenceIsBeingSaved() {
        compose.setContent {
            SmocnicaTheme {
                NotificationPrivacySetting(NotificationPrivacyMode.DETAILED, true) {}
            }
        }

        compose.onNodeWithText("Spremanje postavke…").assertIsDisplayed()
        compose.onNodeWithText("Privatne").assertIsNotEnabled()
        compose.onNodeWithText("Detaljne").assertIsNotEnabled()
    }
}
