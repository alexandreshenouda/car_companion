package com.carlauncher.companion.ui.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlauncher.companion.R
import com.carlauncher.companion.data.db.DeviceEntity
import com.carlauncher.companion.data.db.LocationPointEntity
import com.carlauncher.companion.data.model.HistoryRange
import com.carlauncher.companion.data.model.SpeedZone
import com.carlauncher.companion.data.model.TrackStats
import com.carlauncher.companion.data.model.labelRes
import com.carlauncher.companion.data.repo.DeviceRepository
import com.carlauncher.companion.data.repo.TrackRepository
import com.carlauncher.companion.data.repo.computeStats
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.common.RangeSelector
import com.carlauncher.companion.data.repo.GpxExporter
import com.carlauncher.companion.data.repo.writeToUri
import com.carlauncher.companion.util.dayKey
import com.carlauncher.companion.util.dayLabel
import com.carlauncher.companion.util.formatAbsolute
import com.carlauncher.companion.util.hourKey
import com.carlauncher.companion.util.hourLabel
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    deviceId: String,
    deviceName: String,
    trackRepository: TrackRepository,
    deviceRepository: DeviceRepository,
    onPointSelected: (lat: Double, lng: Double) -> Unit,
) {
    var range by remember { mutableStateOf(HistoryRange.LAST_7_DAYS) }
    var points by remember { mutableStateOf<List<LocationPointEntity>>(emptyList()) }
    var isSyncing by remember { mutableStateOf(false) }
    var expandedDay by remember { mutableStateOf<String?>(null) }
    var expandedHour by remember { mutableStateOf<String?>(null) }
    var dayPendingDelete by remember { mutableStateOf<DaySummary?>(null) }
    var pointPendingDelete by remember { mutableStateOf<LocationPointEntity?>(null) }
    var showReassignDialog by remember { mutableStateOf(false) }
    val devices by deviceRepository.observeDevices().collectAsStateWithLifecycle(initialValue = emptyList())
    val otherDevices = devices.filter { it.deviceId != deviceId }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    suspend fun reload() {
        points = trackRepository.pointsInRange(deviceId, range)
    }

    LaunchedEffect(deviceId, range) { reload() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri ->
        if (uri != null) {
            val gpx = GpxExporter.buildGpx(deviceName, points)
            scope.launch { GpxExporter.writeToUri(context, uri, gpx) }
        }
    }

    val byDay = remember(points) {
        points.groupBy { dayKey(it.ts) }.entries.sortedByDescending { it.key }
    }
    // Sorting + computeStats per day is only worth redoing when the underlying points change —
    // without this, expanding/collapsing any single day re-ran it for every day in the list,
    // since expandedDay/expandedHour are read directly in the LazyColumn's scope builder below.
    val dayEntries = remember(byDay) {
        byDay.map { (key, dayPoints) ->
            val sorted = dayPoints.sortedBy { it.ts }
            DayEntry(key, sorted, computeStats(sorted))
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        RangeSelector(selected = range, onSelect = { range = it })
        Spacer(Modifier.height(12.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val actionAccent = MaterialTheme.colorScheme.secondary
            NeonPill(
                text = if (isSyncing) stringResource(R.string.history_syncing) else stringResource(R.string.history_sync_now),
                accent = actionAccent,
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
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = actionAccent)
                    } else {
                        Icon(Icons.Filled.Refresh, null, tint = actionAccent, modifier = Modifier.size(16.dp))
                    }
                },
            )
            // Disabled actions dim rather than disappear, so the row keeps its shape.
            val exportAccent = if (points.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            NeonPill(
                text = stringResource(R.string.history_export_gpx),
                accent = exportAccent,
                onClick = if (points.isNotEmpty()) {
                    { exportLauncher.launch("$deviceName-${range.name.lowercase()}.gpx") }
                } else {
                    null
                },
                leading = { Icon(Icons.Filled.Download, null, tint = exportAccent, modifier = Modifier.size(16.dp)) },
            )
            val canReassign = points.isNotEmpty() && otherDevices.isNotEmpty()
            val reassignAccent = if (canReassign) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
            NeonPill(
                text = stringResource(R.string.history_reassign),
                accent = reassignAccent,
                onClick = if (canReassign) {
                    { showReassignDialog = true }
                } else {
                    null
                },
                leading = { Icon(Icons.Filled.SwapHoriz, null, tint = reassignAccent, modifier = Modifier.size(16.dp)) },
            )
        }
        Spacer(Modifier.height(16.dp))

        if (points.isEmpty()) {
            Text(
                stringResource(R.string.history_empty_state),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                dayEntries.forEach { (key, sorted, stats) ->
                    val isExpanded = expandedDay == key
                    item(key = key) {
                        // Accent tracks how fast the day was — a quick visual read of
                        // which days were motorway runs vs. town errands.
                        val dayAccent = Color(SpeedZone.forSpeed(stats.maxSpeedKmh).color)
                        NeonCard(
                            accent = dayAccent,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { expandedDay = if (isExpanded) null else key },
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        dayLabel(sorted.first().ts),
                                        style = MaterialTheme.typography.titleLarge,
                                        color = dayAccent,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                dayPendingDelete = DaySummary(
                                                    key = key,
                                                    label = dayLabel(sorted.first().ts),
                                                    fromTs = sorted.first().ts,
                                                    toTs = sorted.last().ts,
                                                )
                                            },
                                        ) {
                                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.history_clear_day_content_description))
                                        }
                                        Icon(
                                            if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                            contentDescription = if (isExpanded) stringResource(R.string.history_collapse) else stringResource(R.string.history_show_all_points),
                                        )
                                    }
                                }
                                Text(
                                    stringResource(R.string.history_day_summary_format, sorted.size, stats.distanceKm, stats.maxSpeedKmh),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (isExpanded) {
                        val byHour = sorted.groupBy { hourKey(it.ts) }
                        byHour.forEach { (hKey, hourPoints) ->
                            val isHourExpanded = expandedHour == hKey
                            item(key = "hour-$hKey") {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { expandedHour = if (isHourExpanded) null else hKey }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(hourLabel(hourPoints.first().ts), style = MaterialTheme.typography.titleSmall)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            pluralStringResource(R.plurals.history_points_count, hourPoints.size, hourPoints.size),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            if (isHourExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                            contentDescription = if (isHourExpanded) stringResource(R.string.history_collapse) else stringResource(R.string.history_show_points),
                                        )
                                    }
                                }
                            }
                            if (isHourExpanded) {
                                items(hourPoints, key = { "$hKey-${it.ts}" }) { point ->
                                    PointRow(
                                        point,
                                        onClick = { onPointSelected(point.lat, point.lng) },
                                        onDelete = { pointPendingDelete = point },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    dayPendingDelete?.let { day ->
        AlertDialog(
            onDismissRequest = { dayPendingDelete = null },
            title = { Text(stringResource(R.string.history_clear_day_confirm_title_format, day.label)) },
            text = { Text(stringResource(R.string.history_clear_day_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            trackRepository.deletePointsInRange(deviceId, day.fromTs, day.toTs)
                            if (expandedDay == day.key) expandedDay = null
                            dayPendingDelete = null
                            reload()
                        }
                    },
                ) {
                    Text(stringResource(R.string.history_dialog_clear_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { dayPendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    pointPendingDelete?.let { point ->
        AlertDialog(
            onDismissRequest = { pointPendingDelete = null },
            title = { Text(stringResource(R.string.history_delete_point_confirm_title)) },
            text = { Text(stringResource(R.string.history_delete_point_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            trackRepository.deletePoint(point.id)
                            pointPendingDelete = null
                            reload()
                        }
                    },
                ) {
                    Text(stringResource(R.string.history_dialog_clear_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { pointPendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showReassignDialog) {
        ReassignDialog(
            devices = otherDevices,
            rangeLabel = stringResource(range.labelRes),
            onDismiss = { showReassignDialog = false },
            onConfirm = { target ->
                scope.launch {
                    trackRepository.reassignPointsInRange(deviceId, target.deviceId, range)
                    showReassignDialog = false
                    reload()
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReassignDialog(
    devices: List<DeviceEntity>,
    rangeLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (DeviceEntity) -> Unit,
) {
    var selected by remember { mutableStateOf(devices.first()) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_reassign_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.history_reassign_body_format, rangeLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = selected.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.history_reassign_move_to_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        devices.forEach { device ->
                            DropdownMenuItem(
                                text = { Text(device.name) },
                                onClick = { selected = device; expanded = false },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.history_reassign_move_button)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

private data class DaySummary(val key: String, val label: String, val fromTs: Long, val toTs: Long)

private data class DayEntry(val key: String, val points: List<LocationPointEntity>, val stats: TrackStats)

@Composable
private fun PointRow(point: LocationPointEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(formatAbsolute(point.ts), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.common_speed_kmh, point.speedKmh),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.history_delete_point_content_description),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            // Raw coordinates, not natural-language content — kept locale-invariant
            // (period decimal) like the GPX export, rather than following the UI locale.
            String.format(java.util.Locale.US, "%.5f, %.5f", point.lat, point.lng),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}
