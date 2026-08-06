package com.carlauncher.companion.data.repo

import android.content.res.AssetManager
import android.util.Log
import com.carlauncher.companion.data.model.RadarSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "SectionRepository"
private const val ASSET = "radar_sections.json"

/**
 * Loads the precomputed average-speed sections from `assets/radar_sections.json`.
 *
 * Kept separate from [RadarRepository], which pages 25 multi-MB GPX files in per country and
 * also serves the Android Auto alert path. This is one ~190 KB file read once, so it needs
 * none of that machinery.
 */
class SectionRepository(private val assets: AssetManager) {
    private val mutex = Mutex()
    private var cached: List<RadarSection>? = null

    /** Every bundled section. Empty if the asset is missing or unreadable. */
    suspend fun sections(): List<RadarSection> {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: withContext(Dispatchers.IO) { parse() }.also { cached = it }
        }
    }

    private fun parse(): List<RadarSection> = try {
        val json = assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        val array = JSONObject(json).getJSONArray("sections")
        val out = ArrayList<RadarSection>(array.length())
        for (i in 0 until array.length()) {
            out.add(readSection(array.getJSONObject(i)))
        }
        out
    } catch (e: Exception) {
        // A missing or half-written asset should cost the map its section lines, not crash it.
        Log.w(TAG, "Could not read $ASSET; no section lines will be drawn", e)
        emptyList()
    }

    private fun readSection(o: JSONObject): RadarSection {
        val entry = o.getJSONArray("entry")
        val exit = o.getJSONArray("exit")
        return RadarSection(
            country = o.getString("country"),
            directed = o.getBoolean("directed"),
            routed = o.getBoolean("routed"),
            lengthMeters = o.getInt("lengthM"),
            entryLat = entry.getDouble(0),
            entryLon = entry.getDouble(1),
            exitLat = exit.getDouble(0),
            exitLon = exit.getDouble(1),
            line = o.getJSONArray("line").toDoubleArray(),
        )
    }

    private fun JSONArray.toDoubleArray(): DoubleArray =
        DoubleArray(length()) { getDouble(it) }
}
