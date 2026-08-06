package com.carlauncher.companion.ui.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlauncher.companion.R
import com.carlauncher.companion.data.db.DeviceEntity
import com.carlauncher.companion.data.model.DiscoveredDevice
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.data.repo.DeviceRepository
import com.carlauncher.companion.data.repo.TrackRepository
import com.carlauncher.companion.util.formatRelative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class AddDialogState(val lockedDeviceId: String?)

@Composable
fun DevicesScreen(
    deviceRepository: DeviceRepository,
    trackRepository: TrackRepository,
    onDeviceSelected: () -> Unit = {},
) {
    val devices by deviceRepository.observeDevices().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var discovered by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }

    var addDialogState by remember { mutableStateOf<AddDialogState?>(null) }
    var editTarget by remember { mutableStateOf<DeviceEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<DeviceEntity?>(null) }

    suspend fun scan() {
        isScanning = true
        scanError = null
        try {
            discovered = trackRepository.discoverDevices()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            scanError = e.message ?: context.getString(R.string.devices_scan_failed)
        } finally {
            isScanning = false
        }
    }

    LaunchedEffect(Unit) { scan() }

    val existingIds = devices.map { it.deviceId }.toSet()
    val newlyDiscovered = discovered.filter { it.deviceId !in existingIds }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { addDialogState = AddDialogState(lockedDeviceId = null) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.devices_add_device_content_description))
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxWidth().padding(padding)) {
            item {
                DiscoverySection(
                    isScanning = isScanning,
                    scanError = scanError,
                    discovered = newlyDiscovered,
                    onRescan = { scope.launch { scan() } },
                    onAdd = { deviceId -> addDialogState = AddDialogState(lockedDeviceId = deviceId) },
                )
            }
            if (devices.isEmpty()) {
                item { EmptyDevicesMessage(isScanning = isScanning && discovered.isEmpty()) }
            } else {
                items(devices, key = { it.deviceId }) { device ->
                    DeviceRow(
                        device = device,
                        onSelect = {
                            scope.launch { deviceRepository.selectDevice(device.deviceId) }
                            onDeviceSelected()
                        },
                        onEdit = { editTarget = device },
                        onDelete = { deleteTarget = device },
                    )
                }
            }
        }
    }

    addDialogState?.let { state ->
        AddDeviceDialog(
            lockedDeviceId = state.lockedDeviceId,
            onDismiss = { addDialogState = null },
            onConfirm = { deviceId, name ->
                scope.launch {
                    deviceRepository.addDevice(deviceId, name)
                    deviceRepository.selectDevice(deviceId)
                    trackRepository.syncFullHistory(deviceId)
                }
                addDialogState = null
                onDeviceSelected()
            },
        )
    }

    editTarget?.let { device ->
        EditCarDialog(
            device = device,
            onDismiss = { editTarget = null },
            onConfirm = { newName, brand, model, details ->
                scope.launch {
                    deviceRepository.renameDevice(device.deviceId, newName)
                    deviceRepository.updateCarDetails(device.deviceId, brand, model, details)
                }
                editTarget = null
            },
        )
    }

    deleteTarget?.let { device ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.devices_remove_confirm_title_format, device.name)) },
            text = { Text(stringResource(R.string.devices_remove_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        trackRepository.deleteAllRemoteData(device.deviceId)
                        deviceRepository.removeDevice(device)
                    }
                    deleteTarget = null
                }) { Text(stringResource(R.string.car_detail_remove_button)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun DiscoverySection(
    isScanning: Boolean,
    scanError: String?,
    discovered: List<DiscoveredDevice>,
    onRescan: () -> Unit,
    onAdd: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.devices_discovered_label), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onRescan, enabled = !isScanning) {
                if (isScanning) {
                    CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.devices_scan_content_description))
                }
            }
        }
        when {
            scanError != null -> Text(
                stringResource(R.string.devices_scan_error_format, scanError),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            !isScanning && discovered.isEmpty() -> Text(
                stringResource(R.string.devices_no_new_cars),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> discovered.forEach { device ->
                Spacer(Modifier.height(8.dp))
                DiscoveredDeviceRow(device = device, onAdd = { onAdd(device.deviceId) })
            }
        }
    }
}

@Composable
private fun DiscoveredDeviceRow(device: DiscoveredDevice, onAdd: () -> Unit) {
    NeonCard(accent = MaterialTheme.colorScheme.secondary, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    device.deviceId,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.devices_last_seen_format, formatRelative(device.lastSeenMillis)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            NeonPill(stringResource(R.string.garage_add_button), MaterialTheme.colorScheme.primary, selected = true, onClick = onAdd)
        }
    }
}

@Composable
private fun EmptyDevicesMessage(isScanning: Boolean) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.DirectionsCar,
                contentDescription = null,
                modifier = Modifier.height(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(if (isScanning) stringResource(R.string.devices_scanning) else stringResource(R.string.devices_no_cars_yet), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                if (isScanning) {
                    stringResource(R.string.devices_scanning_hint)
                } else {
                    stringResource(R.string.devices_no_cars_hint)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val DeviceEntity.carLabel: String?
    get() = listOfNotNull(brand?.takeIf { it.isNotBlank() }, model?.takeIf { it.isNotBlank() })
        .joinToString(" ")
        .ifBlank { null }

@Composable
private fun DeviceRow(device: DeviceEntity, onSelect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    // Phone-recorded tracks get the cyan "this device" accent, real cars the lime one.
    val accent = if (device.isLocal) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    NeonCard(
        accent = accent,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        onClick = onSelect,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (device.isLocal) {
                Icon(
                    Icons.Filled.Smartphone,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                device.carLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!device.isLocal) {
                    Text(
                        device.deviceId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!device.isLocal) {
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.devices_edit_car_content_description))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.devices_remove_content_description), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddDeviceDialog(
    lockedDeviceId: String?,
    onDismiss: () -> Unit,
    onConfirm: (deviceId: String, name: String) -> Unit,
) {
    var deviceId by remember { mutableStateOf(lockedDeviceId.orEmpty()) }
    var name by remember { mutableStateOf("") }
    val isLocked = lockedDeviceId != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.garage_add_car_dialog_title)) },
        text = {
            Column {
                if (!isLocked) {
                    Text(
                        stringResource(R.string.devices_paste_id_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = deviceId,
                    onValueChange = { deviceId = it },
                    label = { Text(stringResource(R.string.devices_field_device_id)) },
                    singleLine = true,
                    enabled = !isLocked,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.devices_field_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = deviceId.isNotBlank() && name.isNotBlank(),
                onClick = { onConfirm(deviceId.trim(), name.trim()) },
            ) { Text(stringResource(R.string.garage_add_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun EditCarDialog(
    device: DeviceEntity,
    onDismiss: () -> Unit,
    onConfirm: (name: String, brand: String?, model: String?, details: String?) -> Unit,
) {
    var name by remember { mutableStateOf(device.name) }
    var brand by remember { mutableStateOf(device.brand.orEmpty()) }
    var model by remember { mutableStateOf(device.model.orEmpty()) }
    var details by remember { mutableStateOf(device.details.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.car_detail_edit_car_title)) },
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
                OutlinedTextField(
                    value = brand,
                    onValueChange = { brand = it },
                    label = { Text(stringResource(R.string.devices_field_brand_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(R.string.devices_field_model_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    label = { Text(stringResource(R.string.devices_field_details_hint)) },
                    singleLine = true,
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
                        details.trim().ifBlank { null },
                    )
                },
            ) { Text(stringResource(R.string.car_detail_save_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
