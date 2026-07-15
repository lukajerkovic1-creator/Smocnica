package hr.smocnica.ui

import hr.smocnica.core.model.Activity
import hr.smocnica.core.model.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ActivityPresentationTest {
    @Test
    fun stockAdditionNamesActionShelfAndDevice() {
        val text = activityDescription(activity(ActivityType.STOCK_ADDED, 2, "Polica 1", "Polica 1"))

        assertEquals("Dodano 2 kom na policu Polica 1 · Lukin mobitel", text)
    }

    @Test
    fun legacyNumericTotalsAreNotPresentedAsMeaninglessTransition() {
        val text = activityDescription(activity(ActivityType.STOCK_ADDED, 1, "1", "2"))

        assertEquals("Dodano 1 kom · Lukin mobitel", text)
        assertFalse(text.contains("1 → 2"))
    }

    private fun activity(type: ActivityType, delta: Int?, old: String?, new: String?) = Activity(
        id = "a1",
        pantryId = "p1",
        type = type,
        aggregateId = "product1",
        displayLabel = "Vindi jabuka",
        quantityDelta = delta,
        actorUid = "u1",
        deviceId = "d1",
        deviceName = "Lukin mobitel",
        oldValue = old,
        newValue = new,
        createdAt = 1,
    )
}
