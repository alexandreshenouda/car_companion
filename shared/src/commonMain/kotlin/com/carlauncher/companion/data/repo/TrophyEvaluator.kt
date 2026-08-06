package com.carlauncher.companion.data.repo

import com.carlauncher.companion.data.db.LocationPointEntity
import com.carlauncher.companion.data.model.TrophyStats
import com.carlauncher.companion.util.haversineKm
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.todayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.math.floor
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Streaming accumulator turning raw GPS points into a [TrophyStats] snapshot.
 *
 * Kept as a plain mutable class fed page-by-page rather than a pure fold over one big
 * list: a lifetime of points does not fit comfortably in memory, and
 * [com.carlauncher.companion.data.repo.TrophyRepository] pages them in per device.
 *
 * Feed one device fully before starting the next ([finishDevice] closes the open trip),
 * then call [build].
 */
@OptIn(ExperimentalTime::class)
class TrophyAccumulator(private val zone: TimeZone = TimeZone.currentSystemDefault()) {

    private var totalDistanceKm = 0.0
    private var longestTripKm = 0.0
    private var maxSpeedKmh = 0
    private var totalMovingSeconds = 0L
    private var tripCount = 0
    private var nightTripCount = 0
    private var earlyTripCount = 0

    private val drivingDays = mutableSetOf<Long>()
    private val seasons = mutableSetOf<Int>()
    private val mapSquares = mutableSetOf<Long>()
    private val squareVisits = mutableMapOf<Long, Int>()

    // Open-trip state, reset between devices.
    private var previous: LocationPointEntity? = null
    private var openTripKm = 0.0
    private var openTripStartTs: Long? = null

    /** Points must be ordered by `ts` ascending, and all belong to the same device. */
    fun addPage(points: List<LocationPointEntity>) {
        for (point in points) {
            // Real histories contain GPS glitches reading several hundred km/h. The Stats
            // screen shows those raw, but a trophy unlock is permanent — a single bad fix
            // must not hand out "Redline" forever.
            if (point.speedKmh <= MAX_PLAUSIBLE_SPEED_KMH) {
                maxSpeedKmh = maxOf(maxSpeedKmh, point.speedKmh)
            }

            val square = squareKey(point.lat, point.lng)
            mapSquares += square
            squareVisits[square] = (squareVisits[square] ?: 0) + 1

            val prev = previous
            if (prev == null || point.ts - prev.ts > TRIP_GAP_MILLIS) {
                closeTrip()
                openTripStartTs = point.ts
            } else {
                val stepKm = haversineKm(prev.lat, prev.lng, point.lat, point.lng)
                totalDistanceKm += stepKm
                openTripKm += stepKm
                // Same cap StatsCalculator applies, so a reporting gap inside a trip
                // isn't billed as driving time.
                val deltaSeconds = ((point.ts - prev.ts) / 1000).coerceIn(0, MAX_SAMPLE_GAP_SECONDS)
                if (prev.speedKmh > MOVING_SPEED_KMH) totalMovingSeconds += deltaSeconds
            }
            previous = point
        }
    }

    /** Closes the trip left open by the last page of a device. */
    fun finishDevice() {
        closeTrip()
        previous = null
    }

    fun build(
        departmentCodes: Set<String>,
        carCount: Int,
        modificationCount: Int,
        eventCount: Int,
        gpxImportCount: Int,
        profileFieldsSet: Int,
    ): TrophyStats {
        finishDevice()
        val days = drivingDays.sorted()
        return TrophyStats(
            totalDistanceKm = totalDistanceKm,
            longestTripKm = longestTripKm,
            maxSpeedKmh = maxSpeedKmh,
            totalMovingSeconds = totalMovingSeconds,
            tripCount = tripCount,
            nightTripCount = nightTripCount,
            earlyTripCount = earlyTripCount,
            distinctDrivingDays = days.size,
            bestStreakDays = bestStreak(days),
            currentStreakDays = currentStreak(days),
            seasonsDriven = seasons.size,
            departmentCodes = departmentCodes,
            mapSquaresVisited = mapSquares.size,
            maxDistanceFromBaseKm = maxDistanceFromBase(),
            carCount = carCount,
            modificationCount = modificationCount,
            eventCount = eventCount,
            gpxImportCount = gpxImportCount,
            profileFieldsSet = profileFieldsSet,
            computedAt = Clock.System.now().toEpochMilliseconds(),
        )
    }

    /** Every map square entered so far, for callers wanting the raw coverage. */
    fun visitedSquares(): Set<Long> = mapSquares

    private fun closeTrip() {
        val startTs = openTripStartTs
        // A couple of stray points parked on a driveway is not a trip.
        if (startTs != null && openTripKm >= MIN_TRIP_KM) {
            tripCount++
            longestTripKm = maxOf(longestTripKm, openTripKm)
            val start = Instant.fromEpochMilliseconds(startTs).toLocalDateTime(zone)
            drivingDays += start.date.toEpochDays()
            seasons += seasonOf(start.month.number)
            when (start.hour) {
                in 22..23, in 0..4 -> nightTripCount++
                in 5..6 -> earlyTripCount++
            }
        }
        openTripKm = 0.0
        openTripStartTs = null
    }

    /**
     * "Home" is the square driven in most often, which beats using the first-ever point:
     * it survives a holiday being the oldest data in the database.
     */
    private fun maxDistanceFromBase(): Double {
        val base = squareVisits.maxByOrNull { it.value }?.key ?: return 0.0
        val (baseLat, baseLng) = squareCenter(base)
        return mapSquares.maxOfOrNull { square ->
            val (lat, lng) = squareCenter(square)
            haversineKm(baseLat, baseLng, lat, lng)
        } ?: 0.0
    }

    private companion object {
        /** Longer than this between two fixes and it is a new trip, not a pause. */
        const val TRIP_GAP_MILLIS = 15 * 60 * 1000L
        const val MAX_SAMPLE_GAP_SECONDS = 30L
        const val MOVING_SPEED_KMH = 2
        const val MIN_TRIP_KM = 0.3

        /** Above this a fix is a GPS artefact, not a car. Leaves headroom above the 400 km/h trophy. */
        const val MAX_PLAUSIBLE_SPEED_KMH = 450
    }
}

/** Meteorological seasons, 0 = winter. Only the count matters to the trophy. */
fun seasonOf(monthValue: Int): Int = when (monthValue) {
    12, 1, 2 -> 0
    3, 4, 5 -> 1
    6, 7, 8 -> 2
    else -> 3
}

/** Longest run of consecutive days in an ascending, de-duplicated list of epoch days. */
fun bestStreak(sortedDays: List<Long>): Int {
    if (sortedDays.isEmpty()) return 0
    var best = 1
    var run = 1
    for (i in 1 until sortedDays.size) {
        run = if (sortedDays[i] == sortedDays[i - 1] + 1) run + 1 else 1
        best = maxOf(best, run)
    }
    return best
}

/**
 * Streak ending today or yesterday — yesterday still counts, otherwise the streak would
 * read as broken every morning before the first drive.
 */
@OptIn(ExperimentalTime::class)
fun currentStreak(
    sortedDays: List<Long>,
    today: Long = Clock.System.todayIn(TimeZone.currentSystemDefault()).toEpochDays(),
): Int {
    if (sortedDays.isEmpty()) return 0
    val last = sortedDays.last()
    if (today - last > 1) return 0
    var run = 1
    for (i in sortedDays.size - 1 downTo 1) {
        if (sortedDays[i] == sortedDays[i - 1] + 1) run++ else break
    }
    return run
}

/**
 * ~10 km grid cell. 0.1° of latitude is ~11 km everywhere; 0.1° of longitude is ~7 km at
 * French latitudes, so cells are rectangular rather than square — close enough for a
 * "how much ground have you covered" counter.
 */
internal const val SQUARE_DEGREES = 0.1

fun squareKey(lat: Double, lng: Double): Long {
    val latIdx = floor(lat / SQUARE_DEGREES).toInt()
    val lngIdx = floor(lng / SQUARE_DEGREES).toInt()
    return (latIdx.toLong() shl 32) or (lngIdx.toLong() and 0xFFFFFFFFL)
}

fun squareCenter(key: Long): Pair<Double, Double> {
    val latIdx = (key shr 32).toInt()
    val lngIdx = key.toInt()
    return (latIdx + 0.5) * SQUARE_DEGREES to (lngIdx + 0.5) * SQUARE_DEGREES
}
