package hr.smocnica.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomNavigationOptionsTest {
    @Test
    fun `bottom navigation saves and restores destination state`() {
        val startDestinationId = 42

        val options = bottomNavigationOptions(startDestinationId)

        assertEquals(startDestinationId, options.popUpToId)
        assertFalse(options.isPopUpToInclusive())
        assertTrue(options.shouldPopUpToSaveState())
        assertTrue(options.shouldRestoreState())
        assertTrue(options.shouldLaunchSingleTop())
    }
}
