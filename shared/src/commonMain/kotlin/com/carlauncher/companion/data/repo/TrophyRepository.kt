package com.carlauncher.companion.data.repo

import com.carlauncher.companion.data.db.CarDao
import com.carlauncher.companion.data.db.CarModificationDao
import com.carlauncher.companion.data.db.DeviceDao
import com.carlauncher.companion.data.db.EventDao
import com.carlauncher.companion.data.db.LocationPointDao
import com.carlauncher.companion.data.db.TrophyDao
import com.carlauncher.companion.data.db.TrophyProgressEntity
import com.carlauncher.companion.data.db.TrophyUnlockEntity
import com.carlauncher.companion.data.db.UserProfileDao
import com.carlauncher.companion.data.model.Trophy
import com.carlauncher.companion.data.model.TrophyStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** What the Trophies screen renders: the cached snapshot plus when each trophy fired. */
data class TrophyState(
    val stats: TrophyStats = TrophyStats(),
    val unlockedAt: Map<Trophy, Long> = emptyMap(),
) {
    val unlockedCount: Int get() = unlockedAt.size
    val totalCount: Int get() = Trophy.entries.size
}

/**
 * Owns trophy evaluation. Unlocks are permanent once written — deleting history later
 * does not take a trophy back, which is rather the point of an achievement.
 */
@OptIn(ExperimentalTime::class)
class TrophyRepository(
    private val trophyDao: TrophyDao,
    private val locationPointDao: LocationPointDao,
    private val deviceDao: DeviceDao,
    private val eventDao: EventDao,
    private val carDao: CarDao,
    private val modificationDao: CarModificationDao,
    private val userProfileDao: UserProfileDao,
    private val departmentLocator: DepartmentLocator,
) {

    /** A rescan walks every point; overlapping runs would just duplicate that work. */
    private val refreshLock = Mutex()

    fun observeState(): Flow<TrophyState> =
        combine(trophyDao.observeProgress(), trophyDao.observeUnlocks()) { progress, unlocks ->
            TrophyState(
                stats = progress?.toStats() ?: TrophyStats(),
                unlockedAt = unlocks.mapNotNull { row ->
                    // An unlock row for a trophy that no longer exists (a renamed enum
                    // constant) is ignored rather than crashing the screen.
                    runCatching { Trophy.valueOf(row.id) }.getOrNull()?.let { it to row.unlockedAt }
                }.toMap(),
            )
        }

    /**
     * Full rescan across every device. Returns the trophies that fired *this* run so the
     * caller can notify about them; an empty list means nothing new.
     */
    suspend fun refresh(): List<Trophy> = refreshLock.withLock {
        withContext(Dispatchers.Default) {
            val stats = computeStats()
            trophyDao.upsertProgress(stats.toEntity())

            val already = trophyDao.getUnlocks()
                .mapNotNull { runCatching { Trophy.valueOf(it.id) }.getOrNull() }
                .toSet()
            val newlyUnlocked = Trophy.entries.filter { it !in already && it.isUnlocked(stats) }
            if (newlyUnlocked.isNotEmpty()) {
                val now = Clock.System.now().toEpochMilliseconds()
                trophyDao.insertUnlocks(newlyUnlocked.map { TrophyUnlockEntity(it.name, now) })
            }
            newlyUnlocked
        }
    }

    /**
     * Trophies unlocked (possibly in a background service, app closed) but never shown to
     * the user in-app — what the celebration popup renders. A trophy leaves this set only
     * when [acknowledgeCelebration] is called for it, not merely by being fetched.
     */
    fun observePendingCelebrations(): Flow<List<Trophy>> =
        trophyDao.observeUnseen().map { rows ->
            rows.mapNotNull { runCatching { Trophy.valueOf(it.id) }.getOrNull() }
        }

    /** Marks trophies as shown, so the popup for them never appears again. */
    suspend fun acknowledgeCelebration(trophies: List<Trophy>) {
        if (trophies.isEmpty()) return
        trophyDao.markSeen(trophies.map { it.name }, Clock.System.now().toEpochMilliseconds())
    }

    /**
     * Driving-only stats for a single car, for the badge strip on the car detail screen.
     * Collection counters stay at zero — those are global, not per-car.
     */
    suspend fun statsForDevice(deviceId: String): TrophyStats = withContext(Dispatchers.Default) {
        val accumulator = TrophyAccumulator()
        accumulatePoints(accumulator, deviceId)
        accumulator.build(
            departmentCodes = departmentsFor(accumulator.visitedSquares()),
            carCount = 0,
            modificationCount = 0,
            eventCount = 0,
            gpxImportCount = 0,
            profileFieldsSet = 0,
        )
    }

    private suspend fun computeStats(): TrophyStats {
        val accumulator = TrophyAccumulator()
        for (device in deviceDao.observeAll().first()) {
            accumulatePoints(accumulator, device.deviceId)
        }

        val cars = carDao.observeAll().first()
        val modificationCount = modificationDao.countAll()
        val events = eventDao.observeAll().first()
        val profile = userProfileDao.observe().first()

        return accumulator.build(
            departmentCodes = departmentsFor(accumulator.visitedSquares()),
            carCount = cars.size,
            modificationCount = modificationCount,
            eventCount = events.size,
            gpxImportCount = events.count { it.pointsSource == "GPX" },
            profileFieldsSet = listOf(
                profile?.age != null,
                !profile?.city.isNullOrBlank(),
                !profile?.departmentCodes.isNullOrBlank(),
            ).count { it },
        )
    }

    /** Pages one device's whole history through [accumulator], oldest first. */
    private suspend fun accumulatePoints(accumulator: TrophyAccumulator, deviceId: String) {
        var afterTs = 0L
        while (true) {
            val page = locationPointDao.pageForDevice(deviceId, afterTs, PAGE_SIZE)
            if (page.isEmpty()) break
            accumulator.addPage(page)
            afterTs = page.last().ts
            if (page.size < PAGE_SIZE) break
        }
        accumulator.finishDevice()
    }

    /**
     * Resolves départements from the visited squares rather than from every point — one
     * lookup per ~10 km cell instead of one per fix, for the same answer.
     */
    private fun departmentsFor(squares: Set<Long>): Set<String> =
        squares.mapNotNullTo(mutableSetOf()) { square ->
            val (lat, lng) = squareCenter(square)
            departmentLocator.codeAt(lat, lng)
        }

    private companion object {
        const val PAGE_SIZE = 2_000
    }
}

private fun TrophyProgressEntity.toStats() = TrophyStats(
    totalDistanceKm = totalDistanceKm,
    longestTripKm = longestTripKm,
    maxSpeedKmh = maxSpeedKmh,
    totalMovingSeconds = totalMovingSeconds,
    tripCount = tripCount,
    nightTripCount = nightTripCount,
    earlyTripCount = earlyTripCount,
    distinctDrivingDays = distinctDrivingDays,
    bestStreakDays = bestStreakDays,
    currentStreakDays = currentStreakDays,
    seasonsDriven = seasonsDriven,
    departmentCodes = departmentCodes.split(",").filter { it.isNotBlank() }.toSet(),
    mapSquaresVisited = mapSquaresVisited,
    maxDistanceFromBaseKm = maxDistanceFromBaseKm,
    carCount = carCount,
    modificationCount = modificationCount,
    eventCount = eventCount,
    gpxImportCount = gpxImportCount,
    profileFieldsSet = profileFieldsSet,
    computedAt = computedAt,
)

private fun TrophyStats.toEntity() = TrophyProgressEntity(
    id = 0,
    totalDistanceKm = totalDistanceKm,
    longestTripKm = longestTripKm,
    maxSpeedKmh = maxSpeedKmh,
    totalMovingSeconds = totalMovingSeconds,
    tripCount = tripCount,
    nightTripCount = nightTripCount,
    earlyTripCount = earlyTripCount,
    distinctDrivingDays = distinctDrivingDays,
    bestStreakDays = bestStreakDays,
    currentStreakDays = currentStreakDays,
    seasonsDriven = seasonsDriven,
    departmentCodes = departmentCodes.sorted().joinToString(","),
    mapSquaresVisited = mapSquaresVisited,
    maxDistanceFromBaseKm = maxDistanceFromBaseKm,
    carCount = carCount,
    modificationCount = modificationCount,
    eventCount = eventCount,
    gpxImportCount = gpxImportCount,
    profileFieldsSet = profileFieldsSet,
    computedAt = computedAt,
)
