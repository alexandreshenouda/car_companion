package com.carlauncher.companion.ui.nav

import android.content.Context
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.carlauncher.companion.R
import com.carlauncher.companion.data.AppContainer
import com.carlauncher.companion.ui.bluetooth.BluetoothTriggerScreen
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.common.SectionLabel
import com.carlauncher.companion.ui.devices.DevicesScreen
import com.carlauncher.companion.ui.settings.SettingsToggleRow

/**
 * Dev half of the navigation seam: the top-bar beta screens (Manage cars, Radar
 * trigger), beta top-bar icons, top-bar visibility state, and background settings.
 *
 * The prod flavor declares the same functions with inert/empty bodies, which is what makes these
 * routes and controls simply not exist there — `CompanionNavHost` calls them unconditionally and
 * never references [DevicesScreen]/[BluetoothTriggerScreen], neither of which is compiled into
 * the prod APK.
 */
private const val DEV_UI_PREFS_NAME = "dev_ui_prefs"
private const val KEY_TOP_BAR_ENABLED = "top_bar_enabled"

data class BetaTopBarState(
    val isAvailable: Boolean,
    val isTopBarEnabled: Boolean,
    val setTopBarEnabled: (Boolean) -> Unit,
)

@Composable
fun rememberBetaTopBarState(): BetaTopBarState {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(DEV_UI_PREFS_NAME, Context.MODE_PRIVATE) }
    var isEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean(KEY_TOP_BAR_ENABLED, true)) }
    return remember(isEnabled) {
        BetaTopBarState(
            isAvailable = true,
            isTopBarEnabled = isEnabled,
            setTopBarEnabled = { enabled ->
                isEnabled = enabled
                prefs.edit().putBoolean(KEY_TOP_BAR_ENABLED, enabled).apply()
            },
        )
    }
}

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
        Icon(Icons.Filled.DirectionsCar, contentDescription = stringResource(R.string.nav_title_devices))
    }
    IconButton(onClick = {
        navController.navigate(Destination.Settings.route) { launchSingleTop = true }
    }) {
        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.nav_title_settings))
    }
}

@Composable
fun BetaBackgroundSettings(container: AppContainer) {
    val settings = container.beta.backgroundFeatureSettings
    val firebaseListenersEnabled by settings.firebaseListenersEnabled.collectAsStateWithLifecycle()
    val backgroundRadarEnabled by settings.backgroundRadarEnabled.collectAsStateWithLifecycle()

    SectionLabel(stringResource(R.string.settings_background_section_label), tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.settings_background_section_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    SettingsToggleRow(
        title = stringResource(R.string.settings_firebase_listeners_label),
        description = stringResource(R.string.settings_firebase_listeners_hint),
        enabled = firebaseListenersEnabled,
        onToggle = settings::setFirebaseListenersEnabled,
    )
    Spacer(Modifier.height(12.dp))
    SettingsToggleRow(
        title = stringResource(R.string.settings_background_radar_label),
        description = stringResource(R.string.settings_background_radar_hint),
        enabled = backgroundRadarEnabled,
        onToggle = settings::setBackgroundRadarEnabled,
    )
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
