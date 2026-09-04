package com.carlauncher.companion.data.db

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import com.carlauncher.companion.data.model.FuelType
import com.carlauncher.companion.data.model.GasStation
import com.carlauncher.companion.data.model.GasStationSource
import java.util.Locale

private const val DATABASE_NAME = "gas_stations.db"
private const val DATABASE_VERSION = 1
private const val TABLE_NAME = "gas_stations"

/**
 * High-performance SQLite database helper for gas stations.
 * Uses a compound B-tree index on (lat, lon) to enable sub-millisecond bounding box queries.
 */
class GasStationDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_NAME (
                id INTEGER PRIMARY KEY,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                address TEXT NOT NULL,
                city TEXT NOT NULL,
                postal_code TEXT NOT NULL,
                pop TEXT,
                automate_24 INTEGER NOT NULL DEFAULT 0,
                gazole_prix REAL,
                sp95_prix REAL,
                sp98_prix REAL,
                e10_prix REAL,
                e85_prix REAL,
                gplc_prix REAL,
                available_fuels TEXT NOT NULL DEFAULT '',
                last_update TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_gas_stations_coords ON $TABLE_NAME (lat, lon)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    /**
     * Replaces the entire table with the given list of stations inside a single transaction.
     * Uses a prepared SQLiteStatement for maximum throughput (~150-250ms for ~10k stations).
     */
    fun replaceStations(stations: List<GasStation>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_NAME, null, null)
            val insertSql = """
                INSERT INTO $TABLE_NAME (
                    id, lat, lon, address, city, postal_code, pop, automate_24,
                    gazole_prix, sp95_prix, sp98_prix, e10_prix, e85_prix, gplc_prix,
                    available_fuels, last_update
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            val stmt: SQLiteStatement = db.compileStatement(insertSql)
            for (station in stations) {
                stmt.clearBindings()
                stmt.bindLong(1, station.id)
                stmt.bindDouble(2, station.lat)
                stmt.bindDouble(3, station.lon)
                stmt.bindString(4, station.address)
                stmt.bindString(5, station.city)
                stmt.bindString(6, station.postalCode)
                if (station.pop != null) stmt.bindString(7, station.pop) else stmt.bindNull(7)
                stmt.bindLong(8, if (station.automate24) 1L else 0L)

                bindNullablePrice(stmt, 9, station.prices[FuelType.GAZOLE])
                bindNullablePrice(stmt, 10, station.prices[FuelType.SP95])
                bindNullablePrice(stmt, 11, station.prices[FuelType.SP98])
                bindNullablePrice(stmt, 12, station.prices[FuelType.E10])
                bindNullablePrice(stmt, 13, station.prices[FuelType.E85])
                bindNullablePrice(stmt, 14, station.prices[FuelType.GPLC])

                stmt.bindString(15, station.availableFuels.joinToString(";"))
                if (station.lastUpdate != null) stmt.bindString(16, station.lastUpdate) else stmt.bindNull(16)

                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun bindNullablePrice(stmt: SQLiteStatement, index: Int, price: Double?) {
        if (price != null) {
            stmt.bindDouble(index, price)
        } else {
            stmt.bindNull(index)
        }
    }

    /**
     * Queries gas stations within a bounding box, optionally filtering by fuel type.
     */
    fun stationsForViewport(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        fuelType: FuelType? = null,
        limit: Int = 200,
    ): List<GasStation> {
        val db = readableDatabase
        // Only apply the column filter for fuels that have a price column in this DB.
        // TCS-only fuels (DIESEL_PREMIUM, ADBLUE, CNG, HVO100, H2) have hasOfficialDbColumn=false
        // and would generate a broken "WHERE _prix IS NOT NULL" clause otherwise.
        val fuelFilterClause = if (fuelType != null && fuelType.hasOfficialDbColumn) {
            "AND ${fuelType.code}_prix IS NOT NULL"
        } else {
            ""
        }

        val sql = """
            SELECT id, lat, lon, address, city, postal_code, pop, automate_24,
                   gazole_prix, sp95_prix, sp98_prix, e10_prix, e85_prix, gplc_prix,
                   available_fuels, last_update
            FROM $TABLE_NAME
            WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? $fuelFilterClause
            LIMIT ?
        """.trimIndent()

        val args = arrayOf(
            minLat.toString(),
            maxLat.toString(),
            minLon.toString(),
            maxLon.toString(),
            limit.toString(),
        )

        val result = ArrayList<GasStation>(limit.coerceAtMost(200))
        val cursor: Cursor = db.rawQuery(sql, args)
        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow("id")
            val latIdx = c.getColumnIndexOrThrow("lat")
            val lonIdx = c.getColumnIndexOrThrow("lon")
            val addrIdx = c.getColumnIndexOrThrow("address")
            val cityIdx = c.getColumnIndexOrThrow("city")
            val cpIdx = c.getColumnIndexOrThrow("postal_code")
            val popIdx = c.getColumnIndexOrThrow("pop")
            val autoIdx = c.getColumnIndexOrThrow("automate_24")
            val gazoleIdx = c.getColumnIndexOrThrow("gazole_prix")
            val sp95Idx = c.getColumnIndexOrThrow("sp95_prix")
            val sp98Idx = c.getColumnIndexOrThrow("sp98_prix")
            val e10Idx = c.getColumnIndexOrThrow("e10_prix")
            val e85Idx = c.getColumnIndexOrThrow("e85_prix")
            val gplcIdx = c.getColumnIndexOrThrow("gplc_prix")
            val fuelsIdx = c.getColumnIndexOrThrow("available_fuels")
            val updateIdx = c.getColumnIndexOrThrow("last_update")

            while (c.moveToNext()) {
                val prices = mutableMapOf<FuelType, Double>()
                if (!c.isNull(gazoleIdx)) prices[FuelType.GAZOLE] = c.getDouble(gazoleIdx)
                if (!c.isNull(sp95Idx)) prices[FuelType.SP95] = c.getDouble(sp95Idx)
                if (!c.isNull(sp98Idx)) prices[FuelType.SP98] = c.getDouble(sp98Idx)
                if (!c.isNull(e10Idx)) prices[FuelType.E10] = c.getDouble(e10Idx)
                if (!c.isNull(e85Idx)) prices[FuelType.E85] = c.getDouble(e85Idx)
                if (!c.isNull(gplcIdx)) prices[FuelType.GPLC] = c.getDouble(gplcIdx)

                val availableFuelsStr = c.getString(fuelsIdx)
                val availableFuels = if (availableFuelsStr.isNullOrEmpty()) {
                    emptyList()
                } else {
                    availableFuelsStr.split(";")
                }

                result.add(
                    GasStation(
                        id = c.getLong(idIdx),
                        lat = c.getDouble(latIdx),
                        lon = c.getDouble(lonIdx),
                        address = c.getString(addrIdx) ?: "",
                        city = c.getString(cityIdx) ?: "",
                        postalCode = c.getString(cpIdx) ?: "",
                        pop = if (c.isNull(popIdx)) null else c.getString(popIdx),
                        automate24 = c.getInt(autoIdx) == 1,
                        prices = prices,
                        availableFuels = availableFuels,
                        lastUpdate = if (c.isNull(updateIdx)) null else c.getString(updateIdx),
                    ),
                )
            }
        }
        return result
    }


    /**
     * Queries aggregated spatial clusters of gas stations within a bounding box for low zoom levels.
     * Uses dynamic grid cell step based on the map zoom level to group nearby stations.
     */
    fun clustersForViewport(

         minLat: Double,
         maxLat: Double,
         minLon: Double,
         maxLon: Double,
         zoom: Double,
         fuelType: FuelType? = null,
         limit: Int = 200,
     ): List<GasStation> {
         if (zoom < 5.0) return emptyList()

         val db = readableDatabase
         val fuelFilterClause = if (fuelType != null && fuelType.hasOfficialDbColumn) {
             "AND ${fuelType.code}_prix IS NOT NULL"
         } else {
             ""
         }

         // Target roughly 90px grid cells on screen so cluster badges do not overlap.
         // At zoom Z, world width (360 deg) is 256 * 2^Z pixels.
         val degPerPixel = 360.0 / (256.0 * Math.pow(2.0, zoom.coerceIn(3.0, 14.0)))
         val gridStep = (degPerPixel * 90.0).coerceAtLeast(0.01)
         val stepStr = String.format(Locale.US, "%.6f", gridStep)

         val sql = """
             SELECT COUNT(*) AS pt_count,
                    AVG(lat) AS avg_lat,
                    AVG(lon) AS avg_lon,
                    MIN(gazole_prix) AS min_gazole,
                    MIN(sp95_prix) AS min_sp95,
                    MIN(sp98_prix) AS min_sp98,
                    MIN(e10_prix) AS min_e10,
                    MIN(e85_prix) AS min_e85,
                    MIN(gplc_prix) AS min_gplc,
                    MAX(last_update) AS max_update,
                    CAST(ROUND(lat / $stepStr) AS INTEGER) AS grid_lat,
                    CAST(ROUND(lon / $stepStr) AS INTEGER) AS grid_lon
             FROM $TABLE_NAME
             WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? $fuelFilterClause
             GROUP BY grid_lat, grid_lon
             LIMIT ?
         """.trimIndent()

         val args = arrayOf(
             minLat.toString(),
             maxLat.toString(),
             minLon.toString(),
             maxLon.toString(),
             limit.toString(),
         )

         val result = ArrayList<GasStation>(limit.coerceAtMost(200))
         val cursor: Cursor = db.rawQuery(sql, args)
         cursor.use { c ->
             while (c.moveToNext()) {
                 val count = c.getInt(0)
                 val lat = c.getDouble(1)
                 val lon = c.getDouble(2)
                 val prices = mutableMapOf<FuelType, Double>()
                 if (!c.isNull(3)) prices[FuelType.GAZOLE] = c.getDouble(3)
                 if (!c.isNull(4)) prices[FuelType.SP95] = c.getDouble(4)
                 if (!c.isNull(5)) prices[FuelType.SP98] = c.getDouble(5)
                 if (!c.isNull(6)) prices[FuelType.E10] = c.getDouble(6)
                 if (!c.isNull(7)) prices[FuelType.E85] = c.getDouble(7)
                 if (!c.isNull(8)) prices[FuelType.GPLC] = c.getDouble(8)

                 val maxUpdate = if (c.isNull(9)) null else c.getString(9)
                 val gridLat = c.getLong(10)
                 val gridLon = c.getLong(11)
                 val syntheticId = 1_000_000_000L + Math.abs((gridLat * 31L + gridLon).hashCode().toLong())

                 result.add(
                     GasStation(
                         id = syntheticId,
                         lat = lat,
                         lon = lon,
                         address = "",
                         city = "",
                         postalCode = "",
                         pop = null,
                         automate24 = false,
                         prices = prices,
                         availableFuels = emptyList(),
                         lastUpdate = maxUpdate,
                         source = GasStationSource.FRANCE,
                         isCluster = true,
                         pointCount = count,
                     ),
                 )
             }
         }

         return result
     }

    /** Returns total number of indexed stations. */
    fun stationCount(): Int {

        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null)
        return cursor.use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }
}
