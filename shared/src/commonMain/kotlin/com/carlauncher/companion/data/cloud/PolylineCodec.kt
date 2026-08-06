package com.carlauncher.companion.data.cloud

import kotlin.math.round

/**
 * Google's "Encoded Polyline Algorithm Format" (precision 5) — the same encoding Google Maps,
 * OSRM, and most mapping tooling use. Chosen over a raw lat/lng JSON array purely for size: it
 * runs roughly 5-8x smaller, which matters on the 500 MB Supabase free tier when every shared
 * event's trace goes into `event_tracks.encoded_polyline`.
 *
 * Pure and self-contained so it stays unit-testable without touching Room or the network.
 */
object PolylineCodec {

    private const val PRECISION = 1e5

    fun encode(points: List<Pair<Double, Double>>): String {
        val sb = StringBuilder()
        var prevLat = 0L
        var prevLng = 0L
        for ((lat, lng) in points) {
            val curLat = round(lat * PRECISION).toLong()
            val curLng = round(lng * PRECISION).toLong()
            encodeValue(curLat - prevLat, sb)
            encodeValue(curLng - prevLng, sb)
            prevLat = curLat
            prevLng = curLng
        }
        return sb.toString()
    }

    fun decode(encoded: String): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        var index = 0
        var lat = 0L
        var lng = 0L
        while (index < encoded.length) {
            lat += decodeValue(encoded, index).also { index = it.second }.first
            lng += decodeValue(encoded, index).also { index = it.second }.first
            points += (lat / PRECISION) to (lng / PRECISION)
        }
        return points
    }

    private fun encodeValue(value: Long, sb: StringBuilder) {
        var v = value shl 1
        if (value < 0) v = v.inv()
        while (v >= 0x20L) {
            sb.append(((0x20L or (v and 0x1fL)) + 63L).toInt().toChar())
            v = v shr 5
        }
        sb.append((v + 63L).toInt().toChar())
    }

    /** @return the decoded delta and the index just past the consumed characters. */
    private fun decodeValue(encoded: String, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var index = start
        while (true) {
            val b = encoded[index++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
        }
        val delta = if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)
        return delta to index
    }
}
