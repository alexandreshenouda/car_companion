package com.carlauncher.companion.data.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.JsonReader
import android.util.JsonToken
import com.carlauncher.companion.data.db.GasStationDatabase
import com.carlauncher.companion.data.model.FuelType
import com.carlauncher.companion.data.model.GasStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import android.widget.Toast
import com.carlauncher.companion.R
import kotlinx.coroutines.sync.Mutex
import java.time.LocalDate
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

private const val PREFS_NAME = "gas_stations_prefs"
private const val KEY_LAST_UPDATE_MS = "last_update_ms"
private const val KEY_STATION_COUNT = "station_count"
private const val KEY_LAST_SYNC_EPOCH_DAY = "last_sync_epoch_day"

private const val PRIMARY_GEOJSON_URL =
    "https://data.economie.gouv.fr/api/explore/v2.1/catalog/datasets/prix-des-carburants-en-france-flux-instantane-v2/exports/geojson"
private const val FALLBACK_GEOJSON_URL =
    "https://www.data.gouv.fr/api/1/datasets/r/c465b7f9-f2d7-4e32-a575-d9d69494d112"

sealed interface GasStationUpdateState {
    data object Idle : GasStationUpdateState
    data object Downloading : GasStationUpdateState
    data class Indexing(val count: Int) : GasStationUpdateState
    data class Success(val stationCount: Int, val timestamp: Long) : GasStationUpdateState
    data class Error(val message: String) : GasStationUpdateState
}

/**
 * Repository managing gas stations: streaming download from data.gouv.fr, indexing in SQLite,
 * and viewport spatial queries.
 */
class GasStationRepository(
    private val context: Context,
    private val database: GasStationDatabase = GasStationDatabase(context),
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _updateState = MutableStateFlow<GasStationUpdateState>(GasStationUpdateState.Idle)
    val updateState: StateFlow<GasStationUpdateState> = _updateState.asStateFlow()

    private val _lastUpdateEpochMs = MutableStateFlow(prefs.getLong(KEY_LAST_UPDATE_MS, 0L))
    val lastUpdateEpochMs: StateFlow<Long> = _lastUpdateEpochMs.asStateFlow()

    private val _stationCount = MutableStateFlow(
        prefs.getInt(KEY_STATION_COUNT, database.stationCount()),
    )
    val stationCount: StateFlow<Int> = _stationCount.asStateFlow()

    private val dailySyncMutex = Mutex()

    /**
     * Returns true if gas station data has ever been downloaded successfully.
     */
    fun hasEverDownloaded(): Boolean {
        return _stationCount.value > 0 || prefs.getLong(KEY_LAST_UPDATE_MS, 0L) > 0L
    }

    /**
     * Checks if data should be refreshed automatically for the day.
     * Only triggers if data has previously been downloaded at least once and hasn't been synced today.
     * Displays a Toast upon successful update.
     */
    suspend fun checkDailySync(): Boolean = withContext(Dispatchers.IO) {
        if (!dailySyncMutex.tryLock()) return@withContext false
        try {
            if (!hasEverDownloaded()) return@withContext false

            val today = LocalDate.now().toEpochDay()
            val lastSyncDay = prefs.getLong(KEY_LAST_SYNC_EPOCH_DAY, 0L)
            if (lastSyncDay >= today) return@withContext false

            val result = updateData()
            if (result.isSuccess) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        R.string.gas_station_toast_updated,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                true
            } else {
                false
            }
        } finally {
            dailySyncMutex.unlock()
        }
    }

    /**
     * Queries gas stations within a bounding box, optionally filtering by fuel type.
     */
    suspend fun pointsForViewport(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        fuelType: FuelType? = null,
        limit: Int = 200,
    ): List<GasStation> = withContext(Dispatchers.IO) {
        database.stationsForViewport(minLat, maxLat, minLon, maxLon, fuelType, limit)
    }

    /**
     * Downloads the GeoJSON dataset and indexes it into the local SQLite database.
     */
    suspend fun updateData(): Result<Int> = withContext(Dispatchers.IO) {
        if (_updateState.value is GasStationUpdateState.Downloading ||
            _updateState.value is GasStationUpdateState.Indexing
        ) {
            return@withContext Result.failure(IllegalStateException("Update already in progress"))
        }

        _updateState.value = GasStationUpdateState.Downloading

        try {
            val stations = downloadAndParse()
            _updateState.value = GasStationUpdateState.Indexing(stations.size)

            database.replaceStations(stations)

            val now = System.currentTimeMillis()
            val count = stations.size
            val today = LocalDate.now().toEpochDay()

            prefs.edit()
                .putLong(KEY_LAST_UPDATE_MS, now)
                .putInt(KEY_STATION_COUNT, count)
                .putLong(KEY_LAST_SYNC_EPOCH_DAY, today)
                .apply()

            _lastUpdateEpochMs.value = now
            _stationCount.value = count
            _updateState.value = GasStationUpdateState.Success(count, now)
            Result.success(count)
        } catch (e: Exception) {
            val errorMsg = e.localizedMessage ?: e.message ?: "Unknown error"
            _updateState.value = GasStationUpdateState.Error(errorMsg)
            Result.failure(e)
        }
    }

    private fun downloadAndParse(): List<GasStation> {
        return runCatching { openConnectionAndParse(PRIMARY_GEOJSON_URL) }
            .getOrElse { openConnectionAndParse(FALLBACK_GEOJSON_URL) }
    }

    private fun openConnectionAndParse(urlStr: String): List<GasStation> {
        var currentUrl = urlStr
        var redirects = 0
        var conn: HttpURLConnection? = null

        while (redirects < 5) {
            val url = URL(currentUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "CarCompanion/1.0 (Android)")
                setRequestProperty("Accept-Encoding", "gzip")
            }
            conn = connection

            val responseCode = connection.responseCode
            if (responseCode in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw IllegalStateException("Redirect without Location header")
                currentUrl = if (location.startsWith("http")) location else URL(url, location).toString()
                connection.disconnect()
                redirects++
            } else if (responseCode == 200) {
                break
            } else {
                connection.disconnect()
                throw IllegalStateException("HTTP error $responseCode from $currentUrl")
            }
        }

        val finalConn = conn ?: throw IllegalStateException("Failed to connect")
        try {
            val isGzip = finalConn.contentEncoding?.equals("gzip", ignoreCase = true) == true
            val inputStream: InputStream = if (isGzip) {
                GZIPInputStream(finalConn.inputStream)
            } else {
                finalConn.inputStream
            }
            return parseGeoJsonStream(inputStream)
        } finally {
            finalConn.disconnect()
        }
    }

    private fun parseGeoJsonStream(inputStream: InputStream): List<GasStation> {
        val stations = ArrayList<GasStation>(10_000)
        JsonReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name == "features") {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val station = parseFeature(reader)
                        if (station != null) {
                            stations.add(station)
                        }
                    }
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
        }
        return stations
    }

    private fun parseFeature(reader: JsonReader): GasStation? {
        reader.beginObject()
        var lon = 0.0
        var lat = 0.0
        var id = 0L
        var address = ""
        var city = ""
        var cp = ""
        var pop: String? = null
        var automate24 = false
        val prices = mutableMapOf<FuelType, Double>()
        val availableFuels = mutableListOf<String>()
        var lastUpdate: String? = null

        while (reader.hasNext()) {
            when (reader.nextName()) {
                "geometry" -> {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                    } else {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            if (reader.nextName() == "coordinates") {
                                if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                                    reader.beginArray()
                                    if (reader.hasNext()) lon = readDouble(reader)
                                    if (reader.hasNext()) lat = readDouble(reader)
                                    while (reader.hasNext()) reader.skipValue()
                                    reader.endArray()
                                } else {
                                    reader.skipValue()
                                }
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                }
                "properties" -> {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                    } else {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val propName = reader.nextName()
                            if (reader.peek() == JsonToken.NULL) {
                                reader.nextNull()
                                continue
                            }
                            when (propName) {
                                "id" -> id = readLong(reader)
                                "adresse" -> address = readString(reader)
                                "ville" -> city = readString(reader)
                                "cp" -> cp = readString(reader)
                                "pop" -> pop = readString(reader)
                                "horaires_automate_24_24" -> {
                                    automate24 = readString(reader).equals("Oui", ignoreCase = true)
                                }
                                "gazole_prix" -> readDoubleOrNull(reader)?.let { prices[FuelType.GAZOLE] = it }
                                "sp95_prix" -> readDoubleOrNull(reader)?.let { prices[FuelType.SP95] = it }
                                "sp98_prix" -> readDoubleOrNull(reader)?.let { prices[FuelType.SP98] = it }
                                "e10_prix" -> readDoubleOrNull(reader)?.let { prices[FuelType.E10] = it }
                                "e85_prix" -> readDoubleOrNull(reader)?.let { prices[FuelType.E85] = it }
                                "gplc_prix" -> readDoubleOrNull(reader)?.let { prices[FuelType.GPLC] = it }
                                "gazole_maj", "sp95_maj", "sp98_maj", "e10_maj", "e85_maj", "gplc_maj" -> {
                                    val dateStr = readString(reader)
                                    if (lastUpdate == null && dateStr.isNotBlank()) {
                                        lastUpdate = dateStr
                                    }
                                }
                                "carburants_disponibles" -> {
                                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                                        reader.beginArray()
                                        while (reader.hasNext()) {
                                            availableFuels.add(readString(reader))
                                        }
                                        reader.endArray()
                                    } else {
                                        reader.skipValue()
                                    }
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        // Coordinate sanity check: France bounding box approx [41, 52] lat, [-6, 10] lon, plus DOM-TOM
        if (lat == 0.0 && lon == 0.0) return null
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null

        return GasStation(
            id = id,
            lat = lat,
            lon = lon,
            address = address,
            city = city,
            postalCode = cp,
            pop = pop,
            automate24 = automate24,
            prices = prices,
            availableFuels = availableFuels,
            lastUpdate = lastUpdate,
        )
    }

    private fun readDouble(reader: JsonReader): Double {
        return when (reader.peek()) {
            JsonToken.NUMBER -> reader.nextDouble()
            JsonToken.STRING -> reader.nextString().toDoubleOrNull() ?: 0.0
            else -> {
                reader.skipValue()
                0.0
            }
        }
    }

    private fun readDoubleOrNull(reader: JsonReader): Double? {
        return when (reader.peek()) {
            JsonToken.NUMBER -> reader.nextDouble()
            JsonToken.STRING -> reader.nextString().toDoubleOrNull()
            JsonToken.NULL -> {
                reader.nextNull()
                null
            }
            else -> {
                reader.skipValue()
                null
            }
        }
    }

    private fun readLong(reader: JsonReader): Long {
        return when (reader.peek()) {
            JsonToken.NUMBER -> reader.nextLong()
            JsonToken.STRING -> reader.nextString().toLongOrNull() ?: 0L
            else -> {
                reader.skipValue()
                0L
            }
        }
    }

    private fun readString(reader: JsonReader): String {
        return when (reader.peek()) {
            JsonToken.STRING -> reader.nextString().trim()
            JsonToken.NUMBER -> reader.nextString().trim()
            JsonToken.BOOLEAN -> reader.nextBoolean().toString()
            else -> {
                reader.skipValue()
                ""
            }
        }
    }
}
