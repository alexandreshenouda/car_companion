package radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIN = 300.0
private const val MAX = 30_000.0

class SectionPairingTest {

    /**
     * The regression this whole algorithm exists for, taken from real FRANCE.gpx data.
     *
     * A bidirectional section control puts the opposite carriageway's exit 9 m from an
     * entry. Nearest-neighbour and mutual-nearest both pick that 9 m decoy. Only the
     * distance floor rejects it in favour of the real 2.4 km partner.
     */
    @Test
    fun `rejects the opposite carriageway decoy in favour of the real partner`() {
        val points = listOf(
            Waypoint(47.995185, 1.881220, "Radar Troncon Debut FR"), // A entry
            Waypoint(47.995150, 1.881110, "Radar Troncon Fin FR"),   // B exit, 9 m from A entry
            Waypoint(48.016970, 1.877440, "Radar Troncon Debut FR"), // B entry
            Waypoint(48.016900, 1.877600, "Radar Troncon Fin FR"),   // A exit, ~2.4 km away
        )

        val pairs = pairSections(points, MIN, MAX)

        assertEquals(2, pairs.size)
        assertTrue(
            "every pair must be a real section, not a 9 m opposite-carriageway artefact",
            pairs.all { it.straightMeters > 1_000.0 },
        )
        assertTrue("all pairs are directed", pairs.all { it.directed })
    }

    @Test
    fun `drops an entry whose only candidate is beyond the cap`() {
        val points = listOf(
            Waypoint(48.0, 2.0, "Radar Troncon Debut FR"),
            Waypoint(49.0, 3.0, "Radar Troncon Fin FR"), // ~130 km away
        )

        assertEquals(emptyList<SectionPair>(), pairSections(points, MIN, MAX))
    }

    @Test
    fun `drops a pair closer together than the floor`() {
        val points = listOf(
            Waypoint(48.00000, 2.00000, "Radar Troncon Debut FR"),
            Waypoint(48.00050, 2.00000, "Radar Troncon Fin FR"), // ~56 m
        )

        assertEquals(emptyList<SectionPair>(), pairSections(points, MIN, MAX))
    }

    @Test
    fun `pairs undifferentiated points and never reuses one`() {
        // No Debut/Fin labels, as in PORTUGAL.gpx and NORVEGE.gpx.
        val points = listOf(
            Waypoint(39.0000, -8.0000, "Radar PT Troncon"),
            Waypoint(39.0450, -8.0000, "Radar PT Troncon"), // ~5 km from the first
            Waypoint(40.0000, -8.0000, "Radar PT Troncon"),
            Waypoint(40.0450, -8.0000, "Radar PT Troncon"), // ~5 km from the third
        )

        val pairs = pairSections(points, MIN, MAX)

        assertEquals(2, pairs.size)
        assertTrue("undirected pairs are flagged as such", pairs.none { it.directed })
        val used = pairs.flatMap { listOf(it.entry, it.exit) }
        assertEquals("no waypoint is used twice", used.size, used.toSet().size)
    }

    @Test
    fun `greedy assignment prefers the closer of two competing partners`() {
        val points = listOf(
            Waypoint(48.0000, 2.0000, "Radar Troncon Debut FR"),
            Waypoint(48.0450, 2.0000, "Radar Troncon Fin FR"), // ~5 km
            Waypoint(48.0900, 2.0000, "Radar Troncon Fin FR"), // ~10 km
        )

        val pairs = pairSections(points, MIN, MAX)

        assertEquals(1, pairs.size)
        assertEquals(48.0450, pairs[0].exit.lat, 1e-9)
    }

    @Test
    fun `returns nothing for an empty input`() {
        assertEquals(emptyList<SectionPair>(), pairSections(emptyList(), MIN, MAX))
    }
}
