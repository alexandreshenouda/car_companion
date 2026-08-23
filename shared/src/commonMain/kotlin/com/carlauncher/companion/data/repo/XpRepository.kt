package com.carlauncher.companion.data.repo

import com.carlauncher.companion.data.db.TrophyDao
import com.carlauncher.companion.data.db.TrophyUnlockEntity
import com.carlauncher.companion.data.db.XpStateDao
import com.carlauncher.companion.data.db.XpStateEntity
import com.carlauncher.companion.data.model.Trophy
import com.carlauncher.companion.data.model.TrophyStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** What the Profile XP badge and the leaderboard row render. */
data class XpState(
    val totalXp: Long,
    val level: Int,
    val xpIntoLevel: Long,
    val xpForNextLevel: Long,
    val currentStreakDays: Int,
    val bestStreakDays: Int,
)

/**
 * Owns XP. Distance/trophy/event/garage XP is a pure function of [TrophyDao]'s already-cached
 * state ([computeBaseXp]) — this repository adds only the login-streak bonus, which is
 * inherently sequential and so needs its own persisted state ([XpStateDao]).
 */
@OptIn(ExperimentalTime::class)
class XpRepository(
    private val trophyDao: TrophyDao,
    private val xpStateDao: XpStateDao,
) {

    fun observeState(): Flow<XpState> =
        combine(trophyDao.observeProgress(), trophyDao.observeUnlocks(), xpStateDao.observe()) { progress, unlocks, xpState ->
            toXpState(progress?.toStats() ?: TrophyStats(), unlocks.toTrophySet(), xpState)
        }

    /** One-shot snapshot, for [com.carlauncher.companion.data.cloud.CloudSyncManager]'s push. */
    suspend fun currentState(): XpState {
        val stats = trophyDao.getProgress()?.toStats() ?: TrophyStats()
        val unlocked = trophyDao.getUnlocks().toTrophySet()
        return toXpState(stats, unlocked, xpStateDao.get())
    }

    /**
     * Credits the login-streak bonus for today, once per calendar day — safe to call on every
     * app open regardless of how many times that happens, since a same-day call is a no-op.
     */
    suspend fun recordAppOpen(): StreakUpdate {
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault()).toEpochDays()
        val current = xpStateDao.get() ?: XpStateEntity()
        val update = nextStreakState(current.lastLoginEpochDay, today, current.currentStreakDays)
        if (update.awarded) {
            xpStateDao.upsert(
                current.copy(
                    currentStreakDays = update.newStreakDays,
                    bestStreakDays = maxOf(current.bestStreakDays, update.newStreakDays),
                    lastLoginEpochDay = today,
                    accumulatedLoginXp = current.accumulatedLoginXp + update.bonusXp,
                ),
            )
        }
        return update
    }

    private fun toXpState(stats: TrophyStats, unlocked: Set<Trophy>, xpState: XpStateEntity?): XpState {
        val totalXp = computeBaseXp(stats, unlocked) + (xpState?.accumulatedLoginXp ?: 0L)
        val level = levelForXp(totalXp)
        return XpState(
            totalXp = totalXp,
            level = level,
            xpIntoLevel = totalXp - xpForLevelStart(level),
            xpForNextLevel = xpForLevelStart(level + 1) - xpForLevelStart(level),
            currentStreakDays = xpState?.currentStreakDays ?: 0,
            bestStreakDays = xpState?.bestStreakDays ?: 0,
        )
    }
}

private fun List<TrophyUnlockEntity>.toTrophySet(): Set<Trophy> =
    mapNotNullTo(mutableSetOf()) { runCatching { Trophy.valueOf(it.id) }.getOrNull() }
