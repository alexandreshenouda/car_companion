package com.carlauncher.companion.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlauncher.companion.R
import com.carlauncher.companion.data.model.SpeedZoneTime
import com.carlauncher.companion.util.formatDuration

/**
 * Instrument tile: monospace readout under a wide-tracked label, wrapped in an accent
 * rim with a gradient bar across the top — the arcade dashboard's basic unit, shared by
 * [com.carlauncher.companion.ui.stats.StatsScreen], Garage car stats and Event stats.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    NeonCard(accent = accent, modifier = modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            SectionLabel(label, tint = accent.copy(alpha = 0.8f))
            Spacer(Modifier.height(8.dp))
            Text(
                value,
                style = MaterialTheme.typography.displaySmall,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SpeedZoneCard(zones: List<SpeedZoneTime>, modifier: Modifier = Modifier.fillMaxWidth()) {
    val accent = MaterialTheme.colorScheme.secondary
    NeonCard(accent = accent, modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            SectionLabel(stringResource(R.string.stats_time_by_speed_zone), tint = accent.copy(alpha = 0.8f))
            Spacer(Modifier.height(10.dp))
            zones.forEach { zoneTime ->
                val zoneColor = Color(zoneTime.zone.color)
                Column(Modifier.padding(vertical = 5.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(zoneColor, CircleShape))
                            Spacer(Modifier.width(10.dp))
                            Text(zoneTime.zone.label, style = MaterialTheme.typography.bodyLarge)
                        }
                        Text(
                            stringResource(R.string.stats_zone_duration_percent_format, formatDuration(zoneTime.seconds), zoneTime.percentage.toInt()),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    // The bar is the point of the card — the numbers alone made every
                    // zone look equally important regardless of how little time it held.
                    NeonProgressBar(
                        progress = (zoneTime.percentage / 100.0).toFloat(),
                        accent = zoneColor,
                        height = 5.dp,
                    )
                }
            }
        }
    }
}
