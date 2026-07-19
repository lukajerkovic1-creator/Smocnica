package hr.smocnica.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionPolicyTest {
    @Test
    fun openingApplicationDoesNotCreateAFeatureTrigger() {
        assertFalse(shouldExplainNotificationPermission("0", "0", permissionRequired = true, explanationAlreadyShown = false))
    }

    @Test
    fun enablingMinimumExplainsPermissionBeforeRequest() {
        assertTrue(shouldExplainNotificationPermission("0", "1", permissionRequired = true, explanationAlreadyShown = false))
    }

    @Test
    fun explanationIsNotRepeatedOrShownWhenPermissionIsAlreadyGranted() {
        assertFalse(shouldExplainNotificationPermission("0", "1", permissionRequired = false, explanationAlreadyShown = false))
        assertFalse(shouldExplainNotificationPermission("0", "1", permissionRequired = true, explanationAlreadyShown = true))
        assertFalse(shouldExplainNotificationPermission("2", "3", permissionRequired = true, explanationAlreadyShown = false))
    }
}
