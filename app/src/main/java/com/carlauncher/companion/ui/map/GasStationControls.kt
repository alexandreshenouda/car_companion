package com.carlauncher.companion.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.carlauncher.companion.R
import com.carlauncher.companion.data.model.FuelType
import com.carlauncher.companion.data.model.GasStation
import com.carlauncher.companion.data.model.GasStationSource
import com.carlauncher.companion.data.repo.GasStationRepository
import com.carlauncher.companion.data.repo.SwissGasStationRepository
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.theme.NeonAmber
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay

private const val MAX_VISIBLE_STATIONS = 300
// Minimum zoom level to show individual stations (prevent visual clutter when zoomed out)
private const val INDIVIDUAL_STATION_MIN_ZOOM = 11.5
// Minimum zoom level to show Swiss aggregated clusters (TCS returns clusters starting at zoom 8)
private const val CLUSTER_MIN_ZOOM = 7.5

// Badge marker dimensions (dp-independent; drawn into a fixed pixel bitmap)
private const val BADGE_SIZE_PX = 72
private const val BADGE_TEXT_SIZE_PX = 22f

/**
 * Manages gas station overlay state for both French (offline SQLite) and Swiss (live TCS API)
 * stations. The two sources are fetched concurrently and merged into a single [stations] list.
 */
class GasStationOverlayState internal constructor(
    private val context: Context,
    private val stationMarkerIcon: Drawable?,
    private val onClusterExpand: ((GeoPoint) -> Unit)? = null,
) {
    internal var isEnabled by mutableStateOf(false)
    internal var selectedFuel by mutableStateOf<FuelType?>(null)
    internal var stations by mutableStateOf<List<GasStation>>(emptyList())
    internal var viewportVersion by mutableIntStateOf(0)

    /** Tracks the last focused cluster to allow a second tap when centered to zoom in and expand. */
    var lastFocusedClusterId by mutableStateOf<Long?>(null)
    internal var selectedStation by mutableStateOf<GasStation?>(null)

    private var cachedKey: GasStationOverlayCacheKey? = null
    private var cachedOverlays: List<Overlay> = emptyList()

    // Per-zoom cluster badge drawables, cached to avoid re-allocating on every redraw.
    private val clusterBadgeCache = mutableMapOf<Int, Drawable>()

    fun applyOverlays(view: MapView) {
        val key = GasStationOverlayCacheKey(view, isEnabled, selectedFuel, stations, viewportVersion)
        if (key != cachedKey) {
            cachedOverlays = buildOverlays(view)
            cachedKey = key
            selectedStation?.let { sel ->
                cachedOverlays.filterIsInstance<Marker>().firstOrNull {
                    (it.relatedObject as? GasStation)?.id == sel.id
                }?.showInfoWindow()
            }
        }
        view.overlays.addAll(cachedOverlays)
    }

    fun showStationInfoWindow(station: GasStation) {
        selectedStation = station
        cachedOverlays.filterIsInstance<Marker>().firstOrNull {
            (it.relatedObject as? GasStation)?.id == station.id
        }?.showInfoWindow()
    }

    private fun buildOverlays(view: MapView): List<Overlay> {
        val zoom = view.zoomLevelDouble
        if (!isEnabled || stations.isEmpty() || zoom < CLUSTER_MIN_ZOOM) {
            return emptyList()
        }
        val bounds = view.boundingBox
        val overlays = ArrayList<Overlay>(stations.size.coerceAtMost(MAX_VISIBLE_STATIONS))
        var shown = 0
        for (station in stations) {
            if (shown >= MAX_VISIBLE_STATIONS) break
            // Individual unclustered stations require zoom >= INDIVIDUAL_STATION_MIN_ZOOM
            if (!station.isCluster && zoom < INDIVIDUAL_STATION_MIN_ZOOM) continue
            if (!bounds.contains(station.lat, station.lon)) continue

            val icon: Drawable? = if (station.isCluster) {
                buildClusterBadge(station.pointCount)
            } else {
                stationMarkerIcon
            }

            val marker = Marker(view).apply {
                position = GeoPoint(station.lat, station.lon)
                title = station.title
                snippet = station.buildPricesSnippetHtml(selectedFuel)
                subDescription = station.buildSubDescriptionHtml()
                this.icon = icon
                infoWindow = NeonInfoWindow(view)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                relatedObject = station

                if (station.isCluster) {
                    setOnMarkerClickListener { m, map ->
                        val currentCenter = map.mapCenter
                        val isCentered = Math.abs(currentCenter.latitude - station.lat) < 0.002 &&
                            Math.abs(currentCenter.longitude - station.lon) < 0.002
                        if (isCentered && (m.isInfoWindowShown || lastFocusedClusterId == station.id)) {
                            m.closeInfoWindow()
                            lastFocusedClusterId = null
                            onClusterExpand?.invoke(GeoPoint(station.lat, station.lon))
                        } else {
                            lastFocusedClusterId = station.id
                            selectedStation = station
                            m.showInfoWindow()
                            map.controller.animateTo(GeoPoint(station.lat, station.lon))
                        }
                        true
                    }
                }
            }
            overlays.add(marker)
            shown++
        }
        return overlays
    }


    /**
     * Builds a circular badge [Drawable] with the station count drawn in the centre.
     * Results are cached by count value to avoid repeated [Bitmap] allocations.
     */
    private fun buildClusterBadge(count: Int): Drawable {
        return clusterBadgeCache.getOrPut(count.coerceAtMost(9999)) {
            val size = BADGE_SIZE_PX
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = NeonAmber.copy(alpha = 0.9f).toArgb()
                style = Paint.Style.FILL
            }
            val radius = size / 2f
            canvas.drawCircle(radius, radius, radius, bgPaint)

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawCircle(radius, radius, radius - 2f, borderPaint)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                textSize = BADGE_TEXT_SIZE_PX
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val label = if (count > 999) "${count / 1000}k" else count.toString()
            val textY = radius - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(label, radius, textY, textPaint)

            BitmapDrawable(context.resources, bitmap)
        }
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
    swissRepository: SwissGasStationRepository,
    mapView: MapView,
    mapMoveEvents: Flow<Unit>,
    getZoom: () -> Int,
    onClusterExpand: ((GeoPoint) -> Unit)? = null,
): GasStationOverlayState {
    val context = LocalContext.current
    val stationIcon = remember(context) {
        ContextCompat.getDrawable(context, R.drawable.ic_gas_station)
    }
    val state = remember(context, onClusterExpand) {
        GasStationOverlayState(context, stationIcon, onClusterExpand)
    }


    // Triggered when layer is toggled on/off or fuel filter changes.
    // Also re-runs when the settled map motion emits via mapMoveEvents.
    LaunchedEffect(mapView, state.isEnabled, state.selectedFuel) {
        if (!state.isEnabled) {
            state.stations = emptyList()
            state.viewportVersion++
            return@LaunchedEffect
        }

        // Immediate fetch for current viewport.
        state.stations = fetchBothSources(repository, swissRepository, mapView, state.selectedFuel, getZoom)
        state.viewportVersion++

        // Debounced refetch on map pan/zoom.
        mapMoveEvents.debounce(150).collect {
            if (state.isEnabled) {
                state.stations = fetchBothSources(repository, swissRepository, mapView, state.selectedFuel, getZoom)
                state.viewportVersion++
            }
        }
    }

    return state
}

/**
 * Fires both the French SQLite query and the Swiss HTTP fetch concurrently,
 * then merges the results into a single list (French first, Swiss appended).
 */
private suspend fun fetchBothSources(
    french: GasStationRepository,
    swiss: SwissGasStationRepository,
    mapView: MapView,
    fuelType: FuelType?,
    getZoom: () -> Int,
): List<GasStation> = coroutineScope {
    val bounds = mapView.boundingBox
    val zoom = getZoom()
    val frenchDeferred = async {
        french.pointsForViewport(
            minLat = bounds.latSouth,
            maxLat = bounds.latNorth,
            minLon = bounds.lonWest,
            maxLon = bounds.lonEast,
            fuelType = fuelType,
            limit = MAX_VISIBLE_STATIONS,
        )
    }
    val swissDeferred = async {
        swiss.pointsForViewport(
            minLat = bounds.latSouth,
            maxLat = bounds.latNorth,
            minLon = bounds.lonWest,
            maxLon = bounds.lonEast,
            zoom = zoom,
            fuelType = fuelType,
        )
    }
    frenchDeferred.await() + swissDeferred.await()
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
