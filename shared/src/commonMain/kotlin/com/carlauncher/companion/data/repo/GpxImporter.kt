package com.carlauncher.companion.data.repo

import com.carlauncher.companion.util.haversineKm
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Same shape as [com.carlauncher.companion.data.db.EventPointEntity] minus the event FK. */
data class GpxPoint(val lat: Double, val lng: Double, val ts: Long, val speedKmh: Int)

/**
 * Pure GPX 1.1 `<trkpt>` parsing — file access (Android `Uri`, iOS document picker) is each
 * platform's own concern; see the `import(context, uri)` extension in `:app`'s
 * `GpxImporterAndroid.kt`.
 *
 * Regex-based rather than a real XML parser: GPX's `<trkpt lat="" lon=""><time>…</time></trkpt>`
 * shape is simple and doesn't nest, and pulling in a multiplatform XML library for this one
 * schema wasn't worth the dependency — same reasoning as the zlib compression seam preferring
 * platform-native primitives over an extra library.
 */
object GpxImporter {

    private data class RawPoint(val lat: Double, val lng: Double, val ts: Long?)

    private val TRKPT_REGEX = Regex("""<trkpt\b([^>]*)>(.*?)</trkpt>""", RegexOption.DOT_MATCHES_ALL)
    private val TIME_REGEX = Regex("""<time\b[^>]*>([^<]*)</time>""")

    fun parse(xml: String): List<GpxPoint> {
        val raw = TRKPT_REGEX.findAll(xml).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val body = match.groupValues[2]
            val lat = attr(attrs, "lat")?.toDoubleOrNull() ?: return@mapNotNull null
            val lng = attr(attrs, "lon")?.toDoubleOrNull() ?: return@mapNotNull null
            val ts = TIME_REGEX.find(body)?.groupValues?.get(1)?.trim()?.let(::parseGpxTime)
            RawPoint(lat, lng, ts)
        }.toList()
        if (raw.isEmpty()) return emptyList()

        // Some loggers omit <time> entirely — synthesize ascending 1s-spaced timestamps so
        // distance/duration math downstream still has something to work with. Anchored to the
        // file's own earliest real timestamp if it has any, else to an arbitrary epoch (0) —
        // deterministic rather than depending on wall-clock "now" at import time.
        val ordered = if (raw.all { it.ts != null }) {
            raw.sortedBy { it.ts }
        } else {
            val base = raw.firstNotNullOfOrNull { it.ts } ?: 0L
            raw.mapIndexed { i, p -> p.copy(ts = p.ts ?: (base + i * 1000L)) }
        }

        val points = mutableListOf<GpxPoint>()
        var prev: RawPoint? = null
        for (p in ordered) {
            val ts = p.ts!!
            val speedKmh = prev?.let { pr ->
                val dtHours = (ts - pr.ts!!) / 3_600_000.0
                if (dtHours > 0) (haversineKm(pr.lat, pr.lng, p.lat, p.lng) / dtHours).toInt().coerceAtLeast(0) else 0
            } ?: 0
            points.add(GpxPoint(p.lat, p.lng, ts, speedKmh))
            prev = p
        }
        return points
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("""\b$name\s*=\s*"([^"]*)"""").find(attrs)?.groupValues?.get(1)

    @OptIn(ExperimentalTime::class)
    private fun parseGpxTime(text: String): Long? =
        runCatching { Instant.parse(text).toEpochMilliseconds() }.getOrNull()
}
