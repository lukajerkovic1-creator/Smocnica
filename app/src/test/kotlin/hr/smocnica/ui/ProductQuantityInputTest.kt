package hr.smocnica.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductQuantityInputTest {
    @Test
    fun acceptsInclusiveSupportedRange() {
        assertEquals(0, parseProductQuantity("0"))
        assertEquals(1_000_000, parseProductQuantity("1000000"))
    }

    @Test
    fun rejectsEmptyNegativeOverflowAndIntegerOverflow() {
        assertNull(parseProductQuantity(""))
        assertNull(parseProductQuantity("-1"))
        assertNull(parseProductQuantity("1000001"))
        assertNull(parseProductQuantity("999999999999999999999999"))
    }
}
