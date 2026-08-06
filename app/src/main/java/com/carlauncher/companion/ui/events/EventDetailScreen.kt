package com.carlauncher.companion.ui.events

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlauncher.companion.R
import com.carlauncher.companion.data.cloud.AuthRepository
import com.carlauncher.companion.data.cloud.CloudPrefsRepository
import com.carlauncher.companion.data.cloud.CloudSyncManager
import com.carlauncher.companion.data.db.CarEntity
import com.carlauncher.companion.data.db.EventEntity
import com.carlauncher.companion.data.db.LocationPointEntity
import com.carlauncher.companion.data.model.EventType
import com.carlauncher.companion.data.repo.CarRepository
import com.carlauncher.companion.data.repo.EventRepository
import com.carlauncher.companion.data.repo.GpxExporter
import com.carlauncher.companion.data.repo.GpxImporter
import com.carlauncher.companion.data.repo.GpxPoint
import com.carlauncher.companion.data.repo.computeStats
import com.carlauncher.companion.data.repo.importFrom
import com.carlauncher.companion.data.repo.writeToUri
import com.carlauncher.companion.ui.common.IconBadge
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.common.SectionLabel
import com.carlauncher.companion.ui.common.StatTile
import com.carlauncher.companion.ui.common.TraceMap
import com.carlauncher.companion.ui.cloud.ShareToggleCard
import com.carlauncher.companion.ui.nav.Destination
import com.carlauncher.companion.util.formatAbsolute
import com.carlauncher.companion.util.formatDuration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.launch

@Composable
fun EventDetailScreen(
    eventId: String,
    eventRepository: EventRepository,
    carRepository: CarRepository,
    authRepository: AuthRepository,
    cloudPrefsRepository: CloudPrefsRepository,
    cloudSyncManager: CloudSyncManager,
    onDone: () -> Unit,
    onShare: (String) -> Unit,
) {
    val isNew = eventId == Destination.EventDetail.NEW_ID
    val existing by eventRepository.observeEvent(eventId).collectAsStateWithLifecycle(initialValue = null)
    val cars by carRepository.observeCars().collectAsStateWithLifecycle(initialValue = emptyList())
    val points by eventRepository.observePoints(eventId).collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    var editing by remember(eventId) { mutableStateOf(isNew) }
    var confirmingDelete by remember { mutableStateOf(false) }

    if (editing) {
        EventForm(
            event = existing,
            cars = cars,
            existingPointsCount = points.size,
            onCancel = { if (isNew) onDone() else editing = false },
            onSave = { title, type, carId, deviceId, startTs, endTs, locationLabel, notes, gpxPoints ->
                scope.launch {
                    val current = existing
                    when {
                        current == null && gpxPoints != null ->
                            eventRepository.createEventFromGpx(title, type, carId, locationLabel, notes, gpxPoints)
                        current == null ->
                            eventRepository.createEvent(title, type, carId, deviceId, startTs, endTs, locationLabel, notes)
                        gpxPoints != null ->
                            eventRepository.updateEventGpxPoints(current, title, type, carId, locationLabel, notes, gpxPoints)
                        current.pointsSource == "GPX" ->
                            eventRepository.updateEventMetadata(current, title, type, carId, locationLabel, notes)
                        else ->
                            eventRepository.updateEvent(current, title, type, carId, deviceId, startTs, endTs, locationLabel, notes)
                    }
                    if (current == null) onDone() else editing = false
                }
            },
        )
    } else {
        val event = existing ?: return
        val car = cars.firstOrNull { it.id == event.carId }
        val type = runCatching { EventType.valueOf(event.type) }.getOrDefault(EventType.OTHER)
        val locationPoints = remember(points) {
            points.map { LocationPointEntity(deviceId = "", lat = it.lat, lng = it.lng, ts = it.ts, speedKmh = it.speedKmh, pushedAtMillis = it.ts) }
        }
        val stats = remember(locationPoints) {
            if (locationPoints.isEmpty()) null else computeStats(locationPoints)
        }
        val context = LocalContext.current
        val gpxExportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/gpx+xml"),
        ) { uri ->
            if (uri != null) {
                val gpx = GpxExporter.buildGpx(event.title, locationPoints)
                scope.launch { GpxExporter.writeToUri(context, uri, gpx) }
            }
        }

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
            NeonCard(accent = type.color, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(type.icon, type.color)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(event.title, style = MaterialTheme.typography.headlineSmall)
                                SectionLabel(stringResource(type.labelRes), tint = type.color)
                            }
                        }
                        Row {
                            if (locationPoints.isNotEmpty()) {
                                IconButton(onClick = { onShare(event.id) }) {
                                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.common_share), tint = type.color)
                                }
                                IconButton(onClick = {
                                    gpxExportLauncher.launch("${event.title.ifBlank { "event" }}.gpx")
                                }) {
                                    Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.history_export_gpx), tint = type.color)
                                }
                            }
                            IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.common_edit), tint = type.color) }
                            IconButton(onClick = { confirmingDelete = true }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.event_detail_delete_content_description), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.event_detail_date_range_format, formatAbsolute(event.startTs), formatAbsolute(event.endTs)), style = MaterialTheme.typography.bodyLarge)
                    car?.let { Text(stringResource(R.string.event_detail_car_format, it.name), style = MaterialTheme.typography.bodyLarge) }
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
                TraceMap(points = locationPoints, modifier = Modifier.fillMaxWidth().height(220.dp))
            } else if (event.deviceId != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.event_detail_no_gps_points),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))
            ShareToggleCard(
                authRepository = authRepository,
                cloudPrefsRepository = cloudPrefsRepository,
                isShared = event.isShared,
                accent = type.color,
                onToggle = { shared ->
                    scope.launch {
                        eventRepository.setShared(event.id, shared)
                        // Immediate, not deferred to the periodic worker — see the identical
                        // comment on CarDetailScreen's share toggle.
                        cloudSyncManager.syncAll()
                    }
                },
            )
        }

        if (confirmingDelete) {
            AlertDialog(
                onDismissRequest = { confirmingDelete = false },
                title = { Text(stringResource(R.string.event_detail_delete_confirm_title_format, event.title)) },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch { eventRepository.removeEvent(event) }
                        confirmingDelete = false
                        onDone()
                    }) { Text(stringResource(R.string.common_delete)) }
                },
                dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
            )
        }
    }
}

private enum class PointsSourceMode { DEVICE, GPX }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventForm(
    event: EventEntity?,
    cars: List<CarEntity>,
    existingPointsCount: Int,
    onCancel: () -> Unit,
    onSave: (
        title: String,
        type: String,
        carId: String?,
        deviceId: String?,
        startTs: Long,
        endTs: Long,
        locationLabel: String?,
        notes: String?,
        gpxPoints: List<GpxPoint>?,
    ) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val initialDate = event?.startTs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: LocalDate.now()
    val initialStart = event?.startTs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(9, 0)
    val initialEnd = event?.endTs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(17, 0)

    var title by remember { mutableStateOf(event?.title.orEmpty()) }
    var type by remember { mutableStateOf(event?.type?.let { runCatching { EventType.valueOf(it) }.getOrNull() } ?: EventType.CAR_MEET) }
    var selectedCar by remember { mutableStateOf(cars.firstOrNull { it.id == event?.carId }) }
    var date by remember { mutableStateOf(initialDate) }
    var startTime by remember { mutableStateOf(initialStart) }
    var endTime by remember { mutableStateOf(initialEnd) }
    var locationLabel by remember { mutableStateOf(event?.locationLabel.orEmpty()) }
    var notes by remember { mutableStateOf(event?.notes.orEmpty()) }

    var sourceMode by remember {
        mutableStateOf(if (event?.pointsSource == "GPX") PointsSourceMode.GPX else PointsSourceMode.DEVICE)
    }
    var gpxPoints by remember { mutableStateOf<List<GpxPoint>?>(null) }
    var gpxFileName by remember { mutableStateOf<String?>(null) }
    var importingGpx by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val gpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                importingGpx = true
                gpxPoints = GpxImporter.importFrom(context, uri)
                gpxFileName = queryFileName(context, uri)
                importingGpx = false
            }
        }
    }

    var typeMenuExpanded by remember { mutableStateOf(false) }
    var carMenuExpanded by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingStartTime by remember { mutableStateOf(false) }
    var pickingEndTime by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.car_detail_field_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))

        ExposedDropdownMenuBox(expanded = typeMenuExpanded, onExpandedChange = { typeMenuExpanded = it }) {
            OutlinedTextField(
                value = stringResource(type.labelRes),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.event_detail_field_type)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            DropdownMenu(expanded = typeMenuExpanded, onDismissRequest = { typeMenuExpanded = false }) {
                EventType.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(stringResource(option.labelRes)) }, onClick = { type = option; typeMenuExpanded = false })
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        ExposedDropdownMenuBox(expanded = carMenuExpanded, onExpandedChange = { carMenuExpanded = it }) {
            OutlinedTextField(
                value = selectedCar?.name ?: stringResource(R.string.event_detail_no_car),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.event_detail_field_car)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = carMenuExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            DropdownMenu(expanded = carMenuExpanded, onDismissRequest = { carMenuExpanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.event_detail_no_car)) }, onClick = { selectedCar = null; carMenuExpanded = false })
                cars.forEach { car ->
                    DropdownMenuItem(
                        text = { Text(if (car.deviceId != null) car.name else stringResource(R.string.event_detail_car_no_gps_format, car.name)) },
                        onClick = { selectedCar = car; carMenuExpanded = false },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        SectionLabel(stringResource(R.string.event_detail_track_source_label))
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonPill(
                text = stringResource(R.string.event_detail_source_from_car),
                accent = MaterialTheme.colorScheme.primary,
                selected = sourceMode == PointsSourceMode.DEVICE,
                onClick = { sourceMode = PointsSourceMode.DEVICE },
            )
            NeonPill(
                text = stringResource(R.string.event_detail_source_import_gpx),
                accent = MaterialTheme.colorScheme.secondary,
                selected = sourceMode == PointsSourceMode.GPX,
                onClick = { sourceMode = PointsSourceMode.GPX },
            )
        }
        Spacer(Modifier.height(8.dp))

        if (sourceMode == PointsSourceMode.DEVICE) {
            OutlinedTextField(
                value = date.toString(),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.event_detail_field_day)) },
                modifier = Modifier.fillMaxWidth().clickableField { pickingDate = true },
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startTime.toString(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.event_detail_field_start)) },
                    modifier = Modifier.weight(1f).clickableField { pickingStartTime = true },
                )
                OutlinedTextField(
                    value = endTime.toString(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.event_detail_field_end)) },
                    modifier = Modifier.weight(1f).clickableField { pickingEndTime = true },
                )
            }
        } else {
            OutlinedButton(onClick = { gpxLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                if (importingGpx) {
                    CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.AttachFile, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (gpxPoints == null && event?.pointsSource != "GPX") {
                        stringResource(R.string.event_detail_import_gpx_file)
                    } else {
                        stringResource(R.string.event_detail_replace_gpx_file)
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            val importedFileFallback = stringResource(R.string.event_detail_gpx_imported_file_fallback)
            val summary = gpxPoints?.let { pts ->
                if (pts.isEmpty()) {
                    stringResource(R.string.event_detail_gpx_no_points)
                } else {
                    stringResource(
                        R.string.event_detail_gpx_summary_format,
                        gpxFileName ?: importedFileFallback,
                        pts.size,
                        formatAbsolute(pts.minOf { it.ts }),
                        formatAbsolute(pts.maxOf { it.ts }),
                    )
                }
            } ?: event?.takeIf { it.pointsSource == "GPX" }?.let {
                stringResource(R.string.event_detail_gpx_existing_summary_format, existingPointsCount, formatAbsolute(it.startTs), formatAbsolute(it.endTs))
            }
            Text(
                summary ?: stringResource(R.string.event_detail_gpx_none_imported),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(locationLabel, { locationLabel = it }, label = { Text(stringResource(R.string.event_detail_field_location)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(notes, { notes = it }, label = { Text(stringResource(R.string.car_detail_field_notes)) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
            Spacer(Modifier.width(8.dp))
            val canSave = title.isNotBlank() &&
                (sourceMode == PointsSourceMode.DEVICE || gpxPoints != null || event?.pointsSource == "GPX")
            Button(
                enabled = canSave,
                onClick = {
                    if (sourceMode == PointsSourceMode.GPX) {
                        onSave(
                            title.trim(),
                            type.name,
                            selectedCar?.id,
                            null,
                            0L,
                            0L,
                            locationLabel.trim().ifBlank { null },
                            notes.trim().ifBlank { null },
                            gpxPoints,
                        )
                    } else {
                        var endDate = date
                        if (!endTime.isAfter(startTime)) endDate = date.plusDays(1)
                        val startTs = date.atTime(startTime).atZone(zone).toInstant().toEpochMilli()
                        val endTs = endDate.atTime(endTime).atZone(zone).toInstant().toEpochMilli()
                        onSave(
                            title.trim(),
                            type.name,
                            selectedCar?.id,
                            selectedCar?.deviceId,
                            startTs,
                            endTs,
                            locationLabel.trim().ifBlank { null },
                            notes.trim().ifBlank { null },
                            null,
                        )
                    }
                },
            ) { Text(stringResource(R.string.car_detail_save_button)) }
        }
    }

    if (pickingDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    pickingDate = false
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.common_cancel)) } },
        ) { DatePicker(state = state) }
    }

    if (pickingStartTime) {
        val state = rememberTimePickerState(initialHour = startTime.hour, initialMinute = startTime.minute)
        TimePickerDialog(
            onDismiss = { pickingStartTime = false },
            onConfirm = { startTime = LocalTime.of(state.hour, state.minute); pickingStartTime = false },
        ) { TimePicker(state = state) }
    }

    if (pickingEndTime) {
        val state = rememberTimePickerState(initialHour = endTime.hour, initialMinute = endTime.minute)
        TimePickerDialog(
            onDismiss = { pickingEndTime = false },
            onConfirm = { endTime = LocalTime.of(state.hour, state.minute); pickingEndTime = false },
        ) { TimePicker(state = state) }
    }
}

@Composable
private fun TimePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { content() },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * A plain `.clickable` on a `readOnly` [OutlinedTextField] doesn't reliably fire: the field's own
 * tap-to-focus gesture handling can swallow the touch before `clickable`'s tap detector resolves
 * it. Intercepting in [PointerEventPass.Initial] (before the field sees it) and consuming the
 * down event avoids that race and also stops the field from gaining focus/showing a cursor.
 */
private fun Modifier.clickableField(onClick: () -> Unit): Modifier = this.pointerInput(onClick) {
    awaitEachGesture {
        awaitFirstDown(pass = PointerEventPass.Initial).also { it.consume() }
        val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
        if (up != null) onClick()
    }
}

private fun queryFileName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

