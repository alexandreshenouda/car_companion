package com.carlauncher.companion.data.cloud.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Read-shapes for [com.carlauncher.companion.data.cloud.CloudRestoreManager] — separate from
 * the push-side rows in `CloudRows.kt` because a restore reads back columns (`id`,
 * `created_at`, `updated_at`, visibility) that a push never sends. */

@Serializable
data class CarRestoreRow(
    val id: String,
    val name: String,
    val brand: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val details: String? = null,
    @SerialName("odometer_km") val odometerKm: Double? = null,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("is_shared") val isShared: Boolean = false,
    @SerialName("photo_updated_at") val photoUpdatedAt: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class CarModificationRestoreRow(
    val title: String,
    val category: String? = null,
    @SerialName("installed_at") val installedAt: String,
    val cost: Double? = null,
    val notes: String? = null,
)

@Serializable
data class EventRestoreRow(
    val id: String,
    @SerialName("car_id") val carId: String? = null,
    val title: String,
    val type: String,
    @SerialName("start_ts") val startTs: String,
    @SerialName("end_ts") val endTs: String,
    @SerialName("location_label") val locationLabel: String? = null,
    val notes: String? = null,
    @SerialName("points_source") val pointsSource: String,
    @SerialName("is_shared") val isShared: Boolean = false,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class EventTrackRestoreRow(
    @SerialName("encoded_polyline") val encodedPolyline: String,
    @SerialName("speeds_kmh") val speedsKmh: List<Int>,
    @SerialName("time_offsets_s") val timeOffsetsS: List<Int>,
)

/** Just the polyline column — for a feed card's route sketch, which needs neither the
 * per-point speeds nor the parent `events` row that [EventTrackRestoreRow]/`getSharedEvent`
 * pull in. */
@Serializable
data class EventTrackPolylineRow(@SerialName("encoded_polyline") val encodedPolyline: String)

@Serializable
data class ProfileRestoreRow(
    val age: Int? = null,
    val city: String? = null,
    @SerialName("department_codes") val departmentCodes: List<String> = emptyList(),
    val visibility: String = "private",
    @SerialName("feed_scope") val feedScope: String = "friends",
)

@Serializable
data class TrophyUnlockRestoreRow(
    @SerialName("trophy_id") val trophyId: String,
    @SerialName("unlocked_at") val unlockedAt: String,
)
