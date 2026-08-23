package com.carlauncher.companion.data.cloud.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for `supabase/schema.sql`. Kept separate from the Room entities on purpose —
 * the local and remote shapes diverge deliberately (no `photoPath`, ids are `String` locally
 * but must be UUID-shaped for Postgres, snake_case columns, etc.), and coupling them would
 * make either side's schema hostage to the other's.
 */

@Serializable
data class CarRow(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    val name: String,
    val brand: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val details: String? = null,
    @SerialName("odometer_km") val odometerKm: Double? = null,
    // No `= false` default, deliberately: `cars.is_favorite`/`is_shared` are NOT NULL WITH a
    // database default. kotlinx.serialization omits a field from its JSON output whenever the
    // value equals the field's declared Kotlin default — fine for a nullable column (a missing
    // key and an explicit null mean the same thing), but not here: PostgREST's batch upsert
    // needs one uniform column list across every row in the array, and for whichever rows
    // happened to omit the key, it fills the gap with SQL NULL rather than falling back to the
    // column's own DEFAULT — which then fails the NOT NULL constraint. Losing the default
    // forces this field to serialize on every row, every time, so the key is never absent.
    @SerialName("is_favorite") val isFavorite: Boolean,
    @SerialName("is_shared") val isShared: Boolean,
    @SerialName("photo_updated_at") val photoUpdatedAt: String? = null,
)

@Serializable
data class CarModificationRow(
    val id: String,
    @SerialName("car_id") val carId: String,
    @SerialName("owner_id") val ownerId: String,
    val title: String,
    val category: String? = null,
    @SerialName("installed_at") val installedAtIso: String,
    val cost: Double? = null,
    val notes: String? = null,
)

@Serializable
data class EventRow(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("car_id") val carId: String? = null,
    val title: String,
    val type: String,
    @SerialName("start_ts") val startTsIso: String,
    @SerialName("end_ts") val endTsIso: String,
    @SerialName("location_label") val locationLabel: String? = null,
    val notes: String? = null,
    @SerialName("points_source") val pointsSource: String,
    @SerialName("distance_km") val distanceKm: Double,
    @SerialName("max_speed_kmh") val maxSpeedKmh: Int,
    @SerialName("moving_seconds") val movingSeconds: Long,
    @SerialName("point_count") val pointCount: Int,
    // No `= false` default — see the identical comment on `CarRow.isFavorite`.
    @SerialName("is_shared") val isShared: Boolean,
)

@Serializable
data class EventTrackRow(
    @SerialName("event_id") val eventId: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("encoded_polyline") val encodedPolyline: String,
    @SerialName("speeds_kmh") val speedsKmh: List<Int>,
    @SerialName("time_offsets_s") val timeOffsetsS: List<Int>,
)

@Serializable
data class TrophyUnlockRow(
    @SerialName("owner_id") val ownerId: String,
    @SerialName("trophy_id") val trophyId: String,
    @SerialName("unlocked_at") val unlockedAtIso: String,
)

@Serializable
data class ProfileUpdateRow(
    val age: Int? = null,
    val city: String? = null,
    @SerialName("department_codes") val departmentCodes: List<String> = emptyList(),
)

@Serializable
data class VisibilityUpdateRow(
    val visibility: String,
    @SerialName("feed_scope") val feedScope: String,
    // No Kotlin defaults on any of these, by convention for every push-side row in this file:
    // an omitted field can silently turn into something other than what was intended once
    // PostgREST is involved. See the identical reasoning on `CarRow.isFavorite`.
    @SerialName("share_profile") val shareProfile: Boolean,
    @SerialName("share_garage") val shareGarage: Boolean,
    @SerialName("share_trophies") val shareTrophies: Boolean,
    // XP/leaderboard fields ride along on this same "core account settings, pushed every sync
    // run" row rather than getting their own — they're small, non-sensitive, always-current
    // metadata, same category as visibility/feed_scope above.
    @SerialName("total_xp") val totalXp: Long,
    val level: Int,
    @SerialName("login_streak_days") val loginStreakDays: Int,
    @SerialName("leaderboard_visibility") val leaderboardVisibility: String,
)

/** One page of a chunked, end-to-end encrypted backup. See `CryptoBox` for the encryption. */
@Serializable
data class PrivateBackupRow(
    @SerialName("owner_id") val ownerId: String,
    val kind: String,
    @SerialName("chunk_index") val chunkIndex: Int,
    val ciphertext: String,
    val nonce: String,
)

@Serializable
data class IdOnlyRow(val id: String)
