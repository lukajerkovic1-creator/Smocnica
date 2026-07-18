package hr.smocnica.ui

import hr.smocnica.core.domain.CatalogProduct
import hr.smocnica.core.model.PhotoSource
import hr.smocnica.core.model.Product
import hr.smocnica.core.model.ProductWithStock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductEntryFlowTest {
    @Test
    fun catalogPopulatesOnlyEmptyFields() {
        val draft = ProductEntryDraft(
            name = "Moj naziv",
            barcode = "4006381333931",
            description = "",
            category = "Moja kategorija",
            photoUri = "content://moja-slika",
            photoSource = PhotoSource.CAMERA,
        )

        val merged = draft.mergeEmptyFields(
            CatalogProduct("4006381333931", "Javni naziv", "500 g", "Grickalice", "https://slika"),
        )

        assertEquals("Moj naziv", merged.name)
        assertEquals("500 g", merged.description)
        assertEquals("Moja kategorija", merged.category)
        assertEquals("content://moja-slika", merged.photoUri)
        assertEquals(PhotoSource.CAMERA, merged.photoSource)
    }

    @Test
    fun partialCatalogResponseKeepsBarcodeAndReportsMissingRequiredFields() {
        val merged = ProductEntryDraft(barcode = "4006381333931")
            .mergeEmptyFields(CatalogProduct("4006381333931", "", "1 l", "", null))

        assertEquals("4006381333931", merged.barcode)
        assertEquals("1 l", merged.description)
        assertEquals(listOf("naziv", "kategoriju"), merged.missingRequiredFields)
    }

    @Test
    fun activeMatchHasPriorityAndDeletedMatchIsStillDiscoverable() {
        val barcode = "4006381333931"
        val active = ProductWithStock(Product("active", "p", "Aktivan", barcode, createdAt = 1, updatedAt = 1), emptyList())
        val deleted = ProductWithStock(Product("deleted", "p", "Obrisan", barcode, createdAt = 1, updatedAt = 1, deletedAt = 2), emptyList())

        assertTrue(findBarcodeInventoryMatch(barcode, listOf(active), listOf(deleted)) is BarcodeInventoryMatch.Active)
        assertTrue(findBarcodeInventoryMatch(barcode, emptyList(), listOf(deleted)) is BarcodeInventoryMatch.Deleted)
    }
}
