package com.carlauncher.companion.data.cloud.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** RPC parameter shape for `get_leaderboard` — see `supabase/schema.sql`. */
@Serializable
data class GetLeaderboardParams(
    @SerialName("p_scope") val scope: String? = null,
    @SerialName("p_limit") val limit: Int = 50,
)

/** One ranked row of `get_leaderboard`. Rank itself isn't a column — the RPC already returns
 * rows ordered by `total_xp desc`, so rank is just the row's position in the decoded list. */
@Serializable
data class LeaderboardEntryRow(
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("total_xp") val totalXp: Long,
    val level: Int,
    @SerialName("login_streak_days") val loginStreakDays: Int,
    @SerialName("is_self") val isSelf: Boolean,
)
