package hr.smocnica.core.domain

import hr.smocnica.core.model.PackageUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoEntryFieldsTest {
    @Test fun backLabelFillsSizeWithoutReplacingFrontIdentity() {
        val front = PhotoEntryFields("Mlijeko", "Dukat", "", PackageUnit.UNKNOWN)
        val merged = front.merge(front, PhotoProductSuggestion("Mliječni proizvod", "", "1", PackageUnit.L), true)
        assertEquals(PhotoEntryFields("Mlijeko", "Dukat", "1", PackageUnit.L), merged)
    }
    @Test fun lateResultPreservesManualEditsAndKnownSize() {
        val start = PhotoEntryFields("", "", "", PackageUnit.UNKNOWN)
        val edited = PhotoEntryFields("Moje mlijeko", "Proizvođač", "500", PackageUnit.ML)
        assertEquals(edited, edited.merge(start, PhotoProductSuggestion("Mlijeko", "", "1", PackageUnit.L), false))
        assertEquals(edited, edited.merge(edited, PhotoProductSuggestion("Mlijeko", "", "1", PackageUnit.L), true))
    }
}
