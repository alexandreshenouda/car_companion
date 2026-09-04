package com.carlauncher.companion.data.repo

import android.util.Log
import com.carlauncher.companion.data.model.FuelType
import com.carlauncher.companion.data.model.GasStation
import com.carlauncher.companion.data.model.GasStationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

private const val TAG = "SwissGasStationRepo"

private const val TCS_ENDPOINT =
    "https://europe-west6-tcs-digitalbackend.cloudfunctions.net/benzinGetStationByBbox"

// Switzerland bounding box (rough, WGS84). Used to skip the HTTP call when the
// map viewport does not overlap Switzerland at all.
private const val CH_LAT_MIN = 45.8
private const val CH_LAT_MAX = 47.9
private const val CH_LON_MIN = 5.9
private const val CH_LON_MAX = 10.5

/** Default TCS fuel code used when the user selects "All fuels". */
private const val DEFAULT_TCS_FUEL = "SP95"

/**
 * Live viewport fetch for Swiss gas stations via the TCS benzinGetStationByBbox API.
 *
 * Unlike the French layer (offline SQLite), this repository is stateless and network-only.
 * Results are returned in-memory per viewport call; nothing is persisted to disk.
 *
 * The API returns two kinds of objects in the same response array:
 *  - Clusters (cluster=true) — aggregated groups at low zoom, with a representative price
 *    and point count.
 *  - Individual stations (cluster=false) — full station objects with brand, address, and
 *    optionally a specific fuel price.
 *
 * Both are mapped to the shared [GasStation] model; clusters set [GasStation.isCluster] = true.
 */
class SwissGasStationRepository {

    /**
     * Fetches Swiss gas stations for the given bounding box and zoom level.
     *
     * Returns an empty list without throwing if the viewport does not overlap Switzerland,
     * if the network is unavailable, or if the API returns a non-200 response.
     */
    suspend fun pointsForViewport(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        zoom: Int,
        fuelType: FuelType? = null,
    ): List<GasStation> = withContext(Dispatchers.IO) {
        if (maxLat < CH_LAT_MIN || minLat > CH_LAT_MAX || maxLon < CH_LON_MIN || minLon > CH_LON_MAX) {
            return@withContext emptyList()
        }
        val tcsCode = fuelType?.tcsCode ?: DEFAULT_TCS_FUEL
        val clampedZoom = zoom.coerceIn(8, 18)
        val payload = buildRequestPayload(minLon, minLat, maxLon, maxLat, clampedZoom, tcsCode)
        return@withContext try {
            val json = postJson(payload)
            parseResponse(json, fuelType ?: FuelType.SP95)
        } catch (e: Exception) {
            Log.w(TAG, "Swiss station fetch failed: ${e.message}")
            emptyList()
        }
    }

    private fun buildRequestPayload(
        lonWest: Double,
        latSouth: Double,
        lonEast: Double,
        latNorth: Double,
        zoom: Int,
        tcsCode: String,
    ): String {
        return JSONObject().apply {
            put("zoom", zoom)
            put("pixelRatio", 1)
            put("bbox", JSONArray().apply {
                put(lonWest)
                put(latSouth)
                put(lonEast)
                put(latNorth)
            })
            put("filters", JSONObject().apply {
                put("fuel", tcsCode)
                put("brands", JSONArray())
            })
        }.toString()
    }

    private fun postJson(payload: String): JSONArray {
        val url = URL(TCS_ENDPOINT)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate")
            conn.setRequestProperty("Origin", "https://benzin.tcs.ch")
            conn.setRequestProperty("Referer", "https://benzin.tcs.ch/")
            conn.setRequestProperty("User-Agent", "CarCompanion/1.0 (Android)")
            val bodyBytes = payload.toByteArray(StandardCharsets.UTF_8)
            conn.setRequestProperty("Content-Length", bodyBytes.size.toString())
            conn.outputStream.use { it.write(bodyBytes) }
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                throw IllegalStateException("TCS API returned HTTP $responseCode")
            }
            val isGzip = conn.contentEncoding?.equals("gzip", ignoreCase = true) == true
            val rawStream = if (isGzip) GZIPInputStream(conn.inputStream) else conn.inputStream
            val body = rawStream.use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).readText()
            }
            return JSONArray(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(array: JSONArray, selectedFuel: FuelType): List<GasStation> {
        val result = ArrayList<GasStation>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            parseEntry(obj, selectedFuel)?.let { result.add(it) }
        }
        return result
    }

    private fun parseEntry(obj: JSONObject, selectedFuel: FuelType): GasStation? {
        val lat = obj.optDouble("latitude", Double.NaN)
        val lon = obj.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null

        val isCluster = obj.optBoolean("cluster", false)
        val rawId = obj.opt("id")
        val id: Long = when (rawId) {
            is Number -> rawId.toLong()
            is String -> rawId.hashCode().toLong()
            else -> return null
        }

        val rawPrice = obj.optDouble("price", Double.NaN)
        val prices: Map<FuelType, Double> =
            if (!rawPrice.isNaN()) mapOf(selectedFuel to rawPrice) else emptyMap()

        return if (isCluster) {
            GasStation(
                id = id,
                lat = lat,
                lon = lon,
                address = "",
                city = "",
                postalCode = "",
                pop = null,
                automate24 = false,
                prices = prices,
                availableFuels = emptyList(),
                lastUpdate = null,
                source = GasStationSource.SWITZERLAND,
                isCluster = true,
                pointCount = obj.optInt("pointCount", 0),
                fiability = obj.optString("fiability").takeIf { it.isNotBlank() },
                isCheapest = obj.optBoolean("isCheapest", false),
            )
        } else {
            val fuel = obj.optString("fuel").takeIf { it.isNotBlank() }
            GasStation(
                id = id,
                lat = lat,
                lon = lon,
                address = "",
                city = "",
                postalCode = "",
                pop = null,
                automate24 = false,
                prices = prices,
                availableFuels = if (fuel != null) listOf(fuel) else emptyList(),
                lastUpdate = null,
                source = GasStationSource.SWITZERLAND,
                isCluster = false,
                brand = obj.optString("brand").takeIf { it.isNotBlank() && it != "UNDEFINED" },
                displayName = obj.optString("displayName").takeIf { it.isNotBlank() },
                formattedAddress = obj.optString("formattedAddress").takeIf { it.isNotBlank() },
                fiability = obj.optString("fiability").takeIf { it.isNotBlank() },
                isCheapest = obj.optBoolean("isCheapest", false),
            )
        }
    }
}
