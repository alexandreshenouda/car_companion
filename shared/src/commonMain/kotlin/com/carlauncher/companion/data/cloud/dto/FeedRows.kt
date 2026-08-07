package com.carlauncher.companion.data.cloud.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** RPC parameter shape for `get_feed` — see `supabase/schema.sql`. */
@Serializable
data class GetFeedParams(
    @SerialName("p_scope") val scope: String? = null,
    @SerialName("p_before") val before: String? = null,
    @SerialName("p_limit") val limit: Int = 30,
)

/** One row of `get_feed` — a single activity card's worth of pre-joined data. Aggregates
 * (`distanceKm`/`maxSpeedKmh`) are only meaningful for `kind == "event_shared"`; `modCount`
 * only for `kind in ("car_added", "car_shared")`. */
@Serializable
data class FeedActivityRow(
    @SerialName("activity_id") val activityId: String,
    @SerialName("actor_id") val actorId: String,
    @SerialName("actor_name") val actorUsername: String,
    @SerialName("actor_display") val actorDisplayName: String? = null,
    val kind: String,
    @SerialName("subject_id") val subjectId: String? = null,
    @SerialName("subject_key") val subjectKey: String? = null,
    @SerialName("created_at") val createdAt: String,
    val title: String? = null,
    val subtitle: String? = null,
    @SerialName("distance_km") val distanceKm: Double = 0.0,
    @SerialName("max_speed_kmh") val maxSpeedKmh: Int = 0,
    @SerialName("mod_count") val modCount: Int = 0,
    @SerialName("photo_updated_at") val photoUpdatedAt: String? = null,
)

@Serializable
data class GetPublicProfileParams(@SerialName("p_user_id") val userId: String)

/** `get_public_profile`'s row — already reduced server-side to whatever that person chose to
 * expose; nullable fields here mean "not shared with you", not "not decoded". */
@Serializable
data class PublicProfileRow(
    @SerialName("user_id") val userId: String,
    @SerialName("user_name") val username: String,
    @SerialName("display_name") val displayName: String? = null,
    val city: String? = null,
    @SerialName("department_codes") val departmentCodes: List<String> = emptyList(),
    @SerialName("share_garage") val shareGarage: Boolean = false,
    @SerialName("share_trophies") val shareTrophies: Boolean = false,
    @SerialName("is_friend") val isFriend: Boolean = false,
    @SerialName("friend_state") val friendState: String = "none",
)
