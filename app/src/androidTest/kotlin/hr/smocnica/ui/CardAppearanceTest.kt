package hr.smocnica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import hr.smocnica.core.model.*
import hr.smocnica.ui.theme.SmocnicaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue

class CardAppearanceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun lightCards() = capture(false)
    @Test fun darkCards() = capture(true)

    private fun capture(dark: Boolean) {
        val shelf = Shelf("s", "pantry", "Namirnice za pečenje", 0, createdAt = 1, updatedAt = 1)
        compose.setContent {
            SmocnicaTheme(darkTheme = dark) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Moja smočnica", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                    ShelfCard(shelf, 12, false, true, true, {}, {}, {}, {}, {}, {}, {}, {}, {})
                    ProductCard(
                        ProductWithStock(
                            Product("p", "pantry", "Glatko brašno", category = "Za pečenje", minimumQuantity = 3, minimumAmountBase = 3, createdAt = 1, updatedAt = 1),
                            listOf(Stock("pantry", "p", "s", 2, updatedAt = 1, variantId = "v")),
                            listOf(ProductVariant("v", "pantry", "p", "Glatko brašno", manufacturer = "Čakovečki mlinovi", packageLabel = "1 kg", createdAt = 1, updatedAt = 1)),
                        ), listOf(shelf), null, false, false, {}, {}, {}, {}, {}, {}, {},
                    )
                }
            }
        }
        compose.onNodeWithText("Namirnice za pečenje").assertIsDisplayed()
        compose.onNodeWithText("Glatko brašno").assertIsDisplayed()
        val productBounds = compose.onNodeWithText("Glatko brašno").fetchSemanticsNode().boundsInRoot
        assertTrue("Kartica s pakiranjem i upozorenjem treba ostati kompaktna.", productBounds.height <= 180f * compose.density.density)
        compose.onNodeWithContentDescription("Dodaj jedan").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Ispod minimalne zalihe").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.cacheDir, if (dark) "cards-dark.png" else "cards-light.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
