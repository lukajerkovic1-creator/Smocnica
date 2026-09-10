package hr.smocnica.core.domain

import hr.smocnica.core.model.PackageUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhotoRecognitionTest {
    @Test fun knownPackageAndOptionalManufacturer() {
        val suggestion = PhotoProductSuggestion("Glatko brašno", "", "1", PackageUnit.KG)
        assertEquals(1_000_000L, PackageAmountPolicy.baseAmount(suggestion.packageAmount, suggestion.packageUnit))
        assertEquals(PackageUnit.UNKNOWN, PhotoProductSuggestion("Brašno", "", "", PackageUnit.UNKNOWN).packageUnit)
    }
    @Test fun invalidSuggestionsCannotReachTheEditor() {
        assertThrows(IllegalArgumentException::class.java) { PhotoProductSuggestion("", "", "", PackageUnit.UNKNOWN) }
        assertThrows(IllegalArgumentException::class.java) { PhotoProductSuggestion("Brašno", "", "1", PackageUnit.UNKNOWN) }
        assertThrows(IllegalArgumentException::class.java) { PhotoProductSuggestion("Brašno", "", "-1", PackageUnit.KG) }
    }
}
