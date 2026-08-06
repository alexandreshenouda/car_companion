package com.carlauncher.companion.ui.garage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlauncher.companion.R
import com.carlauncher.companion.data.db.CarEntity
import com.carlauncher.companion.data.db.DeviceEntity
import com.carlauncher.companion.data.repo.CarRepository
import com.carlauncher.companion.data.repo.DeviceRepository
import com.carlauncher.companion.ui.common.CarPhoto
import com.carlauncher.companion.ui.common.DashboardRow
import com.carlauncher.companion.ui.common.IconBadge
import com.carlauncher.companion.ui.theme.AccentTrophy
import com.carlauncher.companion.ui.theme.AccentGarage
import kotlinx.coroutines.launch

@Composable
fun GarageScreen(
    carRepository: CarRepository,
    deviceRepository: DeviceRepository,
    onCarSelected: (String) -> Unit,
) {
    val cars by carRepository.observeCars().collectAsStateWithLifecycle(initialValue = emptyList())
    val devices by deviceRepository.observeDevices().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.garage_add_car_content_description))
            }
        },
    ) { padding ->
        if (cars.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconBadge(Icons.Filled.DirectionsCar, AccentGarage, size = 72.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.garage_empty_state), style = MaterialTheme.typography.titleLarge)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().padding(padding).padding(horizontal = 20.dp)) {
                items(cars, key = { it.id }) { car ->
                    CarRow(car = car, onClick = { onCarSelected(car.id) })
                }
            }
        }
    }

    if (showAddDialog) {
        AddCarDialog(
            devices = devices,
            onDismiss = { showAddDialog = false },
            onConfirm = { name, deviceId, brand, model, year, details, odometer ->
                scope.launch {
                    val id = carRepository.addCar(name, deviceId, brand, model, year, details, odometer)
                    showAddDialog = false
                    onCarSelected(id)
                }
            },
        )
    }
}

@Composable
private fun CarRow(car: CarEntity, onClick: () -> Unit) {
    val subtitle = listOfNotNull(
        listOfNotNull(car.brand, car.model).joinToString(" ").ifBlank { null },
        car.odometerKm?.let { stringResource(R.string.garage_odometer_km_format, it) },
    ).joinToString(" · ").ifBlank { null }

    DashboardRow(
        leading = {
            Box {
                CarPhoto(
                    photoPath = car.photoPath,
                    modifier = Modifier.size(48.dp).clip(CircleShape),
                )
                if (car.isFavorite) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = stringResource(R.string.garage_favorite_content_description),
                        tint = AccentTrophy,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(18.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .padding(2.dp),
                    )
                }
            }
        },
        dividerTint = AccentGarage,
        title = car.name,
        subtitle = subtitle,
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCarDialog(
    devices: List<DeviceEntity>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, deviceId: String?, brand: String?, model: String?, year: Int?, details: String?, odometer: Double?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var odometer by remember { mutableStateOf("") }
    var selectedDevice by remember { mutableStateOf<DeviceEntity?>(null) }
    var deviceMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.garage_add_car_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.garage_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (devices.isNotEmpty()) {
                    ExposedDropdownMenuBox(expanded = deviceMenuExpanded, onExpandedChange = { deviceMenuExpanded = it }) {
                        OutlinedTextField(
                            value = selectedDevice?.name ?: stringResource(R.string.garage_no_linked_device),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.garage_field_linked_device)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = deviceMenuExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        DropdownMenu(expanded = deviceMenuExpanded, onDismissRequest = { deviceMenuExpanded = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.garage_no_linked_device)) }, onClick = {
                                selectedDevice = null
                                deviceMenuExpanded = false
                            })
                            devices.forEach { device ->
                                DropdownMenuItem(text = { Text(device.name) }, onClick = {
                                    selectedDevice = device
                                    deviceMenuExpanded = false
                                })
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = brand,
                    onValueChange = { brand = it },
                    label = { Text(stringResource(R.string.garage_field_brand)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(R.string.garage_field_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = year,
                    onValueChange = { year = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.garage_field_year)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    label = { Text(stringResource(R.string.garage_field_details)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = odometer,
                    onValueChange = { odometer = it.filter { c -> c.isDigit() || c == '.' } },
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
                        selectedDevice?.deviceId,
                        brand.trim().ifBlank { null },
                        model.trim().ifBlank { null },
                        year.toIntOrNull(),
                        details.trim().ifBlank { null },
                        odometer.toDoubleOrNull(),
                    )
                },
            ) { Text(stringResource(R.string.garage_add_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
