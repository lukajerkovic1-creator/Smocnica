package hr.smocnica.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import hr.smocnica.ui.theme.SmocnicaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CameraPermissionRequiredTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun deniedCameraKeepsManualPathAndProvidesSettingsAction() {
        var settingsClicks = 0
        compose.setContent {
            SmocnicaTheme {
                CameraPermissionRequired(
                    permissionDenied = true,
                    requestPermission = {},
                    openSettings = { settingsClicks++ },
                )
            }
        }

        compose.onNodeWithText("Za skeniranje je potreban pristup kameri. Barkod i dalje možete upisati ručno.").assertExists()
        compose.onNodeWithText("Ponovno zatraži dopuštenje").assertExists()
        compose.onNodeWithText("Otvori postavke dopuštenja").performClick()
        assertEquals(1, settingsClicks)
    }
}
