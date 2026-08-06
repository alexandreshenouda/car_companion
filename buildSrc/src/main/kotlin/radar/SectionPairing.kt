package radar

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An average-speed camera pair.
 *
 * [directed] is true when the source data told us which end is which (the FR/BE/ES
 * "Debut"/"Fin" labels); false when the pair was inferred purely from proximity between
 * undifferentiated points (PT/NO), in which case entry and exit are interchangeable.
 */
data class SectionPair(
    val entry: Waypoint,
    val exit: Waypoint,
    val directed: Boolean,
    val straightMeters: Double,
)

private const val EARTH_RADIUS_M = 6_371_000.0

/**
 * Great-circle distance in metres. Duplicated from the app's `util/Haversine.kt` because
 * buildSrc cannot share source with the application module.
 */
fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dPhi = p2 - p1
    val dLambda = Math.toRadians(lon2 - lon1)
    val a = sin(dPhi / 2) * sin(dPhi / 2) + cos(p1) * cos(p2) * sin(dLambda / 2) * sin(dLambda / 2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
}

/**
 * Pairs the entry and exit of every average-speed section among [waypoints].
 *
 * Call this per country file: pairing globally would be both slower and free to invent
 * cross-border sections.
 *
 * The source data carries no link between the two ends, so pairing is geometric. Two
 * details make the naive version wrong:
 *
 *  - [minMeters] is not a nicety, it is the whole algorithm. Bidirectional section controls
 *    put the opposite carriageway's "Fin" 9-100 m from a "Debut", and that decoy is nearer
 *    than the real partner for 20 of 64 French sections. Nearest-neighbour picks it, and so
 *    does mutual-nearest (the decoy pairs are mutually nearest). Only a distance floor
 *    rejects it.
 *  - Assignment is greedy over all candidates sorted by distance, so a point cannot be
 *    claimed by two sections.
 */
fun pairSections(
    waypoints: List<Waypoint>,
    minMeters: Double,
    maxMeters: Double,
): List<SectionPair> {
    if (waypoints.isEmpty()) return emptyList()

    val entries = waypoints.filter { it.name.contains("Debut", ignoreCase = true) }
    val exits = waypoints.filter { it.name.contains("Fin", ignoreCase = true) }
    val directed = entries.isNotEmpty() || exits.isNotEmpty()

    // Candidate (entryIndex, exitIndex, distance) triples within the accepted length band.
    val candidates = mutableListOf<Triple<Int, Int, Double>>()
    val from: List<Waypoint>
    val to: List<Waypoint>

    if (directed) {
        from = entries
        to = exits
        for (i in from.indices) {
            for (j in to.indices) {
                val d = haversineMeters(from[i].lat, from[i].lon, to[j].lat, to[j].lon)
                if (d in minMeters..maxMeters) candidates.add(Triple(i, j, d))
            }
        }
    } else {
        // No Debut/Fin labels (PT/NO): every point is a candidate for either end. Only
        // consider i < j so a pair is not offered twice in both orders.
        from = waypoints
        to = waypoints
        for (i in waypoints.indices) {
            for (j in i + 1 until waypoints.size) {
                val d = haversineMeters(from[i].lat, from[i].lon, to[j].lat, to[j].lon)
                if (d in minMeters..maxMeters) candidates.add(Triple(i, j, d))
            }
        }
    }

    candidates.sortBy { it.third }

    val usedEntries = mutableSetOf<Int>()
    val usedExits = mutableSetOf<Int>()
    val pairs = mutableListOf<SectionPair>()
    for ((i, j, d) in candidates) {
        if (i in usedEntries || j in usedExits) continue
        // Undirected mode draws both ends from the same list, so a point consumed as an
        // entry must not be reusable as an exit.
        if (!directed && (j in usedEntries || i in usedExits)) continue
        usedEntries.add(i)
        usedExits.add(j)
        pairs.add(SectionPair(from[i], to[j], directed, d))
    }
    return pairs
}
