package com.carlauncher.companion.ui.leaderboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.carlauncher.companion.R
import com.carlauncher.companion.data.cloud.LeaderboardEntry
import com.carlauncher.companion.data.cloud.LeaderboardRepository
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.common.NeonSegmentedSelector
import com.carlauncher.companion.ui.theme.AccentTrophy

private enum class LeaderboardScope(val wire: String) { FRIENDS("friends"), EVERYONE("everyone") }

/**
 * Ranked by total XP — a client-side rendering of `get_leaderboard`'s RPC output, same shape as
 * [com.carlauncher.companion.ui.feed.FeedScreen]. Visibility already happened server-side (each
 * row's account chose to appear here via its own `leaderboard_visibility`), so there is nothing
 * left to filter or trust here.
 */
@Composable
fun LeaderboardScreen(
    leaderboardRepository: LeaderboardRepository,
    modifier: Modifier = Modifier,
) {
    val accent = AccentTrophy
    var leaderboardScope by remember { mutableStateOf(LeaderboardScope.FRIENDS) }
    var entries by remember { mutableStateOf<List<LeaderboardEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(leaderboardScope) {
        loading = true
        entries = leaderboardRepository.page(leaderboardScope.wire)
        loading = false
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        NeonSegmentedSelector(
            options = LeaderboardScope.entries,
            selected = leaderboardScope,
            label = {
                stringResource(
                    when (it) {
                        LeaderboardScope.FRIENDS -> R.string.leaderboard_scope_friends_label
                        LeaderboardScope.EVERYONE -> R.string.leaderboard_scope_everyone_label
                    },
                )
            },
            onSelect = { leaderboardScope = it },
            accent = accent,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        when {
            loading -> Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = accent)
            }
            entries.isEmpty() -> Text(
                stringResource(R.string.leaderboard_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(entries, key = { _, entry -> entry.userId }) { index, entry ->
                    LeaderboardRow(rank = index + 1, entry = entry, accent = accent)
                }
            }
        }
    }
}

@Composable
private fun LeaderboardRow(rank: Int, entry: LeaderboardEntry, accent: Color) {
    NeonCard(
        accent = accent,
        modifier = Modifier.fillMaxWidth(),
        glow = false,
        topBar = false,
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RankBadge(rank, accent, isSelf = entry.isSelf)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.displayName?.takeIf { it.isNotBlank() } ?: entry.username,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.profile_level_format, entry.level),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.loginStreakDays > 0) {
                NeonPill(
                    text = pluralStringResource(R.plurals.profile_streak_days, entry.loginStreakDays, entry.loginStreakDays),
                    accent = accent,
                    leading = { Icon(Icons.Filled.LocalFireDepartment, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp)) },
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                stringResource(R.string.leaderboard_xp_format, entry.totalXp),
                style = MaterialTheme.typography.titleMedium,
                color = accent,
            )
        }
    }
}

@Composable
private fun RankBadge(rank: Int, accent: Color, isSelf: Boolean) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (isSelf) accent else MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            "#$rank",
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelf) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
