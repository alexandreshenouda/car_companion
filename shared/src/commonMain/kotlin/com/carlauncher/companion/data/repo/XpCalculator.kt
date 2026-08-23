package com.carlauncher.companion.data.repo

import com.carlauncher.companion.data.model.Trophy
import com.carlauncher.companion.data.model.TrophyStats
import com.carlauncher.companion.data.model.TrophyTier
import kotlin.math.floor
import kotlin.math.sqrt

/** Per-source XP weights. Kept as constants (not config) so the leaderboard stays comparable
 * across accounts over time — changing these later would need a one-off re-derivation, same as
 * changing a [Trophy] target would invalidate nothing (unlocks are permanent) but would make an
 * XP total mean something different before/after the change. */
object XpWeights {
    const val XP_PER_KM = 1
    const val XP_PER_EVENT = 30
    const val XP_PER_CAR = 20
    const val XP_PER_MODIFICATION = 10

    fun trophyXp(tier: TrophyTier): Int = when (tier) {
        TrophyTier.BRONZE -> 50
        TrophyTier.SILVER -> 150
        TrophyTier.GOLD -> 400
        TrophyTier.PLATINUM -> 1000
    }

    /** Day N of an unbroken login streak, capped so a very long streak doesn't dwarf every
     * other source. */
    fun loginStreakBonus(streakDay: Int): Int = (5 * streakDay).coerceAtMost(100)
}

/**
 * XP earned from state [TrophyRepository] already tracks — distance, trophy unlocks, events,
 * garage. Pure function of a [TrophyStats] snapshot plus which trophies are unlocked, same shape
 * as [Trophy.value]. Does NOT include the login-streak bonus: that portion is inherently
 * sequential (today's bonus depends on whether yesterday was played, not on any accumulated
 * stat), so [XpRepository] stores it separately rather than recomputing it here.
 */
fun computeBaseXp(stats: TrophyStats, unlockedTrophies: Set<Trophy>): Long =
    (stats.totalDistanceKm * XpWeights.XP_PER_KM).toLong() +
        stats.eventCount.toLong() * XpWeights.XP_PER_EVENT +
        stats.carCount.toLong() * XpWeights.XP_PER_CAR +
        stats.modificationCount.toLong() * XpWeights.XP_PER_MODIFICATION +
        unlockedTrophies.sumOf { XpWeights.trophyXp(it.tier).toLong() }

/** Level N starts at `100 * (N-1)^2` XP — a standard quadratic curve so each level takes
 * progressively more XP than the last without needing a lookup table. */
fun levelForXp(totalXp: Long): Int = floor(sqrt(totalXp / 100.0)).toInt() + 1

fun xpForLevelStart(level: Int): Long = (level - 1).toLong() * (level - 1) * 100

/** Result of evaluating one app-open against the stored login-streak state. */
data class StreakUpdate(
    val newStreakDays: Int,
    val bonusXp: Int,
    /** False when today was already recorded — calling code should not re-credit XP. */
    val awarded: Boolean,
)

/**
 * Pure day-transition logic for the login streak, kept separate from [XpRepository]'s I/O so it
 * can be unit tested directly. All three days are epoch-day integers (`LocalDate.toEpochDays()`)
 * in the device's current time zone — same zone convention [TrophyAccumulator] already uses for
 * driving-day streaks, so "today" means the same thing in both places.
 */
fun nextStreakState(lastLoginEpochDay: Long?, todayEpochDay: Long, currentStreakDays: Int): StreakUpdate =
    when (lastLoginEpochDay) {
        todayEpochDay -> StreakUpdate(currentStreakDays, bonusXp = 0, awarded = false)
        todayEpochDay - 1 -> {
            val streak = currentStreakDays + 1
            StreakUpdate(streak, XpWeights.loginStreakBonus(streak), awarded = true)
        }
        else -> StreakUpdate(1, XpWeights.loginStreakBonus(1), awarded = true)
    }
