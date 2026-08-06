package radar

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.Locale

/**
 * Builds `assets/radar_sections.json` from the bundled radar GPX files.
 *
 * The GPX data carries no link between the entry and exit of an average-speed section, so
 * pairs are inferred geometrically (see [pairSections]) and the road between them is
 * resolved through OSRM at build time. That keeps the app itself free of any routing
 * dependency or runtime network call.
 *
 * The GPX files are declared as inputs, so the asset is regenerated exactly when the radar
 * data changes and is UP-TO-DATE otherwise -- the lines can never disagree with the GPX,
 * and an ordinary build costs nothing.
 */
@DisableCachingByDefault(because = "Depends on a remote routing service; results are memoised on disk instead")
abstract class GenerateRadarSectionsTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val gpxFiles: ConfigurableFileCollection

    @get:Input
    abstract val osrmBaseUrl: Property<String>

    @get:Input
    abstract val requestDelayMillis: Property<Long>

    @get:Input
    abstract val minPairMeters: Property<Double>

    @get:Input
    abstract val maxPairMeters: Property<Double>

    /**
     * Rejects a pair whose road distance exceeds this multiple of its straight-line
     * distance. This is the second half of the pairing guard: [minPairMeters] rejects
     * opposite-carriageway decoys that are metres apart, and this rejects the ones that
     * squeaked past it -- two points 400 m apart on opposing carriageways need an 8.7 km
     * drive between them, whereas a genuine section routes almost straight.
     */
    @get:Input
    abstract val maxDetourRatio: Property<Double>

    /** When true, any straight-line fallback fails the build instead of warning. */
    @get:Input
    abstract val strict: Property<Boolean>

    /**
     * Survives `clean` and is deliberately neither an input nor an output, so it never
     * takes part in up-to-date checks.
     */
    @get:Internal
    abstract val routeCacheDir: DirectoryProperty

    /** Set by AGP through `addGeneratedSourceDirectory`; never assign it at registration. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val cacheDir = routeCacheDir.get().asFile.also { it.mkdirs() }
        val router = OsrmRouter(
            baseUrl = osrmBaseUrl.get(),
            delayMillis = requestDelayMillis.get(),
            cacheDir = cacheDir,
            userAgent = "car_localizer_android radar-section generator (+https://github.com/)",
            log = { logger.info("Radar sections: $it") },
        )

        val min = minPairMeters.get()
        val max = maxPairMeters.get()
        val maxDetour = maxDetourRatio.get()
        val records = mutableListOf<String>()
        val perCountry = linkedMapOf<String, Int>()
        var fallbacks = 0
        var detourRejects = 0

        for (gpx in gpxFiles.files.sortedBy { it.name }) {
            val country = gpx.nameWithoutExtension
            val waypoints = parseSectionWaypoints(gpx)
            if (waypoints.isEmpty()) continue

            val pairs = pairSections(waypoints, min, max)
            var kept = 0

            for (pair in pairs) {
                val routed = router.route(pair.entry, pair.exit)
                if (routed != null && routed.meters > pair.straightMeters * maxDetour) {
                    // Not a section: no plausible average-speed stretch makes you drive
                    // several times its own length. Drawing nothing beats drawing a loop.
                    detourRejects++
                    logger.info(
                        "Radar sections: dropped $country pair at ${pair.entry.lat},${pair.entry.lon} " +
                            "-- ${routed.meters.toInt()} m of road for ${pair.straightMeters.toInt()} m straight",
                    )
                    continue
                }
                val line = routed?.line
                    ?: doubleArrayOf(pair.entry.lat, pair.entry.lon, pair.exit.lat, pair.exit.lon)
                val meters = routed?.meters ?: pair.straightMeters
                if (routed == null) fallbacks++
                records.add(record(country, pair, line, meters, routed != null))
                kept++
            }
            perCountry[country] = kept
        }

        val summary = perCountry.entries.joinToString(", ") { "${it.key} ${it.value}" }
        logger.lifecycle("Radar sections: ${records.size} pairs ($summary)")
        if (detourRejects > 0) {
            logger.lifecycle(
                "Radar sections: dropped $detourRejects implausible pair(s) exceeding the ${maxDetour}x detour ratio",
            )
        }
        logger.lifecycle(
            "Radar sections: ${router.cacheHits} from cache, ${router.networkHits} routed via OSRM, $fallbacks straight-line fallback",
        )

        if (fallbacks > 0) {
            val message = "$fallbacks section(s) fell back to a straight line -- the routing service was unreachable or had no route"
            if (strict.get()) {
                throw GradleException(
                    "Radar sections: $message. Re-run with network access, or drop -PradarSections.strict.",
                )
            }
            logger.warn("Radar sections: WARNING - $message")
        }

        val lastGood = File(cacheDir, "last-good.json")
        val json = buildString {
            append("{\"version\":1,\"source\":\"OSRM\",\"sections\":[")
            append(records.joinToString(","))
            append("]}")
        }

        val outFile = File(outputDir.get().asFile.also { it.mkdirs() }, "radar_sections.json")
        if (records.isNotEmpty() && fallbacks == records.size && lastGood.isFile) {
            // Nothing routed at all and we have a previous good result: shipping that beats
            // shipping 100+ straight lines that claim to be roads.
            logger.warn("Radar sections: nothing could be routed; reusing the last successfully generated asset")
            lastGood.copyTo(outFile, overwrite = true)
            return
        }

        outFile.writeText(json)
        if (fallbacks == 0 && records.isNotEmpty()) lastGood.writeText(json)
        logger.lifecycle("Radar sections: wrote ${outFile.name} (${outFile.length() / 1024} KB)")
    }

    private fun record(
        country: String,
        pair: SectionPair,
        line: DoubleArray,
        meters: Double,
        routed: Boolean,
    ): String = buildString {
        append("{\"country\":\"").append(country).append("\"")
        append(",\"directed\":").append(pair.directed)
        append(",\"routed\":").append(routed)
        append(",\"lengthM\":").append(meters.toInt())
        append(",\"entry\":[").append(c(pair.entry.lat)).append(',').append(c(pair.entry.lon)).append(']')
        append(",\"exit\":[").append(c(pair.exit.lat)).append(',').append(c(pair.exit.lon)).append(']')
        append(",\"line\":[")
        line.forEachIndexed { i, v ->
            if (i > 0) append(',')
            append(c(v))
        }
        append("]}")
    }

    private companion object {
        /** 5 dp is ~1.1 m, plenty for a map polyline; OSRM's 6th digit is 11 cm of payload. */
        fun c(v: Double): String =
            String.format(Locale.ROOT, "%.5f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }
}
