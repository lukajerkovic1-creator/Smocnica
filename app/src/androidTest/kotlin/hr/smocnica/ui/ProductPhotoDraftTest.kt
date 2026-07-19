package hr.smocnica.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class ProductPhotoDraftTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun savedStateContainsOnlyPathWhileLargePhotoRemainsInTemporaryFile() {
        val restoration = StateRestorationTester(compose)
        lateinit var state: MutableState<String?>
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val photo = File.createTempFile("product-photo-draft-", ".jpg", cacheDir)
        RandomAccessFile(photo, "rw").use { it.setLength(5L * 1024 * 1024) }

        restoration.setContent {
            state = rememberProductPhotoDraftPath("new-product")
            Text(state.value ?: "Nema fotografije")
        }

        compose.runOnUiThread { state.value = photo.absolutePath }
        compose.onNodeWithText(photo.absolutePath).assertExists()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(photo.absolutePath).assertExists()
        assertTrue(photo.isFile)
        assertTrue(deleteTemporaryProductPhoto(photo.parentFile!!, photo.absolutePath))
        assertFalse(photo.exists())
    }

    @Test
    fun cleanupRefusesToDeleteFilesOutsideApplicationCache() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cacheDir = context.cacheDir
        val outside = File(context.filesDir, "product-photo-draft-outside.jpg").apply { writeText("test") }
        try {
            assertFalse(deleteTemporaryProductPhoto(cacheDir, outside.absolutePath))
            assertTrue(outside.exists())
        } finally {
            outside.delete()
        }
    }
}
