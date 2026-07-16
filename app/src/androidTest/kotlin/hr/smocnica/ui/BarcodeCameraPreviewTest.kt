package hr.smocnica.ui

import androidx.camera.view.PreviewView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BarcodeCameraPreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun previewUsesDialogCompatibleRendering() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.runOnUiThread {
            val preview = createBarcodePreviewView(context)
            assertEquals(PreviewView.ImplementationMode.COMPATIBLE, preview.implementationMode)
            assertEquals(PreviewView.ScaleType.FILL_CENTER, preview.scaleType)
        }
    }
}
