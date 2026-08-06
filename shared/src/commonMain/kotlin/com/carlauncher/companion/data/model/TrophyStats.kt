package com.carlauncher.companion.data.model

/**
 * Lifetime driving aggregate every [Trophy] is scored against.
 *
 * Recomputed from scratch by
 * [com.carlauncher.companion.data.repo.TrophyRepository.refresh] rather than kept
 * incrementally: points can be deleted or moved between cars, and a watermark would
 * quietly drift out of sync with those edits.
 */
data class TrophyStats(
    val totalDistanceKm: Double = 0.0,
    val longestTripKm: Double = 0.0,
    val maxSpeedKmh: Int = 0,
    val totalMovingSeconds: Long = 0,
    val tripCount: Int = 0,
    /** Trips starting between 22:00 and 05:00 local time. */
    val nightTripCount: Int = 0,
    /** Trips starting between 05:00 and 07:00 local time. */
    val earlyTripCount: Int = 0,
    val distinctDrivingDays: Int = 0,
    val bestStreakDays: Int = 0,
    val currentStreakDays: Int = 0,
    /** How many of the four seasons have at least one trip in them. */
    val seasonsDriven: Int = 0,
    /** INSEE codes of the French départements the track has passed through. */
    val departmentCodes: Set<String> = emptySet(),
    /** Distinct ~10 km map squares entered — a geometry-free "area explored" measure. */
    val mapSquaresVisited: Int = 0,
    /** Farthest map square from the one driven in most often. */
    val maxDistanceFromBaseKm: Double = 0.0,

    // Collection counters, read straight off the Garage/Events/Profile tables.
    val carCount: Int = 0,
    val modificationCount: Int = 0,
    val eventCount: Int = 0,
    val gpxImportCount: Int = 0,
    /** 0..3 — age, city and at least one département. */
    val profileFieldsSet: Int = 0,

    val computedAt: Long = 0,
) {
    val departmentsVisited: Int get() = departmentCodes.size
}
