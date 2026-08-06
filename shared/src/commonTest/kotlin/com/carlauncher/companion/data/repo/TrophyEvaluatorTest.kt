package com.carlauncher.companion.data.repo

import com.carlauncher.companion.data.db.LocationPointEntity
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Covers the pure half of the trophy system: trip segmentation, streaks and the map-grid
 * helpers. The repository around it is just paging and DB I/O. Trophy-catalogue tests
 * (progress/unlock/labels) live in the Android app module, next to [com.carlauncher.companion.data.model.Trophy]
 * itself, which carries Compose icon/color and isn't part of this shared module.
 */
@OptIn(ExperimentalTime::class)
class TrophyEvaluatorTest {

    private val zone: TimeZone = TimeZone.of("Europe/Paris")

    private fun point(lat: Double, ts: Long, speedKmh: Int) = LocationPointEntity(
        deviceId = "car",
        lat = lat,
        lng = 5.0,
        ts = ts,
        speedKmh = speedKmh,
        pushedAtMillis = 0,
    )

    private fun epochMillis(dateTime: LocalDateTime, offsetSeconds: Long = 0) =
        (dateTime.toInstant(zone) + offsetSeconds.seconds).toEpochMilliseconds()

    /** 0.01° of latitude is ~1.11 km, which keeps the expected distances easy to reason about. */
    private fun leg(startAt: LocalDateTime, points: Int, startLat: Double = 45.0, speedKmh: Int = 90) =
        (0 until points).map { i ->
            point(
                lat = startLat + i * 0.01,
                ts = epochMillis(startAt, i * 30L),
                speedKmh = speedKmh,
            )
        }

    private fun build(accumulator: TrophyAccumulator) = accumulator.build(
        departmentCodes = emptySet(),
        carCount = 0,
        modificationCount = 0,
        eventCount = 0,
        gpxImportCount = 0,
        profileFieldsSet = 0,
    )

    private fun assertClose(expected: Double, actual: Double, delta: Double) {
        assertTrue(abs(expected - actual) <= delta, "expected $expected, got $actual")
    }

    @Test
    fun `a gap longer than fifteen minutes splits one run into two trips`() {
        val accumulator = TrophyAccumulator(zone)
        accumulator.addPage(leg(LocalDateTime(2026, 3, 2, 9, 0), points = 10))
        // 40 minutes later — well past the 15-minute trip gap.
        accumulator.addPage(leg(LocalDateTime(2026, 3, 2, 9, 45), points = 10, startLat = 46.0))

        val stats = build(accumulator)

        assertEquals(2, stats.tripCount)
        // Two legs of 9 x ~1.11 km each.
        assertClose(20.0, stats.totalDistanceKm, 0.5)
        assertClose(10.0, stats.longestTripKm, 0.5)
    }

    @Test
    fun `a continuous run is a single trip even across pages`() {
        val all = leg(LocalDateTime(2026, 3, 2, 9, 0), points = 20)
        val accumulator = TrophyAccumulator(zone)
        accumulator.addPage(all.take(10))
        accumulator.addPage(all.drop(10))

        assertEquals(1, build(accumulator).tripCount)
    }

    @Test
    fun `a car parked on the driveway is not a trip`() {
        val at = epochMillis(LocalDateTime(2026, 3, 2, 9, 0))
        val accumulator = TrophyAccumulator(zone)
        accumulator.addPage(
            (0 until 5).map { i -> point(lat = 45.0 + i * 0.00001, ts = at + i * 30_000L, speedKmh = 0) },
        )

        val stats = build(accumulator)
        assertEquals(0, stats.tripCount)
        assertEquals(0L, stats.totalMovingSeconds)
    }

    @Test
    fun `trip start hour decides night versus early bird`() {
        val accumulator = TrophyAccumulator(zone)
        accumulator.addPage(leg(LocalDateTime(2026, 3, 2, 23, 0), points = 10))
        accumulator.finishDevice()
        accumulator.addPage(leg(LocalDateTime(2026, 3, 3, 5, 30), points = 10, startLat = 46.0))

        val stats = build(accumulator)
        assertEquals(1, stats.nightTripCount)
        assertEquals(1, stats.earlyTripCount)
    }

    @Test
    fun `moving time ignores stopped points and caps reporting gaps`() {
        val start = epochMillis(LocalDateTime(2026, 3, 2, 9, 0))
        val accumulator = TrophyAccumulator(zone)
        accumulator.addPage(
            listOf(
                point(45.00, start, speedKmh = 90),
                // 30 s later, still moving -> 30 s counted.
                point(45.01, start + 30_000, speedKmh = 90),
                // 10-minute reporting gap inside the trip -> capped at 30 s, not 600 s.
                point(45.02, start + 630_000, speedKmh = 0),
                // Previous point was stopped -> this step contributes no moving time.
                point(45.03, start + 660_000, speedKmh = 90),
            ),
        )

        assertEquals(60L, build(accumulator).totalMovingSeconds)
    }

    @Test
    fun `finishDevice closes the open trip so devices never merge`() {
        val accumulator = TrophyAccumulator(zone)
        accumulator.addPage(leg(LocalDateTime(2026, 3, 2, 9, 0), points = 10))
        accumulator.finishDevice()
        // Same wall clock, different car: must not be stitched onto the previous trip.
        accumulator.addPage(leg(LocalDateTime(2026, 3, 2, 9, 5), points = 10, startLat = 47.0))

        assertEquals(2, build(accumulator).tripCount)
    }

    @Test
    fun `an implausible GPS speed spike never sets the max`() {
        val start = epochMillis(LocalDateTime(2026, 3, 2, 9, 0))
        val accumulator = TrophyAccumulator(zone)
        accumulator.addPage(
            listOf(
                point(45.00, start, speedKmh = 150),
                // The kind of artefact a real history actually contains.
                point(45.01, start + 30_000, speedKmh = 1233),
                point(45.02, start + 60_000, speedKmh = 140),
            ),
        )

        assertEquals(150, build(accumulator).maxSpeedKmh)
    }

    @Test
    fun `best streak finds the longest consecutive run`() {
        val days = listOf(10L, 11L, 12L, 20L, 21L, 30L)
        assertEquals(3, bestStreak(days))
        assertEquals(0, bestStreak(emptyList()))
        assertEquals(1, bestStreak(listOf(7L)))
    }

    @Test
    fun `current streak tolerates yesterday but not older`() {
        val today = kotlinx.datetime.LocalDate(2026, 3, 10).toEpochDays()
        assertEquals(3, currentStreak(listOf(today - 2, today - 1, today), today))
        // Ends yesterday — still live, you just haven't driven yet today.
        assertEquals(2, currentStreak(listOf(today - 2, today - 1), today))
        // Ends two days ago — broken.
        assertEquals(0, currentStreak(listOf(today - 4, today - 3, today - 2), today))
        assertEquals(0, currentStreak(emptyList(), today))
    }

    @Test
    fun `map squares group nearby points and separate distant ones`() {
        val a = squareKey(45.001, 5.001)
        val b = squareKey(45.049, 5.049)
        val far = squareKey(46.5, 6.5)
        assertEquals(a, b)
        assertTrue(a != far)

        val (lat, lng) = squareCenter(a)
        assertClose(45.05, lat, 1e-9)
        assertClose(5.05, lng, 1e-9)
    }

    @Test
    fun `map squares work south of the equator and west of Greenwich`() {
        val key = squareKey(-21.11, -55.53)
        val (lat, lng) = squareCenter(key)
        assertTrue(lat in -21.2..-21.1, "expected $lat within its own cell")
        assertTrue(lng in -55.6..-55.5, "expected $lng within its own cell")
    }

    @Test
    fun `seasons cover all twelve months`() {
        assertEquals(0, seasonOf(1))
        assertEquals(1, seasonOf(4))
        assertEquals(2, seasonOf(7))
        assertEquals(3, seasonOf(10))
        assertEquals(4, (1..12).map(::seasonOf).distinct().size)
    }
}
