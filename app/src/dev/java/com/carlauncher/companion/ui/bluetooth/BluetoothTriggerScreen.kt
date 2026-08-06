package com.carlauncher.companion.ui.bluetooth

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.carlauncher.companion.R
import com.carlauncher.companion.data.bluetooth.BluetoothTriggerStore

/** A bonded device, flattened out of [android.bluetooth.BluetoothDevice] so reads can't throw later. */
private data class BondedDevice(val address: String, val name: String)

/**
 * Lets the user pick which paired Bluetooth devices mean "I'm in the car" — connecting to one
 * starts background radar tracking, disconnecting from the last one stops it. See
 * [com.carlauncher.companion.car.CarBluetoothReceiver].
 */
@Composable
fun BluetoothTriggerScreen(store: BluetoothTriggerStore) {
    val context = LocalContext.current

    var hasPermission by remember { mutableStateOf(hasBluetoothPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    var selected by remember { mutableStateOf(store.triggerAddresses()) }
    var bonded by remember { mutableStateOf(emptyList<BondedDevice>()) }
    var bluetoothOff by remember { mutableStateOf(false) }

    // Prompt once on arrival only. Re-prompting on every resume would be pointless anyway — after
    // two refusals Android returns "denied" without showing anything — and the hint below leaves a
    // button to ask again deliberately.
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(bluetoothPermission())
    }

    // Re-read on every resume rather than once: pairing the car happens in system settings, so the
    // common flow is to leave this screen, pair, and come straight back to it.
    LifecycleResumeEffect(hasPermission) {
        if (hasPermission) {
            val adapter = ContextCompat.getSystemService(context, BluetoothManager::class.java)?.adapter
            bluetoothOff = adapter?.isEnabled == false
            bonded = bondedDevices(context)
        }
        onPauseOrDispose { }
    }

    fun toggle(address: String) {
        selected = if (address in selected) selected - address else selected + address
        store.setTriggerAddresses(selected)
    }

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.nav_title_bluetooth_trigger), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.bt_trigger_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (selected.isEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.bt_trigger_nothing_ticked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            HorizontalDivider()
        }

        when {
            !hasPermission -> item {
                Hint(
                    stringResource(R.string.bt_trigger_permission_hint),
                    actionLabel = stringResource(R.string.bt_trigger_grant_permission),
                    onAction = { permissionLauncher.launch(bluetoothPermission()) },
                )
            }

            bluetoothOff -> item { Hint(stringResource(R.string.bt_trigger_bluetooth_off)) }

            bonded.isEmpty() -> item { Hint(stringResource(R.string.bt_trigger_no_paired_devices)) }

            else -> items(bonded, key = { it.address }) { device ->
                DeviceRow(
                    device = device,
                    checked = device.address in selected,
                    onToggle = { toggle(device.address) },
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(device: BondedDevice, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                device.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                device.address,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun Hint(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.padding(16.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * BLUETOOTH_CONNECT is the Android 12+ runtime replacement for the old install-time BLUETOOTH
 * permission, which is all that's needed (and all that exists) below API 31.
 */
private fun bluetoothPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Manifest.permission.BLUETOOTH_CONNECT
    } else {
        Manifest.permission.BLUETOOTH
    }

private fun hasBluetoothPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

private fun bondedDevices(context: Context): List<BondedDevice> {
    val adapter = ContextCompat.getSystemService(context, BluetoothManager::class.java)?.adapter ?: return emptyList()
    // Both bondedDevices and name are guarded by BLUETOOTH_CONNECT; the permission can be revoked
    // between the check above and here, and the failure mode is a SecurityException, not null.
    return try {
        adapter.bondedDevices.orEmpty().map { BondedDevice(it.address, it.name ?: it.address) }
            .sortedBy { it.name.lowercase() }
    } catch (e: SecurityException) {
        emptyList()
    }
}
