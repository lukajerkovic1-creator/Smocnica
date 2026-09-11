package hr.smocnica.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomNavigationOptionsTest {
    @Test
    fun `bottom navigation discards nested destination state`() {
        val startDestinationId = 42

        val options = sectionNavigationOptions(startDestinationId)

        assertEquals(startDestinationId, options.popUpToId)
        assertFalse(options.isPopUpToInclusive())
        assertFalse(options.shouldPopUpToSaveState())
        assertFalse(options.shouldRestoreState())
        assertTrue(options.shouldLaunchSingleTop())
    }
}
