package hr.smocnica.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PantryCreationRequestIdTest {
    @Test
    fun `retry on same device and pantry name reuses idempotency key`() {
        val first = pantryCreationRequestId("device-1", "  Obiteljska smočnica ")
        val retry = pantryCreationRequestId("device-1", "obiteljska smočnica")

        assertEquals(first, retry)
        assertTrue(first.matches(Regex("create-[0-9a-f]{64}")))
    }

    @Test
    fun `different creation intents have different keys`() {
        assertNotEquals(
            pantryCreationRequestId("device-1", "Prva"),
            pantryCreationRequestId("device-1", "Druga"),
        )
    }
}
