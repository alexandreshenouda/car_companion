package com.carlauncher.companion.ui.map

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.carlauncher.companion.R
import com.carlauncher.companion.data.model.FuelType
import com.carlauncher.companion.data.model.GasStation
import com.carlauncher.companion.data.repo.GasStationRepository
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.theme.NeonAmber
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay

private const val MAX_VISIBLE_STATIONS = 200
private const val STATION_MIN_ZOOM = 11.5

/**
 * Manages gas station overlay state, dynamic viewport fetching, and map marker caching.
 */
class GasStationOverlayState internal constructor(
    private val context: Context,
    private val markerIcon: Drawable?,
) {
    internal var isEnabled by mutableStateOf(false)
    internal var selectedFuel by mutableStateOf<FuelType?>(null)
    internal var stations by mutableStateOf<List<GasStation>>(emptyList())
    internal var viewportVersion by mutableIntStateOf(0)

    private var cachedKey: GasStationOverlayCacheKey? = null
    private var cachedOverlays: List<Overlay> = emptyList()

    fun applyOverlays(view: MapView) {
        val key = GasStationOverlayCacheKey(view, isEnabled, selectedFuel, stations, viewportVersion)
        if (key != cachedKey) {
            cachedOverlays = buildOverlays(view)
            cachedKey = key
        }
        view.overlays.addAll(cachedOverlays)
    }

    fun showStationInfoWindow(station: GasStation) {
        cachedOverlays.filterIsInstance<Marker>().firstOrNull {
            it.position.latitude == station.lat && it.position.longitude == station.lon
        }?.showInfoWindow()
    }

    private fun buildOverlays(view: MapView): List<Overlay> {
        if (!isEnabled || stations.isEmpty() || view.zoomLevelDouble < STATION_MIN_ZOOM) {
            return emptyList()
        }
        val bounds = view.boundingBox
        val overlays = ArrayList<Overlay>(stations.size.coerceAtMost(MAX_VISIBLE_STATIONS))
        var shown = 0
        for (station in stations) {
            if (shown >= MAX_VISIBLE_STATIONS) break
            if (!bounds.contains(station.lat, station.lon)) continue

            val marker = Marker(view).apply {
                position = GeoPoint(station.lat, station.lon)
                title = station.title
                snippet = station.buildPricesSnippetHtml(selectedFuel)
                subDescription = station.buildSubDescriptionHtml()
                icon = markerIcon
                infoWindow = NeonInfoWindow(view)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                relatedObject = station
            }
            overlays.add(marker)
            shown++
        }
        return overlays
    }
}

private data class GasStationOverlayCacheKey(
    val view: MapView,
    val isEnabled: Boolean,
    val selectedFuel: FuelType?,
    val stations: List<GasStation>,
    val viewportVersion: Int,
)

@OptIn(FlowPreview::class)
@Composable
fun rememberGasStationOverlays(
    repository: GasStationRepository,
    mapView: MapView,
    mapMoveEvents: Flow<Unit>,
): GasStationOverlayState {
    val context = LocalContext.current
    val markerIcon = remember(context) {
        ContextCompat.getDrawable(context, R.drawable.ic_gas_station)
    }
    val state = remember(context) {
        GasStationOverlayState(context, markerIcon)
    }

    // Trigger viewport fetch on settled pan/zoom motion
    LaunchedEffect(mapView, state.isEnabled, state.selectedFuel) {
        if (!state.isEnabled) {
            state.stations = emptyList()
            state.viewportVersion++
            return@LaunchedEffect
        }

        // Immediate fetch when toggled on or fuel changed
        val bounds = mapView.boundingBox
        state.stations = repository.pointsForViewport(
            minLat = bounds.latSouth,
            maxLat = bounds.latNorth,
            minLon = bounds.lonWest,
            maxLon = bounds.lonEast,
            fuelType = state.selectedFuel,
            limit = MAX_VISIBLE_STATIONS,
        )
        state.viewportVersion++

        // Debounced fetch on camera movement
        mapMoveEvents.debounce(150).collect {
            if (state.isEnabled) {
                val currentBounds = mapView.boundingBox
                state.stations = repository.pointsForViewport(
                    minLat = currentBounds.latSouth,
                    maxLat = currentBounds.latNorth,
                    minLon = currentBounds.lonWest,
                    maxLon = currentBounds.lonEast,
                    fuelType = state.selectedFuel,
                    limit = MAX_VISIBLE_STATIONS,
                )
                state.viewportVersion++
            }
        }
    }

    return state
}

/**
 * Dropdown button placed next to the radar button in MapScreen's bottom controls row.
 */
@Composable
fun GasStationControls(
    state: GasStationOverlayState,
    hasData: Boolean = true,
    onOpenSettings: () -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showNoDataDialog by remember { mutableStateOf(false) }

    val pillLabel = when {
        !state.isEnabled -> stringResource(R.string.gas_station_pill_label)
        state.selectedFuel == null -> stringResource(R.string.gas_station_all_fuels)
        else -> state.selectedFuel!!.canonicalName
    }

    Box {
        NeonPill(
            text = pillLabel,
            accent = NeonAmber,
            selected = state.isEnabled,
            onClick = {
                if (hasData) {
                    menuExpanded = true
                } else {
                    showNoDataDialog = true
                }
            },
            leading = {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = if (state.isEnabled) MaterialTheme.colorScheme.onPrimary else NeonAmber,
                    modifier = Modifier.size(16.dp),
                )
            },
        )

        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            if (state.isEnabled) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.gas_station_hide),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        state.isEnabled = false
                        menuExpanded = false
                    },
                )
                HorizontalDivider()
            }

            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = state.isEnabled && state.selectedFuel == null,
                            onClick = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.gas_station_all_fuels))
                    }
                },
                onClick = {
                    state.selectedFuel = null
                    state.isEnabled = true
                    menuExpanded = false
                },
            )
            HorizontalDivider()

            FuelType.entries.forEach { fuel ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = state.isEnabled && state.selectedFuel == fuel,
                                onClick = null,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(fuel.labelRes))
                        }
                    },
                    onClick = {
                        state.selectedFuel = fuel
                        state.isEnabled = true
                        menuExpanded = false
                    },
                )
            }
        }
    }

    if (showNoDataDialog) {
        AlertDialog(
            onDismissRequest = { showNoDataDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.gas_station_no_data_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.gas_station_no_data_dialog_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNoDataDialog = false
                        onOpenSettings()
                    },
                ) {
                    Text(stringResource(R.string.gas_station_no_data_dialog_settings_button))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showNoDataDialog = false },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
