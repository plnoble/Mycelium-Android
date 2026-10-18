package com.xavierxia.mycelium.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckPolicyTest {
    private val hour = 60L * 60 * 1000

    @Test
    fun neverCheckedIsDue() {
        assertTrue(UpdateCheckPolicy.isDue(1_000L, 0L))
    }

    @Test
    fun twentyThreeHoursIsNotDue() {
        val now = 100 * hour
        assertFalse(UpdateCheckPolicy.isDue(now, now - 23 * hour))
    }

    @Test
    fun exactlyTwentyFourHoursIsDue() {
        val now = 100 * hour
        assertTrue(UpdateCheckPolicy.isDue(now, now - UpdateCheckPolicy.INTERVAL_MS))
    }

    @Test
    fun twentyFiveHoursIsDue() {
        val now = 100 * hour
        assertTrue(UpdateCheckPolicy.isDue(now, now - 25 * hour))
    }

    @Test
    fun aClockMovedBackwardsIsDueInsteadOfBlockedForever() {
        val now = 100 * hour
        assertTrue(UpdateCheckPolicy.isDue(now, now + hour))
    }
}