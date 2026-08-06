package com.carlauncher.companion.data.repo

import android.content.res.AssetManager
import android.util.Xml
import com.carlauncher.companion.data.model.RadarPoint
import com.carlauncher.companion.data.model.RadarType
import com.carlauncher.companion.util.haversineKm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import kotlin.math.cos

private const val RADARS_DIR = "radars"
private const val METERS_PER_DEGREE_LAT = 111_320.0

/** A [RadarPoint] paired with its great-circle distance from a query location. */
data class NearestRadar(val point: RadarPoint, val distanceMeters: Double)

private data class CountryFile(
    val fileName: String,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
) {
    fun intersects(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): Boolean =
        this.minLat <= maxLat && this.maxLat >= minLat && this.minLon <= maxLon && this.maxLon >= minLon
}

// Rough geographic bounding boxes for the bundled countries. Only used to decide which GPX
// file(s) are worth parsing for a given map viewport, so precision doesn't matter beyond
// avoiding obviously unrelated countries.
private val COUNTRY_FILES = listOf(
    CountryFile("ALLEMAGNE.gpx", 47.2, 55.1, 5.8, 15.1),
    CountryFile("ANDORRE.gpx", 42.4, 42.7, 1.4, 1.8),
    CountryFile("AUSTRALIE.gpx", -44.0, -10.0, 112.0, 154.0),
    CountryFile("BELGIQUE.gpx", 49.4, 51.6, 2.5, 6.4),
    CountryFile("BRESIL.gpx", -34.0, 5.5, -74.0, -34.0),
    CountryFile("BULGARIE.gpx", 41.2, 44.3, 22.3, 28.6),
    CountryFile("CANADA.gpx", 41.7, 83.5, -141.0, -52.0),
    CountryFile("EMIRATSARABESUNIS.gpx", 22.5, 26.5, 51.0, 56.5),
    CountryFile("ESPAGNE.gpx", 27.6, 43.9, -18.4, 4.6),
    CountryFile("FINLANDE.gpx", 59.7, 70.1, 20.5, 31.6),
    CountryFile("FRANCE.gpx", 41.3, 51.1, -5.2, 9.6),
    CountryFile("GRANDE-BRETAGNE.gpx", 49.9, 60.9, -8.2, 1.8),
    CountryFile("IRLANDE.gpx", 51.4, 55.4, -10.5, -6.0),
    CountryFile("ITALIE.gpx", 35.5, 47.1, 6.6, 18.5),
    CountryFile("LETTONIE.gpx", 55.7, 58.1, 21.0, 28.2),
    CountryFile("LUXEMBOURG.gpx", 49.4, 50.2, 5.7, 6.5),
    CountryFile("MAROC.gpx", 27.6, 35.9, -13.2, -1.0),
    CountryFile("NORVEGE.gpx", 57.9, 71.2, 4.5, 31.1),
    CountryFile("NOUVELLE-ZELANDE.gpx", -47.3, -34.4, 166.4, 178.6),
    CountryFile("PAYS-BAS.gpx", 50.7, 53.6, 3.3, 7.3),
    CountryFile("POLOGNE.gpx", 49.0, 54.9, 14.1, 24.2),
    CountryFile("PORTUGAL.gpx", 36.8, 42.2, -9.6, -6.2),
    CountryFile("REPUBLIQUETCHEQUE.gpx", 48.5, 51.1, 12.0, 18.9),
    CountryFile("SUEDE.gpx", 55.3, 69.1, 10.9, 24.2),
    CountryFile("SUISSE.gpx", 45.8, 47.9, 5.9, 10.5),
)

/** Loads the bundled OsmAnd radar GPX files under assets/radars, one country at a time. */
class RadarRepository(private val assets: AssetManager) {
    private val mutex = Mutex()
    private val loadedCountries = mutableMapOf<String, List<RadarPoint>>()

    /**
     * Parses and caches only the bundled country files whose rough bounding box overlaps the
     * given viewport, returning every point loaded so far (including countries loaded by
     * earlier, different-viewport calls). Keeps startup fast by not parsing all ~33k bundled
     * points up front, pulling countries in on demand as the map reaches them instead.
     */
    suspend fun pointsForViewport(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<RadarPoint> {
        val toLoad = COUNTRY_FILES.filter {
            it.fileName !in loadedCountries && it.intersects(minLat, maxLat, minLon, maxLon)
        }
        if (toLoad.isNotEmpty()) {
            mutex.withLock {
                for (country in toLoad) {
                    if (country.fileName in loadedCountries) continue
                    loadedCountries[country.fileName] = withContext(Dispatchers.IO) {
                        assets.open("$RADARS_DIR/${country.fileName}").use { parseGpx(it) }
                    }
                }
            }
        }
        return loadedCountries.values.flatten()
    }

    /**
     * Closest bundled radar to [lat]/[lon] within [radiusMeters], or null if none are that
     * close. Loads whichever country file(s) cover a small box around the point (via
     * [pointsForViewport]) rather than duplicating the GPX-loading logic here.
     */
    suspend fun nearestWithinRadius(lat: Double, lon: Double, radiusMeters: Double): NearestRadar? {
        val latPad = radiusMeters / METERS_PER_DEGREE_LAT
        val lonPad = radiusMeters / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(lat)).coerceAtLeast(0.1))
        val candidates = pointsForViewport(
            minLat = lat - latPad,
            maxLat = lat + latPad,
            minLon = lon - lonPad,
            maxLon = lon + lonPad,
        )
        return candidates
            .map { NearestRadar(it, haversineKm(lat, lon, it.lat, it.lon) * 1000.0) }
            .filter { it.distanceMeters <= radiusMeters }
            .minByOrNull { it.distanceMeters }
    }

    private fun parseGpx(input: java.io.InputStream): List<RadarPoint> {
        val points = mutableListOf<RadarPoint>()
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")

        var insideWpt = false
        var lat = 0.0
        var lon = 0.0
        var label = ""
        var country = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "wpt" -> {
                        insideWpt = true
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                        label = ""
                        country = ""
                    }
                    "name" -> if (insideWpt) label = parser.nextText().trim()
                    "type" -> if (insideWpt) country = formatCountryName(parser.nextText().substringAfter(":").trim())
                }
                XmlPullParser.END_TAG -> if (parser.name == "wpt") {
                    insideWpt = false
                    points.add(
                        RadarPoint(
                            lat = lat,
                            lon = lon,
                            label = label,
                            type = RadarType.fromLabel(label),
                            country = country,
                            speedLimitKmh = label.trim().substringAfterLast(' ').toIntOrNull(),
                        ),
                    )
                }
            }
            eventType = parser.next()
        }
        return points
    }

    private fun formatCountryName(raw: String): String =
        raw.lowercase().split(" ").joinToString(" ") { word ->
            word.split("-").joinToString("-") { part -> part.replaceFirstChar { it.uppercase() } }
        }
}
