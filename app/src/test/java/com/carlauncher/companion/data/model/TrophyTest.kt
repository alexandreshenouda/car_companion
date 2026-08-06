package com.carlauncher.companion.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrophyTest {

    @Test
    fun `trophy progress is a clamped ratio of value to target`() {
        val halfway = TrophyStats(totalDistanceKm = 500.0)
        assertEquals(0.5f, Trophy.ROAD_WARRIOR.progress(halfway), 1e-6f)
        assertFalse(Trophy.ROAD_WARRIOR.isUnlocked(halfway))

        val over = TrophyStats(totalDistanceKm = 5_000.0)
        assertEquals(1f, Trophy.ROAD_WARRIOR.progress(over), 1e-6f)
        assertTrue(Trophy.ROAD_WARRIOR.isUnlocked(over))
        // Progress never reads past the target once earned.
        val progress = Trophy.ROAD_WARRIOR.progressLabel(over)
        assertEquals(1000, progress.current)
        assertEquals(1000, progress.target)
        assertEquals(Trophy.ROAD_WARRIOR.unit, progress.unit)
    }

    @Test
    fun `every trophy has a positive target and a distinct title`() {
        assertTrue(Trophy.entries.all { it.target > 0 })
        assertEquals(Trophy.entries.size, Trophy.entries.map { it.titleRes }.distinct().size)
    }
}
