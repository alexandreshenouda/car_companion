package com.carlauncher.companion.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.carlauncher.companion.data.cloud.SharedContentRepository
import com.carlauncher.companion.data.cloud.dto.EventRestoreRow
import com.carlauncher.companion.data.cloud.parseIsoToEpochMilli
import com.carlauncher.companion.data.db.LocationPointEntity
import com.carlauncher.companion.data.model.EventType
import com.carlauncher.companion.data.repo.computeStats
import com.carlauncher.companion.ui.common.IconBadge
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.common.SectionLabel
import com.carlauncher.companion.ui.common.StatTile
import com.carlauncher.companion.ui.common.TraceMap
import com.carlauncher.companion.R
import com.carlauncher.companion.ui.theme.AccentEvents
import com.carlauncher.companion.util.formatAbsolute
import com.carlauncher.companion.util.formatDuration

/** A friend's shared event, read-only, rendered through the identical [TraceMap] a local event
 * detail screen uses — a shared trace looks and behaves exactly like one of the viewer's own. */
@Composable
fun SharedEventDetailScreen(eventId: String, sharedContentRepository: SharedContentRepository, modifier: Modifier = Modifier) {
    var result by remember(eventId) { mutableStateOf<Pair<EventRestoreRow, List<LocationPointEntity>>?>(null) }
    var loading by remember(eventId) { mutableStateOf(true) }

    LaunchedEffect(eventId) {
        loading = true
        result = sharedContentRepository.getSharedEvent(eventId)
        loading = false
    }

    if (loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AccentEvents) }
        return
    }
    val (event, points) = result ?: run {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.shared_event_not_visible), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val type = runCatching { EventType.valueOf(event.type) }.getOrDefault(EventType.OTHER)
    val stats = remember(points) { if (points.isEmpty()) null else computeStats(points) }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        NeonCard(accent = type.color, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(type.icon, type.color)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(event.title, style = MaterialTheme.typography.headlineSmall)
                        SectionLabel(stringResource(type.labelRes), tint = type.color)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.event_detail_date_range_format, formatAbsolute(event.startTs.parseIsoToEpochMilli()), formatAbsolute(event.endTs.parseIsoToEpochMilli())),
                    style = MaterialTheme.typography.bodyLarge,
                )
                event.locationLabel?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                event.notes?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (stats != null) {
            Spacer(Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.event_detail_track_label), tint = type.color)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(stringResource(R.string.car_detail_stat_distance), stringResource(R.string.common_distance_km, stats.distanceKm), Modifier.weight(1f), accent = type.color)
                StatTile(stringResource(R.string.event_detail_stat_duration), formatDuration(stats.movingTimeSeconds), Modifier.weight(1f), accent = type.color)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(stringResource(R.string.car_detail_stat_max_speed), stringResource(R.string.common_speed_kmh, stats.maxSpeedKmh), Modifier.weight(1f), accent = type.color)
                StatTile(stringResource(R.string.stats_tile_points), "${points.size}", Modifier.weight(1f), accent = type.color)
            }
            Spacer(Modifier.height(12.dp))
            TraceMap(points = points, modifier = Modifier.fillMaxWidth().height(220.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}
