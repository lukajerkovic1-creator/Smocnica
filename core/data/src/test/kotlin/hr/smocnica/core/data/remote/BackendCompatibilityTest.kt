package hr.smocnica.core.data.remote

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendCompatibilityTest {
    private val client = mockk<FirebaseCallableClient>()
    private val store = mockk<BackendCompatibilityStore>(relaxed = true)

    @Test
    fun `accepts current backend contract`() {
        val result = evaluateBackendCapabilities(
            mapOf(
                "backendApiVersion" to 7L,
                "capabilities" to listOf(
                    "operation:delete_shopping",
                    "device-registration:v2",
                    "notification-privacy:v1",
                    "single-active-pantry:v1",
                    "canonical-names:v1",
                    "manual-shopping-merge:v1",
                    "atomic-bulk-products:v1",
                    "future:capability",
                    "account-deletion:v1",
                ),
            ),
        )

        assertEquals(BackendCompatibilityResult.Compatible(), result)
    }

    @Test
    fun `blocks older backend`() {
        val result = evaluateBackendCapabilities(
            mapOf("backendApiVersion" to 1L, "capabilities" to emptyList<String>()),
        )

        assertTrue(result is BackendCompatibilityResult.Blocked)
        assertTrue((result as BackendCompatibilityResult.Blocked).message.contains("zastario"))
    }

    @Test
    fun `blocks backend missing required capability`() {
        val result = evaluateBackendCapabilities(
            mapOf(
                "backendApiVersion" to 7L,
                "capabilities" to listOf("operation:delete_shopping", "device-registration:v2"),
            ),
        )

        assertTrue(result is BackendCompatibilityResult.Blocked)
        assertTrue((result as BackendCompatibilityResult.Blocked).message.contains("notification-privacy:v1"))
    }

    @Test
    fun `checker persists a confirmed compatible version`() = runTest {
        coEvery { client.call("getBackendCapabilities") } returns mapOf(
            "backendApiVersion" to 7L,
            "capabilities" to BackendCompatibilityChecker.REQUIRED_CAPABILITIES.toList(),
        )

        val result = BackendCompatibilityChecker(client, store) { false }.check()

        assertEquals(BackendCompatibilityResult.Compatible(), result)
        verify { store.confirmedApiVersion = 7 }
    }

    @Test
    fun `temporary outage uses only a previously confirmed current contract`() = runTest {
        val unavailable = IllegalStateException("offline")
        coEvery { client.call("getBackendCapabilities") } throws unavailable
        every { store.confirmedApiVersion } returns 7

        val result = BackendCompatibilityChecker(client, store) { it === unavailable }.check()

        assertEquals(BackendCompatibilityResult.Compatible(usedCachedConfirmation = true), result)
    }
}
