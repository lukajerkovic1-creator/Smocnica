package hr.smocnica.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
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

    @Test
    fun permissionIsRecheckedWhenReturningFromApplicationSettings() {
        val owner = TestLifecycleOwner()
        var systemPermissionGranted = false
        compose.setContent {
            val permission = rememberCameraPermissionState(owner) { systemPermissionGranted }
            Text(if (permission.value) "Kamera dopuštena" else "Kamera nije dopuštena")
        }

        compose.onNodeWithText("Kamera nije dopuštena").assertExists()
        compose.runOnUiThread {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            systemPermissionGranted = true
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        compose.onNodeWithText("Kamera dopuštena").assertExists()
    }

    @Test
    fun flashControlIsHiddenWhenCameraHasNoFlashUnit() {
        val hasFlashUnit = androidx.compose.runtime.mutableStateOf(false)
        var toggles = 0
        compose.setContent {
            SmocnicaTheme {
                ScannerFlashButton(
                    hasFlashUnit = hasFlashUnit.value,
                    flash = false,
                    onToggle = { toggles++ },
                )
            }
        }

        compose.onNodeWithContentDescription("Bljeskalica").assertDoesNotExist()
        compose.runOnUiThread { hasFlashUnit.value = true }
        compose.onNodeWithContentDescription("Bljeskalica").performClick()
        assertEquals(1, toggles)
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }
}
