package com.carlauncher.companion.data.cloud.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** RPC parameter shapes — see `supabase/schema.sql` for the matching function signatures. */

@Serializable
data class UsernameParam(@SerialName("p_username") val username: String)

@Serializable
data class RespondFriendRequestParams(
    @SerialName("p_requester") val requesterId: String,
    @SerialName("p_accept") val accept: Boolean,
)

@Serializable
data class BlockUserParams(@SerialName("p_user") val userId: String)

@Serializable
data class ReportCarParams(
    @SerialName("p_car_id") val carId: String,
    @SerialName("p_reason") val reason: String? = null,
)

/** One row of `find_user_by_username` — at most one, since it's an exact-match lookup. */
@Serializable
data class FoundUserRow(
    @SerialName("user_id") val userId: String,
    @SerialName("user_name") val username: String,
    @SerialName("user_display_name") val displayName: String? = null,
)

/** One row of `get_friends` — a friend, or a pending request in either direction. */
@Serializable
data class FriendRow(
    @SerialName("other_id") val otherId: String,
    @SerialName("other_username") val otherUsername: String,
    @SerialName("other_display_name") val otherDisplayName: String? = null,
    val status: String,
    val direction: String,
)
