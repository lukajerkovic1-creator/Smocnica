package hr.smocnica.ui

import hr.smocnica.core.model.PackageUnit
import hr.smocnica.core.model.ProductVariant
import org.junit.Assert.assertEquals
import org.junit.Test

class InventoryPackageLabelTest {
    private val milk = ProductVariant("v", "pantry", "p", "Mlijeko", manufacturer = "Testna mljekara",
        packageAmountBase = 500, packageUnit = PackageUnit.L, createdAt = 1, updatedAt = 1)

    @Test fun sameNamesKeepDifferentSizes() {
        assertEquals("Mlijeko · 0,5 l · Testna mljekara", inventoryPackageLabel("Mlijeko", milk))
        assertEquals("Mlijeko · 1 l · Testna mljekara", inventoryPackageLabel("Mlijeko", milk.copy(packageAmountBase = 1000)))
    }

    @Test fun knownSizeWinsOverGenericPackageLabel() {
        assertEquals("Mlijeko · 0,5 l · Testna mljekara", inventoryPackageLabel("Mlijeko", milk.copy(packageLabel = "Boca")))
    }

    @Test fun legacyLabelsAndUnknownSizesRemainVisible() {
        val legacy = milk.copy(packageAmountBase = null, packageUnit = PackageUnit.UNKNOWN, manufacturer = "")
        assertEquals("Mlijeko · 500 ml", inventoryPackageLabel("Mlijeko", legacy.copy(packageLabel = " 500 ml ")))
        assertEquals("Mlijeko · Nepoznata veličina · Barkod: 12345678", inventoryPackageLabel("Mlijeko", legacy.copy(barcode = "12345678")))
    }

    @Test fun distinctiveNamesRemainButEqualNamesAreNotRepeated() {
        assertEquals("MLIJEKO · 0,5 l", inventoryPackageLabel(" MLIJEKO ", milk.copy(manufacturer = "Mlijeko")))
        assertEquals("Mlijeko · 0,5 l · Testna mljekara · Bez laktoze", inventoryPackageLabel("Mlijeko", milk.copy(displayName = "Bez laktoze")))
    }
}
