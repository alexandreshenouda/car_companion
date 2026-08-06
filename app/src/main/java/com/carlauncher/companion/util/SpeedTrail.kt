package com.carlauncher.companion.util

import com.carlauncher.companion.data.db.LocationPointEntity
import com.carlauncher.companion.data.model.SpeedZone
import org.osmdroid.util.GeoPoint

/** One contiguous run of points sharing the same [SpeedZone] color, ready to draw as a Polyline. */
data class SpeedSegment(val color: Int, val points: List<GeoPoint>)

/**
 * Splits [points] (ts-ascending) into contiguous runs sharing the same [SpeedZone] color, e.g. for
 * drawing a history trail whose color changes with speed. Shared by the map screen's history trail
 * and the share-card preview so both render identical zone boundaries.
 */
fun buildSpeedSegments(points: List<LocationPointEntity>): List<SpeedSegment> {
    if (points.size < 2) return emptyList()
    val segments = mutableListOf<SpeedSegment>()
    var i = 0
    while (i < points.size - 1) {
        val color = SpeedZone.forSpeed(points[i].speedKmh).color
        val segment = mutableListOf(GeoPoint(points[i].lat, points[i].lng))
        var j = i
        while (j < points.size - 1 && SpeedZone.forSpeed(points[j].speedKmh).color == color) {
            segment.add(GeoPoint(points[j + 1].lat, points[j + 1].lng))
            j++
        }
        segments.add(SpeedSegment(color, segment))
        i = j
    }
    return segments
}
