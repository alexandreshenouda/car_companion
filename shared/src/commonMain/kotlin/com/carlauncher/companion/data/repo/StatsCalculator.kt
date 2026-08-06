package com.carlauncher.companion.data.repo

import com.carlauncher.companion.data.db.LocationPointEntity
import com.carlauncher.companion.data.model.SpeedZone
import com.carlauncher.companion.data.model.SpeedZoneTime
import com.carlauncher.companion.data.model.TrackStats
import com.carlauncher.companion.util.haversineKm

// Consecutive points can be spaced arbitrarily far apart (sampling interval changes, the app or
// device going offline, etc.). Elapsed time between two points is only counted towards moving
// time / speed-zone time up to this cap, so a long gap in reporting isn't misread as continuous
// driving.
private const val MAX_SAMPLE_GAP_SECONDS = 30L

/** Points must already be ordered by [LocationPointEntity.ts] ascending. */
fun computeStats(points: List<LocationPointEntity>): TrackStats {
    if (points.isEmpty()) return TrackStats.EMPTY

    val speeds = points.map { it.speedKmh }.sorted()
    val avg = speeds.average()
    val median = if (speeds.size % 2 == 0) {
        (speeds[speeds.size / 2 - 1] + speeds[speeds.size / 2]) / 2.0
    } else {
        speeds[speeds.size / 2].toDouble()
    }

    var distanceKm = 0.0
    var movingTimeSeconds = 0L
    val zoneSeconds = mutableMapOf<SpeedZone, Long>()
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val cur = points[i]
        distanceKm += haversineKm(prev.lat, prev.lng, cur.lat, cur.lng)

        // Elapsed time is attributed to the earlier point's speed/zone, based on the actual
        // reporting timestamps rather than assuming a fixed sampling rate.
        val deltaSeconds = ((cur.ts - prev.ts) / 1000).coerceIn(0, MAX_SAMPLE_GAP_SECONDS)
        if (prev.speedKmh > 2) movingTimeSeconds += deltaSeconds
        val zone = SpeedZone.forSpeed(prev.speedKmh)
        zoneSeconds[zone] = (zoneSeconds[zone] ?: 0L) + deltaSeconds
    }

    return TrackStats(
        pointCount = points.size,
        maxSpeedKmh = speeds.last(),
        avgSpeedKmh = avg,
        medianSpeedKmh = median,
        distanceKm = distanceKm,
        movingTimeSeconds = movingTimeSeconds,
        firstTs = points.first().ts,
        lastTs = points.last().ts,
        speedZones = computeSpeedZones(zoneSeconds),
    )
}

private fun computeSpeedZones(zoneSeconds: Map<SpeedZone, Long>): List<SpeedZoneTime> {
    val total = zoneSeconds.values.sum()
    return SpeedZone.entries.map { zone ->
        val seconds = zoneSeconds[zone] ?: 0L
        SpeedZoneTime(zone, seconds = seconds, percentage = if (total > 0) seconds * 100.0 / total else 0.0)
    }
}
