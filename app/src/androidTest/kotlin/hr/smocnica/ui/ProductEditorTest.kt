package hr.smocnica.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import hr.smocnica.core.domain.CatalogProduct
import hr.smocnica.core.model.Product
import hr.smocnica.core.model.ProductWithStock
import hr.smocnica.core.model.Shelf
import hr.smocnica.core.model.Stock
import hr.smocnica.core.model.Category
import hr.smocnica.ui.theme.SmocnicaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class ProductEditorTest {
    @get:Rule val compose = createComposeRule()

    private val shelves = listOf(
        Shelf("s1", "p1", "Polica 1", 0, createdAt = 1, updatedAt = 1),
        Shelf("s2", "p1", "Polica 2", 1, createdAt = 1, updatedAt = 1),
    )
    private val categories = listOf(
        Category("cat-snacks", "p1", "Grickalice", 7),
        Category("cat-other", "p1", "Ostalo", 9, isDefault = true),
    )

    @Test
    fun notificationPermissionIsExplainedOnlyAfterMinimumIsEnabled() {
        var permissionRequested = false
        compose.setContent {
            SmocnicaTheme {
                ProductEditor(
                    current = Product("", "p1", "", category = "Ostalo", categoryId = "cat-other", createdAt = 1, updatedAt = 1),
                    shelves = shelves,
                    categories = categories,
                    onDismiss = {},
                    notificationPermissionRequiredOverride = true,
                    requestNotificationPermissionOverride = { permissionRequested = true },
                    onSave = { _, _, _, _, _, _ -> },
                )
            }
        }

        compose.onNodeWithText("Obavijesti o minimalnoj zalihi").assertDoesNotExist()
        assertFalse(permissionRequested)

        compose.onNodeWithText("Minimalna količina").performTextInput("1")
        compose.onNodeWithText("Obavijesti o minimalnoj zalihi").assertExists()
        assertFalse(permissionRequested)

        compose.onNodeWithText("Dopusti obavijesti").performClick()
        compose.runOnIdle { assertEquals(true, permissionRequested) }
    }

    @Test
    fun productCannotBeSavedWithoutNameAndCapturesPackageData() {
        var saved: Product? = null
        compose.setContent {
            SmocnicaTheme {
                ProductEditor(
                    current = Product("", "p1", "", category = "Ostalo", createdAt = 1, updatedAt = 1, categoryId = "cat-other"),
                    shelves = shelves,
                    categories = categories,
                    onDismiss = {},
                    onSave = { product, _, _, _, _, done -> saved = product; done(true) },
                )
            }
        }
        compose.onNodeWithText("Spremi").assertIsNotEnabled()
        compose.onNodeWithText("Naziv *").performTextInput("Glatko brašno")
        compose.onNodeWithText("Pakiranje / opis").performTextInput("1 kg")
        compose.onNodeWithText("Spremi").performClick()
        assertNotNull(saved)
        assertEquals("Glatko brašno", saved?.name)
        assertEquals("1 kg", saved?.description)
    }

    @Test
    fun invalidBarcodeCannotBeSaved() {
        compose.setContent {
            SmocnicaTheme {
                ProductEditor(
                    current = Product("", "p1", "", barcode = "4006381333932", createdAt = 1, updatedAt = 1),
                    shelves = shelves,
                    categories = categories,
                    onDismiss = {},
                    onSave = { _, _, _, _, _, _ -> error("Neispravan barkod ne smije biti spremljen.") },
                )
            }
        }
        compose.onNodeWithText("Naziv *").performTextInput("Artikl")
        compose.onNodeWithText("Spremi").assertIsNotEnabled()
    }

    @Test
    fun scannerCancelPreservesForm() {
        compose.setContent {
            SmocnicaTheme {
                ProductEditor(
                    current = null,
                    shelves = shelves,
                    categories = categories,
                    onDismiss = {},
                    barcodeScanner = { _, dismiss ->
                        AlertDialog(
                            onDismissRequest = dismiss,
                            title = { Text("Testni skener") },
                            confirmButton = { Button(dismiss) { Text("Zatvori bez očitanja") } },
                        )
                    },
                    onSave = { _, _, _, _, _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Naziv *").performTextInput("Ručno ime")
        compose.onNodeWithText("Pakiranje / opis").performTextInput("750 g")
        compose.onNodeWithContentDescription("Skeniraj barkod").performClick()
        compose.onNodeWithText("Zatvori bez očitanja").performClick()
        compose.onNodeWithText("Ručno ime").assertExists()
        compose.onNodeWithText("750 g").assertExists()
    }

    @Test
    fun scanAutofillsOnlyEmptyFieldsAndKeepsBarcode() {
        var lookup by mutableStateOf(CatalogLookupState())
        compose.setContent {
            SmocnicaTheme {
                ProductEditor(
                    current = null,
                    shelves = shelves,
                    categories = categories,
                    onDismiss = {},
                    catalogLookup = lookup,
                    requestCatalogLookup = { code ->
                        lookup = CatalogLookupState(
                            code,
                            CatalogLookupOutcome.SUCCESS,
                            CatalogProduct(code, "Javni naziv", "500 g", "Grickalice", null),
                        )
                    },
                    barcodeScanner = { detected, dismiss ->
                        AlertDialog(
                            onDismissRequest = dismiss,
                            title = { Text("Testni skener") },
                            confirmButton = { Button({ detected("4006381333931") }) { Text("Očitaj") } },
                        )
                    },
                    onSave = { _, _, _, _, _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Naziv *").performTextInput("Moje ime")
        compose.onNodeWithContentDescription("Skeniraj barkod").performClick()
        compose.onNodeWithText("Očitaj").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Moje ime").assertExists()
        compose.onNodeWithText("4006381333931").assertExists()
        compose.onNodeWithText("500 g").assertExists()
        compose.onNodeWithText("Kategorija *: Grickalice").assertExists()
    }

    @Test
    fun timeoutKeepsBarcodeAndOffersManualContinuation() {
        var lookup by mutableStateOf(CatalogLookupState())
        compose.setContent {
            SmocnicaTheme {
                ProductEditor(
                    current = null,
                    shelves = shelves,
                    categories = categories,
                    onDismiss = {},
                    catalogLookup = lookup,
                    requestCatalogLookup = { code -> lookup = CatalogLookupState(code, CatalogLookupOutcome.TIMEOUT) },
                    barcodeScanner = { detected, dismiss ->
                        AlertDialog(onDismissRequest = dismiss, title = { Text("Testni skener") }, confirmButton = { Button({ detected("4006381333931") }) { Text("Očitaj") } })
                    },
                    onSave = { _, _, _, _, _, _ -> },
                )
            }
        }
        compose.onNodeWithContentDescription("Skeniraj barkod").performClick()
        compose.onNodeWithText("Očitaj").performClick()

        compose.onNodeWithText("4006381333931").assertExists()
        compose.onNodeWithText("Nastavi ručno").assertExists()
        compose.onNodeWithText("Open Food Facts nije odgovorio na vrijeme. Barkod je sačuvan.").assertExists()
    }

    @Test
    fun existingProductUsesOpeningShelfAndDoubleConfirmationIsBlocked() {
        val existing = ProductWithStock(
            Product("existing", "p1", "Postojeći sok", barcode = "4006381333931", description = "1 l", createdAt = 1, updatedAt = 1),
            listOf(Stock("p1", "existing", "s1", 2, updatedAt = 1)),
        )
        var confirmations = 0
        compose.setContent {
            SmocnicaTheme {
                ProductEditor(
                    current = null,
                    shelves = shelves,
                    categories = categories,
                    onDismiss = {},
                    initialShelfId = "s2",
                    activeProducts = listOf(existing),
                    barcodeScanner = { detected, dismiss ->
                        AlertDialog(onDismissRequest = dismiss, title = { Text("Testni skener") }, confirmButton = { Button({ detected("4006381333931") }) { Text("Očitaj") } })
                    },
                    onAddExisting = { _, shelf, quantity, _ ->
                        confirmations++
                        assertEquals("s2", shelf)
                        assertEquals(1, quantity)
                    },
                    onSave = { _, _, _, _, _, _ -> error("Duplikat se ne smije spremiti kao novi artikl.") },
                )
            }
        }
        compose.onNodeWithContentDescription("Skeniraj barkod").performClick()
        compose.onNodeWithText("Očitaj").performClick()
        compose.onNodeWithText("Artikl već postoji").assertExists()
        compose.onNodeWithText("Postojeći sok").assertExists()
        compose.onNodeWithText("Ukupno: 2 kom").assertExists()
        compose.onNodeWithText("Polica: Polica 2").assertExists()
        compose.onNodeWithText("Dodaj količinu postojećem artiklu").performClick()
        compose.onAllNodesWithText("Spremanje…").assertAll(isNotEnabled())
        assertEquals(1, confirmations)
    }

    @Test
    fun failedExistingProductAdjustmentRestoresControlsAndShowsError() {
        val existing = ProductWithStock(
            Product("existing", "p1", "Postojeći sok", barcode = "4006381333931", createdAt = 1, updatedAt = 1),
            listOf(Stock("p1", "existing", "s1", 2, updatedAt = 1)),
        )
        compose.setContent {
            SmocnicaTheme {
                ProductEditor(
                    current = null,
                    shelves = shelves,
                    categories = categories,
                    onDismiss = {},
                    initialShelfId = "s1",
                    activeProducts = listOf(existing),
                    barcodeScanner = { detected, dismiss ->
                        AlertDialog(
                            onDismissRequest = dismiss,
                            title = { Text("Testni skener") },
                            confirmButton = { Button({ detected("4006381333931") }) { Text("Očitaj") } },
                        )
                    },
                    onAddExisting = { _, _, _, done -> done(false) },
                    onSave = { _, _, _, _, _, _ -> error("Duplikat se ne smije spremiti kao novi artikl.") },
                )
            }
        }

        compose.onNodeWithContentDescription("Skeniraj barkod").performClick()
        compose.onNodeWithText("Očitaj").performClick()
        compose.onNodeWithText("Dodaj količinu postojećem artiklu").performClick()

        compose.onNodeWithText("Dodavanje količine nije uspjelo. Pokušajte ponovno.").assertExists()
        compose.onNodeWithText("Dodaj količinu postojećem artiklu").assertIsEnabled()
        compose.onNodeWithText("Nastavi ručno").assertIsEnabled()
    }
}
