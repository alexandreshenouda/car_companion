package com.carlauncher.companion.data.repo

import com.carlauncher.companion.data.model.Trophy
import com.carlauncher.companion.data.model.TrophyStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Covers the pure XP math: [computeBaseXp]'s weighting, the [levelForXp]/[xpForLevelStart]
 * curve, and [nextStreakState]'s three day-transition branches. */
class XpCalculatorTest {

    @Test
    fun computeBaseXp_weighsEachSourceIndependently() {
        val stats = TrophyStats(totalDistanceKm = 250.0, eventCount = 2, carCount = 1, modificationCount = 3)
        val expected = 250L * XpWeights.XP_PER_KM +
            2L * XpWeights.XP_PER_EVENT +
            1L * XpWeights.XP_PER_CAR +
            3L * XpWeights.XP_PER_MODIFICATION
        assertEquals(expected, computeBaseXp(stats, unlockedTrophies = emptySet()))
    }

    @Test
    fun computeBaseXp_sumsTrophyTierBonuses() {
        val unlocked = setOf(Trophy.FIRST_LIGHT, Trophy.CENTURY, Trophy.ROAD_WARRIOR, Trophy.REDLINE)
        val expected = XpWeights.trophyXp(Trophy.FIRST_LIGHT.tier) +
            XpWeights.trophyXp(Trophy.CENTURY.tier) +
            XpWeights.trophyXp(Trophy.ROAD_WARRIOR.tier) +
            XpWeights.trophyXp(Trophy.REDLINE.tier)
        assertEquals(expected.toLong(), computeBaseXp(TrophyStats(), unlocked))
    }

    @Test
    fun computeBaseXp_zeroStatsAndNoTrophiesIsZero() {
        assertEquals(0L, computeBaseXp(TrophyStats(), emptySet()))
    }

    @Test
    fun levelForXp_startsAtLevelOne() {
        assertEquals(1, levelForXp(0))
        assertEquals(1, levelForXp(99))
    }

    @Test
    fun levelForXp_isMonotonicallyNonDecreasing() {
        var previous = levelForXp(0)
        var xp = 0L
        while (xp < 200_000L) {
            val level = levelForXp(xp)
            assertTrue(level >= previous, "level regressed at xp=$xp: $level < $previous")
            previous = level
            xp += 137L
        }
    }

    @Test
    fun xpForLevelStart_roundTripsWithLevelForXp() {
        for (level in 1..50) {
            val threshold = xpForLevelStart(level)
            assertEquals(level, levelForXp(threshold), "level $level should start exactly at its own threshold")
        }
    }

    @Test
    fun nextStreakState_sameDayIsANoOp() {
        val update = nextStreakState(lastLoginEpochDay = 100L, todayEpochDay = 100L, currentStreakDays = 4)
        assertEquals(4, update.newStreakDays)
        assertEquals(0, update.bonusXp)
        assertTrue(!update.awarded)
    }

    @Test
    fun nextStreakState_consecutiveDayExtendsStreak() {
        val update = nextStreakState(lastLoginEpochDay = 100L, todayEpochDay = 101L, currentStreakDays = 4)
        assertEquals(5, update.newStreakDays)
        assertEquals(XpWeights.loginStreakBonus(5), update.bonusXp)
        assertTrue(update.awarded)
    }

    @Test
    fun nextStreakState_gapResetsStreakToOne() {
        val update = nextStreakState(lastLoginEpochDay = 100L, todayEpochDay = 105L, currentStreakDays = 20)
        assertEquals(1, update.newStreakDays)
        assertEquals(XpWeights.loginStreakBonus(1), update.bonusXp)
        assertTrue(update.awarded)
    }

    @Test
    fun nextStreakState_firstEverOpenStartsStreakAtOne() {
        val update = nextStreakState(lastLoginEpochDay = null, todayEpochDay = 42L, currentStreakDays = 0)
        assertEquals(1, update.newStreakDays)
        assertEquals(XpWeights.loginStreakBonus(1), update.bonusXp)
        assertTrue(update.awarded)
    }

    @Test
    fun loginStreakBonus_capsAtOneHundred() {
        assertEquals(100, XpWeights.loginStreakBonus(20))
        assertEquals(100, XpWeights.loginStreakBonus(1000))
        assertEquals(25, XpWeights.loginStreakBonus(5))
    }
}
