package hr.smocnica.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PreferredEntryShelfTest {
    @Test fun contextualShelfWinsAndDeletedRememberedShelfIsIgnored() {
        assertEquals("b", preferredEntryShelf("b", "a", listOf("a", "b")))
        assertEquals("b", preferredEntryShelf("", "b", listOf("a", "b")))
        assertEquals("a", preferredEntryShelf("deleted", "deleted", listOf("a", "b")))
        assertEquals("", preferredEntryShelf("a", "a", emptyList()))
    }
}
