package com.carlauncher.companion.data.repo

import com.carlauncher.companion.data.db.LocationPointEntity
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** GPX string building — pure, reused by both platforms. File writing (Android `Uri`, iOS
 * document export) is each platform's own concern; see the `writeToUri` extension in `:app`'s
 * `GpxExporterAndroid.kt`. */
object GpxExporter {

    /** Standard GPX 1.1: one <trk> with a single <trkseg>, [points] must already be ts-ascending. */
    fun buildGpx(trackName: String, points: List<LocationPointEntity>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"Car Companion\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <trk>\n    <name>${escapeXml(trackName)}</name>\n    <trkseg>\n")
        points.forEach { p ->
            append("      <trkpt lat=\"${p.lat}\" lon=\"${p.lng}\">\n")
            append("        <time>${formatGpxTime(p.ts)}</time>\n")
            append("      </trkpt>\n")
        }
        append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    private fun escapeXml(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** ISO-8601 UTC timestamp, as required by the GPX 1.1 <time> element. */
    @OptIn(ExperimentalTime::class)
    private fun formatGpxTime(epochMillis: Long): String =
        Instant.fromEpochSeconds(epochMillis / 1000).toString()
}
