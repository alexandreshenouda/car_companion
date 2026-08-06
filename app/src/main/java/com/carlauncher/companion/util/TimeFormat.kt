package com.carlauncher.companion.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.carlauncher.companion.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val absoluteFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss", Locale.getDefault())
    .withZone(ZoneId.systemDefault())
private val dayKeyFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    .withZone(ZoneId.systemDefault())
private val dayLabelFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
    .withZone(ZoneId.systemDefault())
private val hourKeyFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH", Locale.US)
    .withZone(ZoneId.systemDefault())
private val hourLabelFormatter = DateTimeFormatter.ofPattern("HH:00", Locale.getDefault())
    .withZone(ZoneId.systemDefault())
private val clockTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    .withZone(ZoneId.systemDefault())

/** Stable grouping key for "same calendar day" bucketing, e.g. "2026-07-26". */
fun dayKey(epochMillis: Long): String = dayKeyFormatter.format(Instant.ofEpochMilli(epochMillis))

/** e.g. "Sun, Jul 26" */
fun dayLabel(epochMillis: Long): String = dayLabelFormatter.format(Instant.ofEpochMilli(epochMillis))

/** Stable grouping key for "same calendar hour" bucketing, e.g. "2026-07-26-14". */
fun hourKey(epochMillis: Long): String = hourKeyFormatter.format(Instant.ofEpochMilli(epochMillis))

/** e.g. "14:00" */
fun hourLabel(epochMillis: Long): String = hourLabelFormatter.format(Instant.ofEpochMilli(epochMillis))

/** e.g. "Jul 26, 14:32:05" */
fun formatAbsolute(epochMillis: Long): String =
    absoluteFormatter.format(Instant.ofEpochMilli(epochMillis))

/** e.g. "14:32 – 15:10", or "Jul 26, 14:32 – Jul 27, 00:10" when the two timestamps fall on different days. */
fun formatTimeRange(startMillis: Long, endMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val startInstant = Instant.ofEpochMilli(startMillis)
    val endInstant = Instant.ofEpochMilli(endMillis)
    val sameDay = startInstant.atZone(zone).toLocalDate() == endInstant.atZone(zone).toLocalDate()
    return if (sameDay) {
        "${clockTimeFormatter.format(startInstant)} – ${clockTimeFormatter.format(endInstant)}"
    } else {
        "${dayLabelFormatter.format(startInstant)}, ${clockTimeFormatter.format(startInstant)} – " +
            "${dayLabelFormatter.format(endInstant)}, ${clockTimeFormatter.format(endInstant)}"
    }
}

/** e.g. "3 min ago", "just now", "2 h ago" */
@Composable
fun formatRelative(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val diffSeconds = (nowMillis - epochMillis) / 1000
    return when {
        diffSeconds < 5 -> stringResource(R.string.time_just_now)
        diffSeconds < 60 -> stringResource(R.string.time_seconds_ago, diffSeconds)
        diffSeconds < 3600 -> stringResource(R.string.time_minutes_ago, diffSeconds / 60)
        diffSeconds < 86400 -> stringResource(R.string.time_hours_ago, diffSeconds / 3600)
        else -> stringResource(R.string.time_days_ago, diffSeconds / 86400)
    }
}

/** e.g. "1 h 23 min", "45 min" */
@Composable
fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) {
        stringResource(R.string.time_duration_hours_minutes, hours, minutes)
    } else {
        stringResource(R.string.time_duration_minutes, minutes)
    }
}
