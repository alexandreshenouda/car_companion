package com.carlauncher.companion.data.cloud

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolylineCodecTest {

    @Test
    fun `known reference example round-trips`() {
        // From Google's own polyline algorithm documentation.
        val points = listOf(38.5 to -120.2, 40.7 to -120.95, 43.252 to -126.453)
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", PolylineCodec.encode(points))
        assertClose(points, PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@"))
    }

    @Test
    fun `empty list round-trips to empty string`() {
        assertEquals("", PolylineCodec.encode(emptyList()))
        assertTrue(PolylineCodec.decode("").isEmpty())
    }

    @Test
    fun `single point round-trips`() {
        val points = listOf(48.8584 to 2.2945)
        assertClose(points, PolylineCodec.decode(PolylineCodec.encode(points)))
    }

    @Test
    fun `negative coordinates round-trip`() {
        val points = listOf(-33.8688 to 151.2093, -33.8700 to 151.2100, -33.8650 to 151.2050)
        assertClose(points, PolylineCodec.decode(PolylineCodec.encode(points)))
    }

    @Test
    fun `a realistic trace round-trips within precision-5 tolerance`() {
        val points = (0 until 500).map { i ->
            (48.8 + i * 0.0001) to (2.3 - i * 0.00015)
        }
        assertClose(points, PolylineCodec.decode(PolylineCodec.encode(points)))
    }

    @Test
    fun `encoding is meaningfully smaller than a plain coordinate list`() {
        val points = (0 until 1000).map { i -> (48.8 + i * 0.0001) to (2.3 - i * 0.00015) }
        val encoded = PolylineCodec.encode(points)
        val plainJsonApprox = points.joinToString(",") { "[${it.first},${it.second}]" }
        assertTrue(encoded.length < plainJsonApprox.length / 3)
    }

    private fun assertClose(expected: List<Pair<Double, Double>>, actual: List<Pair<Double, Double>>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) ->
            assertTrue(abs(e.first - a.first) < 1e-4, "lat ${e.first} vs ${a.first}")
            assertTrue(abs(e.second - a.second) < 1e-4, "lng ${e.second} vs ${a.second}")
        }
    }
}
