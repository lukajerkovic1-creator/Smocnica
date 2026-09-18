package hr.smocnica.ui

import android.Manifest
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import hr.smocnica.ui.theme.SmocnicaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class ProductCameraDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shutterReturnsJpegWithoutASecondConfirmation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.CAMERA}").close()
        val result = AtomicReference<String?>()
        val visible = mutableStateOf(true)
        compose.setContent {
            SmocnicaTheme {
                if (visible.value) ProductCameraDialog(false, { visible.value = false }, { error("Fallback was not requested") }) {
                    result.set(it); visible.value = false
                }
            }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasText("Snimi fotografiju") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Snimi fotografiju").performClick()
        compose.waitUntil(20_000) { result.get() != null }
        val file = File(requireNotNull(result.get()))
        try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            assertTrue("Camera must return a readable photo", bitmap != null && bitmap.width > 0 && bitmap.height > 0)
            bitmap?.recycle()
            compose.onNodeWithText("Snimi fotografiju").assertDoesNotExist()
        } finally { deleteTemporaryProductPhoto(context.cacheDir, file.absolutePath) }
    }
}
