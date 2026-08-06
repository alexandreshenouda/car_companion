package com.carlauncher.companion.data.model

/**
 * An average-speed camera section: the road between an entry radar and its exit.
 *
 * The GPX radar data carries no link between the two ends, so the pairing and the road
 * geometry are both derived at build time (see the `generateRadarSections` Gradle task) and
 * shipped in `assets/radar_sections.json`.
 *
 * Not a data class: [line] is a [DoubleArray], which would give the generated `equals` and
 * `hashCode` identity semantics and trip the `ArrayInDataClass` lint. Nothing here needs
 * value equality.
 */
class RadarSection(
    val country: String,
    /** True when the source labelled which end is the entry; false when it was inferred from proximity alone. */
    val directed: Boolean,
    /** False when the road could not be resolved and [line] is just the two endpoints. */
    val routed: Boolean,
    val lengthMeters: Int,
    val entryLat: Double,
    val entryLon: Double,
    val exitLat: Double,
    val exitLon: Double,
    /** Road geometry as a flat [lat, lon, lat, lon, ...]. */
    val line: DoubleArray,
) {
    // Precomputed rather than derived on demand: the map's draw block reruns on every
    // recomposition and every pan, and scanning ~200 points per section there would be the
    // one real performance mistake available in this feature.
    private val minLat: Double
    private val maxLat: Double
    private val minLon: Double
    private val maxLon: Double

    init {
        var loLat = Double.MAX_VALUE
        var hiLat = -Double.MAX_VALUE
        var loLon = Double.MAX_VALUE
        var hiLon = -Double.MAX_VALUE
        var i = 0
        while (i < line.size) {
            val lat = line[i]
            val lon = line[i + 1]
            if (lat < loLat) loLat = lat
            if (lat > hiLat) hiLat = lat
            if (lon < loLon) loLon = lon
            if (lon > hiLon) hiLon = lon
            i += 2
        }
        minLat = loLat
        maxLat = hiLat
        minLon = loLon
        maxLon = hiLon
    }

    /**
     * Bounding-box overlap with a viewport. Testing the endpoints instead would hide any
     * section long enough to span the screen with both radars off it.
     */
    fun intersects(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): Boolean =
        this.minLat <= maxLat && this.maxLat >= minLat && this.minLon <= maxLon && this.maxLon >= minLon
}
