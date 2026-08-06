package com.carlauncher.companion.ui.garage

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlauncher.companion.R
import com.carlauncher.companion.data.cloud.AuthRepository
import com.carlauncher.companion.data.cloud.CloudPrefsRepository
import com.carlauncher.companion.data.cloud.CloudSyncManager
import com.carlauncher.companion.data.db.CarEntity
import com.carlauncher.companion.data.db.CarModificationEntity
import com.carlauncher.companion.data.model.HistoryRange
import com.carlauncher.companion.data.model.TrackStats
import com.carlauncher.companion.data.model.TrophyStats
import com.carlauncher.companion.data.repo.CarRepository
import com.carlauncher.companion.data.repo.TrophyRepository
import com.carlauncher.companion.data.repo.TrackRepository
import com.carlauncher.companion.ui.common.AccentDivider
import com.carlauncher.companion.ui.common.CarPhoto
import com.carlauncher.companion.ui.common.IconBadge
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.common.SectionLabel
import com.carlauncher.companion.ui.common.StatTile
import com.carlauncher.companion.ui.cloud.ShareToggleCard
import com.carlauncher.companion.ui.trophies.CarTrophyStrip
import com.carlauncher.companion.ui.theme.AccentTrophy
import com.carlauncher.companion.ui.theme.AccentGarage
import com.carlauncher.companion.util.formatAbsolute
import com.carlauncher.companion.util.formatDuration
import kotlinx.coroutines.launch

@Composable
fun CarDetailScreen(
    carId: String,
    carRepository: CarRepository,
    trackRepository: TrackRepository,
    trophyRepository: TrophyRepository,
    authRepository: AuthRepository,
    cloudPrefsRepository: CloudPrefsRepository,
    cloudSyncManager: CloudSyncManager,
    onDeleted: () -> Unit,
) {
    val car by carRepository.observeCar(carId).collectAsStateWithLifecycle(initialValue = null)
    val modifications by carRepository.observeModifications(carId).collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var editingDetails by remember { mutableStateOf(false) }
    var addingModification by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var stats by remember(carId) { mutableStateOf<TrackStats?>(null) }
    var trophyStats by remember(carId) { mutableStateOf<TrophyStats?>(null) }

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) carRepository.updatePhoto(carId, bytes)
            }
        }
    }

    LaunchedEffect(car?.deviceId) {
        val deviceId = car?.deviceId
        stats = if (deviceId != null) trackRepository.statsInRange(deviceId, HistoryRange.ALL) else null
        // Scoped to this car's own device, so the strip shows what *it* earned rather
        // than the global trophy state.
        trophyStats = deviceId?.let { trophyRepository.statsForDevice(it) }
    }

    val current = car
    if (current == null) return

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        NeonCard(
            accent = AccentGarage,
            modifier = Modifier.fillMaxWidth(),
            topBar = false,
            onClick = {
                photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        ) {
            CarPhoto(photoPath = current.photoPath, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        }
        Text(
            stringResource(R.string.car_detail_tap_photo_to_change),
            style = MaterialTheme.typography.bodySmall,
            color = AccentGarage,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(8.dp))
        NeonCard(accent = AccentGarage, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Filled.DirectionsCar, AccentGarage)
                        Spacer(Modifier.width(12.dp))
                        Text(current.name, style = MaterialTheme.typography.headlineSmall)
                    }
                    Row {
                        IconButton(onClick = {
                            scope.launch {
                                if (current.isFavorite) carRepository.clearFavorite() else carRepository.setFavorite(current.id)
                            }
                        }) {
                            Icon(
                                if (current.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = if (current.isFavorite) {
                                    stringResource(R.string.car_detail_remove_favorite_content_description)
                                } else {
                                    stringResource(R.string.car_detail_set_favorite_content_description)
                                },
                                tint = if (current.isFavorite) AccentTrophy else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { editingDetails = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.common_edit), tint = AccentGarage)
                        }
                        IconButton(onClick = { confirmingDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.car_detail_remove_car_content_description), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                listOfNotNull(current.brand, current.model, current.year?.toString())
                    .joinToString(" ")
                    .takeIf { it.isNotBlank() }
                    ?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                current.details?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(14.dp))
                SectionLabel(stringResource(R.string.car_detail_odometer_label), tint = AccentGarage)
                Spacer(Modifier.height(4.dp))
                Text(
                    current.odometerKm?.let { stringResource(R.string.garage_odometer_km_format, it) } ?: stringResource(R.string.car_detail_odometer_not_set),
                    style = MaterialTheme.typography.displaySmall,
                    color = AccentGarage,
                )
            }
        }

        val statsSnapshot = stats
        if (current.deviceId != null && statsSnapshot != null) {
            Spacer(Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.car_detail_stats_label), tint = AccentGarage)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(stringResource(R.string.car_detail_stat_distance), stringResource(R.string.common_distance_km, statsSnapshot.distanceKm), Modifier.weight(1f), accent = AccentGarage)
                StatTile(stringResource(R.string.stats_tile_moving_time), formatDuration(statsSnapshot.movingTimeSeconds), Modifier.weight(1f), accent = AccentGarage)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(stringResource(R.string.car_detail_stat_max_speed), stringResource(R.string.common_speed_kmh, statsSnapshot.maxSpeedKmh), Modifier.weight(1f), accent = AccentGarage)
                StatTile(stringResource(R.string.car_detail_stat_avg_speed), stringResource(R.string.common_speed_kmh_rounded, statsSnapshot.avgSpeedKmh), Modifier.weight(1f), accent = AccentGarage)
            }
        }

        trophyStats?.let { earned ->
            Spacer(Modifier.height(20.dp))
            CarTrophyStrip(stats = earned, accent = AccentGarage)
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.car_detail_modifications_label), tint = AccentGarage)
            IconButton(onClick = { addingModification = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.car_detail_add_modification_content_description), tint = AccentGarage)
            }
        }
        if (modifications.isEmpty()) {
            Text(stringResource(R.string.car_detail_no_modifications), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            modifications.forEach { mod ->
                ModificationRow(mod, onDelete = { scope.launch { carRepository.removeModification(mod) } })
            }
        }

        Spacer(Modifier.height(20.dp))
        ShareToggleCard(
            authRepository = authRepository,
            cloudPrefsRepository = cloudPrefsRepository,
            isShared = current.isShared,
            accent = AccentGarage,
            onToggle = { shared ->
                scope.launch {
                    carRepository.setShared(current.id, shared)
                    // Immediate, not deferred to the periodic worker: a share toggle is a
                    // single small row, and the user is looking right at the switch waiting
                    // to see it take effect (in their own Feed, or a friend's).
                    cloudSyncManager.syncAll()
                }
            },
        )
    }

    if (editingDetails) {
        EditCarDetailsDialog(
            car = current,
            onDismiss = { editingDetails = false },
            onConfirm = { name, brand, model, year, details, odometer ->
                scope.launch {
                    carRepository.updateCar(
                        current.copy(name = name, brand = brand, model = model, year = year, details = details, odometerKm = odometer),
                    )
                }
                editingDetails = false
            },
        )
    }

    if (addingModification) {
        AddModificationDialog(
            onDismiss = { addingModification = false },
            onConfirm = { title, category, installedAt, cost, notes ->
                scope.launch { carRepository.addModification(carId, title, category, installedAt, cost, notes) }
                addingModification = false
            },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.car_detail_remove_confirm_title_format, current.name)) },
            text = { Text(stringResource(R.string.car_detail_remove_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { carRepository.removeCar(current) }
                    confirmingDelete = false
                    onDeleted()
                }) { Text(stringResource(R.string.car_detail_remove_button)) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun ModificationRow(mod: CarModificationEntity, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Build, AccentGarage, size = 40.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(mod.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(mod.category, formatAbsolute(mod.installedAt), mod.cost?.let { stringResource(R.string.car_detail_modification_cost_format, it) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    mod.notes?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.car_detail_delete_modification_content_description), tint = MaterialTheme.colorScheme.error)
            }
        }
        AccentDivider(AccentGarage)
    }
}

@Composable
private fun EditCarDetailsDialog(
    car: CarEntity,
    onDismiss: () -> Unit,
    onConfirm: (name: String, brand: String?, model: String?, year: Int?, details: String?, odometer: Double?) -> Unit,
) {
    var name by remember { mutableStateOf(car.name) }
    var brand by remember { mutableStateOf(car.brand.orEmpty()) }
    var model by remember { mutableStateOf(car.model.orEmpty()) }
    var year by remember { mutableStateOf(car.year?.toString().orEmpty()) }
    var details by remember { mutableStateOf(car.details.orEmpty()) }
    var odometer by remember { mutableStateOf(car.odometerKm?.toString().orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.car_detail_edit_car_title)) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.garage_field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(brand, { brand = it }, label = { Text(stringResource(R.string.garage_field_brand)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(model, { model = it }, label = { Text(stringResource(R.string.garage_field_model)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    year,
                    { year = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.garage_field_year)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(details, { details = it }, label = { Text(stringResource(R.string.garage_field_details)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    odometer,
                    { odometer = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text(stringResource(R.string.garage_field_odometer)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    onConfirm(
                        name.trim(),
                        brand.trim().ifBlank { null },
                        model.trim().ifBlank { null },
                        year.toIntOrNull(),
                        details.trim().ifBlank { null },
                        odometer.toDoubleOrNull(),
                    )
                },
            ) { Text(stringResource(R.string.car_detail_save_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun AddModificationDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, category: String?, installedAt: Long, cost: Double?, notes: String?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.car_detail_add_modification_title)) },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.car_detail_field_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(category, { category = it }, label = { Text(stringResource(R.string.car_detail_field_category_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    cost,
                    { cost = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text(stringResource(R.string.car_detail_field_cost)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(notes, { notes = it }, label = { Text(stringResource(R.string.car_detail_field_notes)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank(),
                onClick = {
                    onConfirm(
                        title.trim(),
                        category.trim().ifBlank { null },
                        System.currentTimeMillis(),
                        cost.toDoubleOrNull(),
                        notes.trim().ifBlank { null },
                    )
                },
            ) { Text(stringResource(R.string.garage_add_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
