package radar

import groovy.json.JsonSlurper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A routed section: road geometry as a flat [lat, lon, lat, lon, ...] plus its length. */
class RoutedLine(val line: DoubleArray, val meters: Double)

/**
 * Resolves the road between two points via the OSRM HTTP API.
 *
 * Results are memoised on disk per pair, so regenerating after a GPX edit only pays for the
 * pairs whose coordinates actually changed. The cache lives outside `build/`, so `clean`
 * does not force a fresh round of network traffic.
 */
class OsrmRouter(
    private val baseUrl: String,
    private val delayMillis: Long,
    private val cacheDir: File,
    private val userAgent: String,
    private val log: (String) -> Unit,
) {
    var cacheHits = 0
        private set
    var networkHits = 0
        private set
    var failures = 0
        private set

    private var consecutiveFailures = 0
    private var breakerTripped = false

    /** Returns the road geometry, or null if it could not be resolved. */
    fun route(entry: Waypoint, exit: Waypoint): RoutedLine? {
        val cacheFile = File(cacheDir, cacheKey(entry, exit) + ".txt")
        readCache(cacheFile)?.let {
            cacheHits++
            return it
        }
        if (breakerTripped) {
            failures++
            return null
        }

        val routed = fetch(entry, exit)
        if (routed == null) {
            failures++
            consecutiveFailures++
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES && !breakerTripped) {
                breakerTripped = true
                // Without this, an offline run pays 100+ connect timeouts back to back.
                log("routing unavailable after $MAX_CONSECUTIVE_FAILURES consecutive failures; falling back to straight lines for the rest of this run")
            }
            return null
        }

        consecutiveFailures = 0
        networkHits++
        writeCache(cacheFile, routed)
        return routed
    }

    private fun fetch(entry: Waypoint, exit: Waypoint): RoutedLine? {
        // Politeness: the public demo server is not meant for bulk use. Only sleep when we
        // are actually about to hit the network.
        if (delayMillis > 0) Thread.sleep(delayMillis)

        val url = buildString {
            append(baseUrl.trimEnd('/'))
            append("/route/v1/driving/")
            append("${fmt(entry.lon)},${fmt(entry.lat)};${fmt(exit.lon)},${fmt(exit.lat)}")
            append("?overview=full&geometries=geojson")
        }

        repeat(2) { attempt ->
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    setRequestProperty("User-Agent", userAgent)
                }
                try {
                    val code = connection.responseCode
                    if (code == 429) {
                        // Rate limited: stop trying rather than digging the hole deeper.
                        breakerTripped = true
                        log("routing service returned 429 (rate limited); falling back to straight lines for the rest of this run")
                        return null
                    }
                    if (code !in 200..299) {
                        if (code in 500..599 && attempt == 0) {
                            Thread.sleep(BACKOFF_MILLIS)
                            return@repeat
                        }
                        return null
                    }
                    return parse(connection.inputStream.bufferedReader().readText())
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                if (attempt == 0) {
                    Thread.sleep(BACKOFF_MILLIS)
                } else {
                    log("routing failed for ${fmt(entry.lat)},${fmt(entry.lon)}: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): RoutedLine? {
        val root = JsonSlurper().parseText(body) as? Map<String, Any?> ?: return null
        if (root["code"] != "Ok") return null
        val routes = root["routes"] as? List<Map<String, Any?>> ?: return null
        val route = routes.firstOrNull() ?: return null
        val geometry = route["geometry"] as? Map<String, Any?> ?: return null
        // GeoJSON, so coordinates arrive as [lon, lat] and have to be flipped.
        val coords = geometry["coordinates"] as? List<List<Number>> ?: return null
        if (coords.size < 2) return null

        val flat = DoubleArray(coords.size * 2)
        coords.forEachIndexed { i, pair ->
            flat[i * 2] = pair[1].toDouble()
            flat[i * 2 + 1] = pair[0].toDouble()
        }
        val meters = (route["distance"] as? Number)?.toDouble() ?: return null
        return RoutedLine(flat, meters)
    }

    private fun cacheKey(entry: Waypoint, exit: Waypoint): String {
        val raw = "$baseUrl|${fmt(entry.lat)},${fmt(entry.lon)}|${fmt(exit.lat)},${fmt(exit.lon)}"
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun readCache(file: File): RoutedLine? {
        if (!file.isFile) return null
        return try {
            val lines = file.readLines()
            if (lines.size < 2) return null
            val meters = lines[0].toDouble()
            val flat = lines[1].split(',').map(String::toDouble).toDoubleArray()
            if (flat.size < 4) null else RoutedLine(flat, meters)
        } catch (e: Exception) {
            null
        }
    }

    private fun writeCache(file: File, routed: RoutedLine) {
        try {
            file.parentFile.mkdirs()
            file.writeText(
                buildString {
                    append(routed.meters)
                    append('\n')
                    append(routed.line.joinToString(",") { fmt(it) })
                    append('\n')
                },
            )
        } catch (e: Exception) {
            log("could not write route cache entry ${file.name}: ${e.message}")
        }
    }

    private companion object {
        const val MAX_CONSECUTIVE_FAILURES = 3
        const val BACKOFF_MILLIS = 1_500L

        /** 6 decimal places, and never scientific notation or a locale comma. */
        fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.6f", v)
    }
}
