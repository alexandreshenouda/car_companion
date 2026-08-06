package com.carlauncher.companion.data.repo

import com.carlauncher.companion.data.cloud.PlatformContext
import com.carlauncher.companion.data.cloud.readBundledAsset
import com.carlauncher.companion.util.haversineKm
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Maps a GPS coordinate to a French département (INSEE code), offline.
 *
 * **This is a nearest-centroid approximation, not a polygon test.** Départements average
 * ~60 km across, so a point well inside one resolves correctly, but a point within a few
 * kilometres of a border — or anywhere in the Paris petite couronne, where 75/92/93/94
 * are only a few kilometres apart — can land on the neighbour. That is acceptable for a
 * trophy counter and explicitly not a record of where the car legally was.
 *
 * Centroids are bundled as `departments_centroids.json` (101 entries, see [readBundledAsset])
 * and parsed once, lazily, the same way `RadarRepository` (dev flavor, Android-only) handles
 * its bundled GPX.
 */
class DepartmentLocator(private val context: PlatformContext) {

    @Serializable
    private data class Centroid(val code: String, val lat: Double, val lng: Double)

    private val centroids: List<Centroid> by lazy {
        val json = readBundledAsset(context, ASSET_NAME)
        Json.decodeFromString<List<Centroid>>(json)
    }

    /** INSEE code of the closest département, or null if the point is nowhere near France. */
    fun codeAt(lat: Double, lng: Double): String? {
        var bestCode: String? = null
        var bestKm = MAX_MATCH_KM
        for (centroid in centroids) {
            val km = haversineKm(lat, lng, centroid.lat, centroid.lng)
            if (km < bestKm) {
                bestKm = km
                bestCode = centroid.code
            }
        }
        return bestCode
    }

    private companion object {
        const val ASSET_NAME = "departments_centroids.json"

        /**
         * Beyond this the nearest centroid is meaningless — it stops a drive through
         * Belgium or Spain from being credited to the closest French département.
         */
        const val MAX_MATCH_KM = 120.0
    }
}
