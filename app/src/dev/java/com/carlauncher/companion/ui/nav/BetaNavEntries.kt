package com.carlauncher.companion.ui.nav

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.carlauncher.companion.R
import com.carlauncher.companion.data.AppContainer
import com.carlauncher.companion.ui.bluetooth.BluetoothTriggerScreen
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.devices.DevicesScreen

/**
 * Dev half of the navigation seam: the two top-bar-only beta screens (Manage cars, Radar
 * trigger) and the top-bar icons that reach them.
 *
 * The prod flavor declares the same three functions with empty bodies, which is what makes these
 * routes and icons simply not exist there — `CompanionNavHost` calls them unconditionally and
 * never references [DevicesScreen]/[BluetoothTriggerScreen], neither of which is compiled into
 * the prod APK.
 */
fun NavGraphBuilder.betaDestinations(navController: NavHostController, container: AppContainer) {
    composable(Destination.Devices.route) {
        DevicesScreen(
            deviceRepository = container.deviceRepository,
            trackRepository = container.trackRepository,
            onDeviceSelected = { navController.popBackStack() },
        )
    }
    composable(Destination.BluetoothTrigger.route) {
        BluetoothTriggerScreen(store = container.beta.bluetoothTriggerStore)
    }
}

@Composable
fun RowScope.BetaTopBarIcons(navController: NavHostController) {
    IconButton(onClick = {
        navController.navigate(Destination.BluetoothTrigger.route) { launchSingleTop = true }
    }) {
        Icon(Icons.Filled.Bluetooth, contentDescription = stringResource(R.string.nav_title_bluetooth_trigger))
    }
    IconButton(onClick = {
        navController.navigate(Destination.Devices.route) { launchSingleTop = true }
    }) {
        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.nav_title_devices))
    }
}

/** Shown under the "no car selected" hint — only meaningful where cars can actually be added. */
@Composable
fun BetaAddCarAction(navController: NavHostController) {
    Spacer(Modifier.height(12.dp))
    NeonPill(
        text = stringResource(R.string.garage_add_car_dialog_title),
        accent = MaterialTheme.colorScheme.primary,
        selected = true,
        onClick = { navController.navigate(Destination.Devices.route) { launchSingleTop = true } },
    )
}
