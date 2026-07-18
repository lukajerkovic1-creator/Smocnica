package hr.smocnica.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScannerRouteTest {
    @Test
    fun contextualRouteKeepsSourceShelfProductAndMode() {
        val route = scannerRoute(
            ScannerContext(
                sourceLabel = "Polica 3",
                shelfId = "s 3",
                productId = "artikl/1",
                shoppingItemId = "kupnja 1",
                mode = ScannerMode.MOVE,
            ),
        )

        assertTrue(route.startsWith("scanner/context?source=Polica%203"))
        assertTrue(route.contains("shelfId=s%203"))
        assertTrue(route.contains("productId=artikl%2F1"))
        assertTrue(route.contains("shoppingItemId=kupnja%201"))
        assertTrue(route.endsWith("mode=MOVE"))
    }
}
