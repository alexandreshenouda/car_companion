package com.carlauncher.companion.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GasStationTest {

    @Test
    fun `FuelType resolution from code and name works correctly`() {
        assertEquals(FuelType.GAZOLE, FuelType.fromCode("gazole"))
        assertEquals(FuelType.GAZOLE, FuelType.fromCode("GAZOLE"))
        assertEquals(FuelType.SP95, FuelType.fromName("SP95"))
        assertEquals(FuelType.SP98, FuelType.fromName("sp98"))
        assertEquals(FuelType.E10, FuelType.fromCode("e10"))
        assertEquals(FuelType.E85, FuelType.fromName("E85"))
        assertEquals(FuelType.GPLC, FuelType.fromCode("gplc"))
        assertNull(FuelType.fromCode("unknown"))
    }

    @Test
    fun `GasStation title distinguishes highway and road stations`() {
        val highwayStation = GasStation(
            id = 1L,
            lat = 48.85,
            lon = 2.35,
            address = "Aire de repos",
            city = "Paris",
            postalCode = "75001",
            pop = "A",
            automate24 = true,
            prices = mapOf(FuelType.GAZOLE to 1.859),
            availableFuels = listOf("Gazole"),
            lastUpdate = "2026-09-04T08:00:00+00:00",
        )
        assertTrue(highwayStation.isHighway)
        assertEquals("Station Autoroute · Paris", highwayStation.title)
        assertEquals("Aire de repos, 75001 Paris", highwayStation.subtitle)

        val roadStation = highwayStation.copy(pop = "R", city = "Dargnies", address = "Rue Joliot Curie", postalCode = "80570")
        assertFalse(roadStation.isHighway)
        assertEquals("Station-service · Dargnies", roadStation.title)
        assertEquals("Rue Joliot Curie, 80570 Dargnies", roadStation.subtitle)
    }

    @Test
    fun `prices snippet highlights selected fuel`() {
        val station = GasStation(
            id = 10L,
            lat = 45.0,
            lon = 3.0,
            address = "Route Nationale",
            city = "Lyon",
            postalCode = "69000",
            pop = "R",
            automate24 = true,
            prices = mapOf(
                FuelType.GAZOLE to 1.789,
                FuelType.SP98 to 1.919,
            ),
            availableFuels = listOf("Gazole", "SP98"),
            lastUpdate = "2026-09-04T10:00:00+00:00",
        )

        val standardSnippet = station.buildPricesSnippetHtml(null)
        assertTrue(standardSnippet.contains("<b>Gazole:</b> 1.789 €"))
        assertTrue(standardSnippet.contains("<b>SP98:</b> 1.919 €"))
        assertFalse(standardSnippet.contains("<font color='#FFC93D'>"))

        val highlightedSnippet = station.buildPricesSnippetHtml(FuelType.GAZOLE)
        assertTrue(highlightedSnippet.contains("<font color='#FFC93D'><b>Gazole: 1.789 €</b></font>"))
        assertTrue(highlightedSnippet.contains("<b>SP98:</b> 1.919 €"))
    }

    @Test
    fun `subdescription contains 24h status and cleaned date`() {
        val station = GasStation(
            id = 10L,
            lat = 45.0,
            lon = 3.0,
            address = "Route Nationale",
            city = "Lyon",
            postalCode = "69000",
            pop = "R",
            automate24 = true,
            prices = emptyMap(),
            availableFuels = emptyList(),
            lastUpdate = "2026-09-04T10:30:00+00:00",
        )
        val subDesc = station.buildSubDescriptionHtml()
        assertTrue(subDesc.contains("24h/24 : <b>Oui</b>"))
        assertTrue(subDesc.contains("MàJ : 2026-09-04 10:30:00"))

        val stationNoAuto = station.copy(automate24 = false, lastUpdate = null)
        val subDescNoAuto = stationNoAuto.buildSubDescriptionHtml()
        assertTrue(subDescNoAuto.contains("24h/24 : Non"))
        assertFalse(subDescNoAuto.contains("MàJ"))
    }

    @Test
    fun `shortName extracts highway rest area name or falls back to address`() {
        val highway = GasStation(
            id = 1L,
            lat = 45.0,
            lon = 6.0,
            address = "Autoroute A43 - Aire du Granier",
            city = "Chignin",
            postalCode = "73800",
            pop = "A",
            automate24 = true,
            prices = mapOf(FuelType.GAZOLE to 1.899),
            availableFuels = listOf("Gazole"),
            lastUpdate = null,
        )
        assertEquals("Aire du Granier", highway.shortName)

        val road = highway.copy(pop = "R", address = "12 Rue de la République")
        assertEquals("12 Rue de la République", road.shortName)
    }

    @Test
    fun `priceFor and formattedPrice return expected values`() {
        val station = GasStation(
            id = 1L,
            lat = 45.0,
            lon = 6.0,
            address = "Station Annecy",
            city = "Annecy",
            postalCode = "74000",
            pop = "R",
            automate24 = true,
            prices = mapOf(
                FuelType.GAZOLE to 1.649,
                FuelType.SP98 to 1.849,
            ),
            availableFuels = listOf("Gazole", "SP98"),
            lastUpdate = null,
        )
        assertEquals(1.649, station.priceFor(FuelType.GAZOLE)!!, 0.001)
        assertEquals(1.649, station.priceFor(null)!!, 0.001) // Defaults to Gazole
        assertEquals(1.849, station.priceFor(FuelType.SP98)!!, 0.001)
        assertNull(station.priceFor(FuelType.E85))

        assertEquals("1.649 €", station.formattedPrice(FuelType.GAZOLE))
        assertEquals("1.849 €", station.formattedPrice(FuelType.SP98))
        assertNull(station.formattedPrice(FuelType.E85))
    }

    @Test
    fun `stations top 5 ordering by price ascending works`() {
        val s1 = GasStation(1L, 45.0, 6.0, "S1", "C1", "74", "R", true, mapOf(FuelType.SP98 to 1.95), emptyList(), null)
        val s2 = GasStation(2L, 45.0, 6.0, "S2", "C2", "74", "R", true, mapOf(FuelType.SP98 to 1.85), emptyList(), null)
        val s3 = GasStation(3L, 45.0, 6.0, "S3", "C3", "74", "R", true, mapOf(FuelType.SP98 to 1.75), emptyList(), null)
        val s4 = GasStation(4L, 45.0, 6.0, "S4", "C4", "74", "R", true, mapOf(FuelType.SP98 to 1.90), emptyList(), null)
        val s5 = GasStation(5L, 45.0, 6.0, "S5", "C5", "74", "R", true, mapOf(FuelType.SP98 to 1.80), emptyList(), null)
        val s6 = GasStation(6L, 45.0, 6.0, "S6", "C6", "74", "R", true, mapOf(FuelType.SP98 to 2.00), emptyList(), null)

        val list = listOf(s1, s2, s3, s4, s5, s6)
        val top5 = list.filter { it.prices.containsKey(FuelType.SP98) }
            .sortedBy { it.prices[FuelType.SP98] }
            .take(5)

        assertEquals(5, top5.size)
        assertEquals(3L, top5[0].id) // 1.75
        assertEquals(5L, top5[1].id) // 1.80
        assertEquals(2L, top5[2].id) // 1.85
        assertEquals(4L, top5[3].id) // 1.90
        assertEquals(1L, top5[4].id) // 1.95
    }

    @Test
    fun `hasEverDownloaded logic returns false when count is 0 and lastUpdate is 0`() {
        fun checkHasEverDownloaded(stationCount: Int, lastUpdateMs: Long): Boolean {
            return stationCount > 0 || lastUpdateMs > 0L
        }

        assertFalse(checkHasEverDownloaded(0, 0L))
        assertTrue(checkHasEverDownloaded(500, 0L))
        assertTrue(checkHasEverDownloaded(0, 1725450000000L))
        assertTrue(checkHasEverDownloaded(9800, 1725450000000L))
    }

    @Test
    fun `daily sync condition triggers only once per epoch day`() {
        val today = java.time.LocalDate.of(2026, 9, 4).toEpochDay()
        val yesterday = java.time.LocalDate.of(2026, 9, 3).toEpochDay()

        fun shouldSync(hasData: Boolean, lastSyncDay: Long, currentDay: Long): Boolean {
            if (!hasData) return false
            return lastSyncDay < currentDay
        }

        // Never downloaded yet -> should not auto sync
        assertFalse(shouldSync(hasData = false, lastSyncDay = 0L, currentDay = today))

        // First open of today when data exists and last sync was yesterday -> should sync
        assertTrue(shouldSync(hasData = true, lastSyncDay = yesterday, currentDay = today))

        // Already synced today -> should not sync again
        assertFalse(shouldSync(hasData = true, lastSyncDay = today, currentDay = today))
    }

    // ── Swiss / TCS additions ─────────────────────────────────────────────────

    @Test
    fun `FuelType TCS codes are mapped correctly`() {
        assertEquals("DIESEL", FuelType.GAZOLE.tcsCode)
        assertEquals("SP95", FuelType.SP95.tcsCode)
        assertEquals("SP98", FuelType.SP98.tcsCode)
        assertEquals("E85", FuelType.E85.tcsCode)
        assertEquals("GPL", FuelType.GPLC.tcsCode)
        assertNull(FuelType.E10.tcsCode) // No TCS equivalent
        assertEquals("DIESEL_PREMIUM", FuelType.DIESEL_PREMIUM.tcsCode)
        assertEquals("ADBLUE", FuelType.ADBLUE.tcsCode)
        assertEquals("CNG", FuelType.CNG.tcsCode)
        assertEquals("HVO100", FuelType.HVO100.tcsCode)
        assertEquals("H2", FuelType.H2.tcsCode)
    }

    @Test
    fun `hasOfficialDbColumn is true only for French fuel types`() {
        assertTrue(FuelType.GAZOLE.hasOfficialDbColumn)
        assertTrue(FuelType.SP95.hasOfficialDbColumn)
        assertTrue(FuelType.SP98.hasOfficialDbColumn)
        assertTrue(FuelType.E10.hasOfficialDbColumn)
        assertTrue(FuelType.E85.hasOfficialDbColumn)
        assertTrue(FuelType.GPLC.hasOfficialDbColumn)
        // TCS-only fuels must NOT have a DB column
        assertFalse(FuelType.DIESEL_PREMIUM.hasOfficialDbColumn)
        assertFalse(FuelType.ADBLUE.hasOfficialDbColumn)
        assertFalse(FuelType.CNG.hasOfficialDbColumn)
        assertFalse(FuelType.HVO100.hasOfficialDbColumn)
        assertFalse(FuelType.H2.hasOfficialDbColumn)
    }

    @Test
    fun `swissSupported includes all fuels with a tcsCode`() {
        val swiss = FuelType.swissSupported
        assertTrue(swiss.contains(FuelType.GAZOLE))
        assertTrue(swiss.contains(FuelType.SP95))
        assertTrue(swiss.contains(FuelType.DIESEL_PREMIUM))
        assertFalse(swiss.contains(FuelType.E10)) // E10 has no TCS equivalent
    }

    @Test
    fun `Swiss cluster title and shortName show point count`() {
        val cluster = GasStation(
            id = 235L,
            lat = 46.20,
            lon = 6.14,
            address = "",
            city = "",
            postalCode = "",
            pop = null,
            automate24 = false,
            prices = mapOf(FuelType.SP95 to 2.02),
            availableFuels = emptyList(),
            lastUpdate = null,
            source = GasStationSource.SWITZERLAND,
            isCluster = true,
            pointCount = 54,
            fiability = "FEW_RECENT_PRICES",
        )
        assertEquals("54 stations", cluster.title)
        assertEquals("54 stations", cluster.shortName)
        assertFalse(cluster.isHighway)
    }

    @Test
    fun `Swiss individual station title uses brand then displayName`() {
        val branded = GasStation(
            id = 1L,
            lat = 46.50,
            lon = 6.31,
            address = "",
            city = "",
            postalCode = "",
            pop = null,
            automate24 = false,
            prices = mapOf(FuelType.SP98 to 2.08),
            availableFuels = listOf("SP98"),
            lastUpdate = null,
            source = GasStationSource.SWITZERLAND,
            isCluster = false,
            brand = "ENI",
            displayName = "Eni Gimel",
            formattedAddress = "Rte D'aubonne 18, 1188 Gimel",
        )
        assertEquals("ENI", branded.title)
        assertEquals("Rte D'aubonne 18, 1188 Gimel", branded.subtitle)
        assertEquals("ENI", branded.shortName)

        val unnamed = branded.copy(brand = null)
        assertEquals("Eni Gimel", unnamed.title)

        val brandUndefined = branded.copy(brand = "UNDEFINED", displayName = "Station Simond")
        // In the parsed model brand would be null for "UNDEFINED" (filtered by repo); shortName falls back to displayName
        assertEquals("UNDEFINED", brandUndefined.title) // direct model test; repo strips it
    }

    @Test
    fun `Swiss subdescription shows fiability and cheapest flag`() {
        val cheap = GasStation(
            id = 427L,
            lat = 46.39,
            lon = 6.22,
            address = "",
            city = "",
            postalCode = "",
            pop = null,
            automate24 = false,
            prices = mapOf(FuelType.SP95 to 1.97),
            availableFuels = emptyList(),
            lastUpdate = null,
            source = GasStationSource.SWITZERLAND,
            isCluster = true,
            pointCount = 16,
            fiability = "FEW_RECENT_PRICES",
            isCheapest = true,
        )
        val sub = cheap.buildSubDescriptionHtml()
        assertTrue(sub.contains("Moins cher"))
        assertTrue(sub.contains("Peu de prix récents"))
    }

    @Test
    fun `Swiss formattedPrice uses CHF currency`() {
        val station = GasStation(
            id = 1L,
            lat = 46.5,
            lon = 6.3,
            address = "",
            city = "",
            postalCode = "",
            pop = null,
            automate24 = false,
            prices = mapOf(FuelType.SP95 to 2.02),
            availableFuels = emptyList(),
            lastUpdate = null,
            source = GasStationSource.SWITZERLAND,
        )
        assertEquals("2.02 CHF", station.formattedPrice(FuelType.SP95))
    }
}

