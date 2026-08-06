package com.carlauncher.companion.ui.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.carlauncher.companion.R
import com.carlauncher.companion.data.BetaContainer
import com.carlauncher.companion.data.model.RADAR_TYPE_ICONS
import com.carlauncher.companion.data.model.RadarPoint
import com.carlauncher.companion.data.model.RadarSection
import com.carlauncher.companion.data.model.RadarType
import com.carlauncher.companion.ui.common.NeonPill
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

private const val RADAR_MARKER_TINT = 0xFFFFFFFF.toInt()

// Radars are rendered as real Markers (icon + built-in InfoWindow popup on tap), so — unlike a
// SimpleFastPointOverlay — the set actually drawn is capped to what's visible in the current
// viewport to avoid building thousands of Marker objects at low zoom levels.
private const val MAX_VISIBLE_RADARS = 400

// Cyan: distinct from the whole SpeedZone palette used by the history trail and from the
// phone marker's Google blue. The alpha is packed into the literal because Paint.setColor
// overwrites the alpha channel, so a separate outlinePaint.alpha would depend on ordering.
private const val SECTION_LINE_TINT = 0xAA00E5FF.toInt()
private const val SECTION_LINE_WIDTH = 12f

// Below this the sections are a few pixels long and only add clutter.
private const val SECTION_MIN_ZOOM = 7.0

/**
 * Dev half of the radar seam: all radar state, loading and map drawing that used to live inline in
 * [MapScreen]. The prod flavor declares the same three declarations doing nothing, so `MapScreen`
 * calls them unconditionally without ever naming a radar type — and no radar code, GPX asset or
 * background-location prompt reaches the prod APK.
 */
class RadarOverlayState internal constructor(
    private val context: Context,
    private val icons: Map<RadarType, Drawable?>,
) {
    // Radars are off by default; visibility is driven by whether any type is selected.
    internal var selectedTypes by mutableStateOf<Set<RadarType>>(emptySet())
    internal var points by mutableStateOf<List<RadarPoint>>(emptyList())

    // Unlike the radar points, sections are a single small asset — loaded all at once rather
    // than paged per viewport.
    internal var sections by mutableStateOf<List<RadarSection>>(emptyList())

    /** Bumped on every settled pan/zoom purely so the draw block below re-runs. */
    internal var viewportVersion by mutableStateOf(0)

    // Rebuilding up to MAX_VISIBLE_RADARS Markers is real work, and applyOverlays() below is
    // called on every AndroidView recomposition in MapScreen (e.g. every 5s phone-location
    // tick), not just on pan/zoom. Cache the built overlay list and only rebuild it when
    // something that actually changes its contents differs from the last build — points/
    // sections are only ever reassigned wholesale (not mutated), so this comparison is a cheap
    // reference check in the steady state, not a structural one.
    private var cachedKey: OverlayCacheKey? = null
    private var cachedOverlays: List<Overlay> = emptyList()

    /**
     * Adds the section bands and radar markers to [view]'s (already cleared) overlay list. Called
     * from `MapScreen`'s AndroidView update block before the history trail and the car/phone
     * markers, so osmdroid's list-order rendering keeps the section bands underneath them — they
     * are context for the two markers they connect, not the subject.
     */
    fun applyOverlays(view: MapView) {
        val key = OverlayCacheKey(view, selectedTypes, sections, points, viewportVersion)
        if (key != cachedKey) {
            cachedOverlays = buildOverlays(view)
            cachedKey = key
        }
        view.overlays.addAll(cachedOverlays)
    }

    private fun buildOverlays(view: MapView): List<Overlay> {
        val overlays = mutableListOf<Overlay>()
        if (RadarType.SECTION_CONTROL in selectedTypes &&
            sections.isNotEmpty() &&
            view.zoomLevelDouble >= SECTION_MIN_ZOOM
        ) {
            val bounds = view.boundingBox
            for (section in sections) {
                // Endpoint containment (as used for the markers below) would hide any
                // section long enough to span the screen with both radars off it.
                if (!section.intersects(bounds.latSouth, bounds.latNorth, bounds.lonWest, bounds.lonEast)) {
                    continue
                }
                val linePoints = ArrayList<GeoPoint>(section.line.size / 2)
                var i = 0
                while (i < section.line.size) {
                    linePoints.add(GeoPoint(section.line[i], section.line[i + 1]))
                    i += 2
                }
                overlays.add(
                    Polyline(view).apply {
                        setPoints(linePoints)
                        outlinePaint.color = SECTION_LINE_TINT
                        outlinePaint.strokeWidth = SECTION_LINE_WIDTH
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                        outlinePaint.isAntiAlias = true
                        // Built without routing available: this is the straight line
                        // between the two radars, not the road. Say so visually.
                        if (!section.routed) {
                            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f)
                        }
                        title = context.getString(RadarType.SECTION_CONTROL.labelRes)
                        snippet = context.getString(
                            R.string.radar_section_snippet_format,
                            section.country,
                            section.lengthMeters / 1000.0,
                        )
                    },
                )
            }
        }

        if (selectedTypes.isNotEmpty() && points.isNotEmpty()) {
            val bounds = view.boundingBox
            var shown = 0
            for (radar in points) {
                if (shown >= MAX_VISIBLE_RADARS) break
                if (radar.type !in selectedTypes) continue
                if (!bounds.contains(radar.lat, radar.lon)) continue
                overlays.add(
                    Marker(view).apply {
                        position = GeoPoint(radar.lat, radar.lon)
                        title = context.getString(radar.type.labelRes)
                        snippet = buildString {
                            append(radar.country)
                            radar.speedLimitKmh?.let {
                                append(context.getString(R.string.radar_point_speed_limit_format, it))
                            }
                        }
                        icon = icons[radar.type]
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    },
                )
                shown++
            }
        }
        return overlays
    }
}

/** Cache key for [RadarOverlayState.buildOverlays] — equality is reference-fast in the steady
 * state since [RadarOverlayState.points]/[RadarOverlayState.sections] are only ever reassigned
 * wholesale, never mutated in place. [view] (compared by the default reference equality —
 * MapView doesn't override equals) invalidates the cache on a device switch: RadarOverlayState
 * outlives any single MapView (it's `remember(context)`-scoped in [rememberRadarOverlays], while
 * `MapScreen` recreates its MapView per device), so a stale cache hit would otherwise hand back
 * Marker/Polyline instances built against an already-discarded MapView. */
private data class OverlayCacheKey(
    val view: MapView,
    val selectedTypes: Set<RadarType>,
    val sections: List<RadarSection>,
    val points: List<RadarPoint>,
    val viewportVersion: Int,
)

@OptIn(FlowPreview::class)
@Composable
fun rememberRadarOverlays(
    beta: BetaContainer,
    mapView: MapView,
    mapMoveEvents: Flow<Unit>,
    hasLocationPermission: Boolean,
): RadarOverlayState {
    val context = LocalContext.current
    val state = remember(context) {
        RadarOverlayState(
            context = context,
            icons = RADAR_TYPE_ICONS.mapValues { (_, res) ->
                ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(RADAR_MARKER_TINT) }
            },
        )
    }

    LaunchedEffect(state) {
        state.sections = beta.sectionRepository.sections()
    }

    // Radar markers are viewport-filtered (there are ~30k bundled points, too many to render as
    // individual Markers at once), so we need to know when the map has panned/zoomed. onScroll
    // fires continuously during a drag, so the recompute is debounced until motion settles.
    LaunchedEffect(mapView) {
        mapMoveEvents.debounce(150).collect {
            state.viewportVersion++
            val bounds = mapView.boundingBox
            state.points = beta.radarRepository.pointsForViewport(
                minLat = bounds.latSouth,
                maxLat = bounds.latNorth,
                minLon = bounds.lonWest,
                maxLon = bounds.lonEast,
            )
        }
    }

    RequestBackgroundLocation(hasLocationPermission)
    return state
}

/**
 * Background location is required (Android 10+) for
 * [com.carlauncher.companion.car.RadarAlertService] to legally start a "location" foreground
 * service when woken up by the car-started push while this app is fully backgrounded — without it,
 * Android throws a SecurityException at startForeground() rather than just silently limiting
 * accuracy. Must be requested as its own, separate prompt, strictly after foreground location is
 * granted (bundling it with the foreground request is rejected outright since Android 11).
 *
 * Prod has no background service to start, so it never asks for this at all.
 */
@Composable
private fun RequestBackgroundLocation(hasLocationPermission: Boolean) {
    val context = LocalContext.current
    var hasBackgroundLocationPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasBackgroundLocationPermission = granted }

    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission && !hasBackgroundLocationPermission &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            launcher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
}

/** The "Radars" pill and its per-type dropdown, dropped into MapScreen's bottom controls row. */
@Composable
fun RadarControls(state: RadarOverlayState) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        val radarAccent = MaterialTheme.colorScheme.tertiary
        val radarsOn = state.selectedTypes.isNotEmpty()
        NeonPill(
            text = stringResource(R.string.radar_pill_label),
            accent = radarAccent,
            selected = radarsOn,
            onClick = { menuExpanded = true },
            leading = {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = if (radarsOn) MaterialTheme.colorScheme.onPrimary else radarAccent,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            val allSelected = state.selectedTypes.size == RadarType.entries.size
            DropdownMenuItem(
                text = {
                Text(
                    if (allSelected) {
                        stringResource(R.string.radar_deselect_all)
                    } else {
                        stringResource(R.string.radar_select_all)
                    },
                )
            },
                onClick = {
                    state.selectedTypes = if (allSelected) emptySet() else RadarType.entries.toSet()
                },
            )
            HorizontalDivider()
            RadarType.entries.forEach { type ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = type in state.selectedTypes, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(type.labelRes))
                        }
                    },
                    onClick = {
                        state.selectedTypes = if (type in state.selectedTypes) {
                            state.selectedTypes - type
                        } else {
                            state.selectedTypes + type
                        }
                    },
                )
            }
        }
    }
}
