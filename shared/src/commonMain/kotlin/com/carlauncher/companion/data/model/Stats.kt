package com.carlauncher.companion.data.model

data class TrackStats(
    val pointCount: Int,
    val maxSpeedKmh: Int,
    val avgSpeedKmh: Double,
    val medianSpeedKmh: Double,
    val distanceKm: Double,
    val movingTimeSeconds: Long,
    val firstTs: Long?,
    val lastTs: Long?,
    val speedZones: List<SpeedZoneTime>,
) {
    companion object {
        val EMPTY = TrackStats(
            pointCount = 0,
            maxSpeedKmh = 0,
            avgSpeedKmh = 0.0,
            medianSpeedKmh = 0.0,
            distanceKm = 0.0,
            movingTimeSeconds = 0,
            firstTs = null,
            lastTs = null,
            speedZones = SpeedZone.entries.map { SpeedZoneTime(it, 0, 0.0) },
        )
    }
}

/** Time spent (derived from point timestamps, same as [TrackStats.movingTimeSeconds]) within a [SpeedZone]. */
data class SpeedZoneTime(val zone: SpeedZone, val seconds: Long, val percentage: Double)
