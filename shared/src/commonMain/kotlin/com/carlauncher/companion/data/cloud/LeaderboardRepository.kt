package com.carlauncher.companion.data.cloud

import com.carlauncher.companion.data.cloud.dto.GetLeaderboardParams
import com.carlauncher.companion.data.cloud.dto.LeaderboardEntryRow
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc

/** One ranked row, in the order `get_leaderboard` returned it — rank is the row's position. */
data class LeaderboardEntry(
    val userId: String,
    val username: String,
    val displayName: String?,
    val totalXp: Long,
    val level: Int,
    val loginStreakDays: Int,
    val isSelf: Boolean,
)

/**
 * The XP leaderboard — a read of the `get_leaderboard` RPC (`supabase/schema.sql`). Visibility
 * is entirely server-side, same as [FeedRepository]: the function already filters to rows the
 * caller is allowed to see (their own row, plus friends/everyone per each account's own
 * `leaderboard_visibility`), so there is nothing here to additionally filter or trust.
 */
class LeaderboardRepository(private val provider: SupabaseClientProvider) {

    /** @param scope "friends" or "everyone", matching [CloudPrefsRepository]'s [LeaderboardVisibility]. */
    suspend fun page(scope: String, limit: Int = 50): List<LeaderboardEntry> {
        val client = provider.client ?: return emptyList()
        return runCatching {
            client.postgrest
                .rpc("get_leaderboard", GetLeaderboardParams(scope = scope, limit = limit))
                .decodeList<LeaderboardEntryRow>()
                .map { it.toEntry() }
        }.getOrDefault(emptyList())
    }
}

private fun LeaderboardEntryRow.toEntry() = LeaderboardEntry(
    userId = userId,
    username = username,
    displayName = displayName,
    totalXp = totalXp,
    level = level,
    loginStreakDays = loginStreakDays,
    isSelf = isSelf,
)
