package com.carlauncher.companion.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlauncher.companion.R
import com.carlauncher.companion.data.model.HistoryRange
import com.carlauncher.companion.data.model.TrackStats
import com.carlauncher.companion.data.repo.DeviceRepository
import com.carlauncher.companion.data.repo.TrackRepository
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.common.RangeSelector
import com.carlauncher.companion.ui.common.SectionLabel
import com.carlauncher.companion.ui.common.SpeedZoneCard
import com.carlauncher.companion.ui.common.StatTile
import com.carlauncher.companion.util.formatAbsolute
import com.carlauncher.companion.util.formatDuration
import kotlinx.coroutines.launch

@Composable
fun StatsScreen(
    deviceId: String,
    trackRepository: TrackRepository,
    deviceRepository: DeviceRepository,
    onShare: (HistoryRange) -> Unit,
) {
    val range by deviceRepository.observeSelectedRange().collectAsStateWithLifecycle(initialValue = HistoryRange.LAST_7_DAYS)
    var stats by remember { mutableStateOf(TrackStats.EMPTY) }
    var isSyncing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        stats = trackRepository.statsInRange(deviceId, range)
    }

    LaunchedEffect(deviceId, range) { reload() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RangeSelector(
                selected = range,
                onSelect = { scope.launch { deviceRepository.selectRange(it) } },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { onShare(range) }) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.map_share_trip_content_description), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(14.dp))

        NeonPill(
            text = if (isSyncing) stringResource(R.string.history_syncing) else stringResource(R.string.history_sync_now),
            accent = MaterialTheme.colorScheme.secondary,
            onClick = {
                if (!isSyncing) {
                    scope.launch {
                        isSyncing = true
                        trackRepository.syncFullHistory(deviceId)
                        reload()
                        isSyncing = false
                    }
                }
            },
            leading = {
                if (isSyncing) {
                    CircularProgressIndicator(
                        Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                } else {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            },
        )
        Spacer(Modifier.height(18.dp))

        if (stats.pointCount == 0) {
            Text(
                stringResource(R.string.stats_empty_state),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Units live in the label so the value stays a big, unbroken numeral.
            val tiles = listOf(
                stringResource(R.string.stats_tile_max_speed) to "${stats.maxSpeedKmh}",
                stringResource(R.string.stats_tile_avg_speed) to "%.0f".format(stats.avgSpeedKmh),
                stringResource(R.string.stats_tile_median_speed) to "%.0f".format(stats.medianSpeedKmh),
                stringResource(R.string.stats_tile_distance) to "%.1f".format(stats.distanceKm),
                stringResource(R.string.stats_tile_moving_time) to formatDuration(stats.movingTimeSeconds),
                stringResource(R.string.stats_tile_points) to "${stats.pointCount}",
            )
            // Rotating accents rather than six identical tiles — the grid should read as
            // an instrument cluster, not a spreadsheet.
            val accents = listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.secondary,
                MaterialTheme.colorScheme.tertiary,
            )
            // Six fixed tiles, so a plain Row grid rather than a LazyVerticalGrid: the
            // lazy variant can't share a scroll container with the cards underneath it.
            tiles.chunked(2).forEachIndexed { rowIndex, rowTiles ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowTiles.forEachIndexed { colIndex, (label, value) ->
                        StatTile(
                            label = label,
                            value = value,
                            modifier = Modifier.weight(1f),
                            accent = accents[(rowIndex * 2 + colIndex) % accents.size],
                        )
                    }
                    if (rowTiles.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(4.dp))
            SpeedZoneCard(stats.speedZones)

            Spacer(Modifier.height(16.dp))
            val first = stats.firstTs
            val last = stats.lastTs
            if (first != null && last != null) {
                NeonCard(accent = MaterialTheme.colorScheme.tertiary, topBar = false) {
                    Column(Modifier.padding(16.dp)) {
                        SectionLabel(stringResource(R.string.stats_range_covered_label), tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.stats_range_covered_format, formatAbsolute(first), formatAbsolute(last)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

