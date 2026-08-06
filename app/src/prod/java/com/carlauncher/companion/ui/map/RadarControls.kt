package com.carlauncher.companion.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.carlauncher.companion.data.BetaContainer
import kotlinx.coroutines.flow.Flow
import org.osmdroid.views.MapView

/**
 * Prod half of the radar seam — see the dev flavor's `RadarControls` for the real thing. Radars
 * aren't part of this build: nothing to load, nothing to draw, no toggle, and no
 * ACCESS_BACKGROUND_LOCATION prompt (there is no background service that would need it).
 */
class RadarOverlayState internal constructor() {
    fun applyOverlays(view: MapView) = Unit
}

@Composable
@Suppress("UNUSED_PARAMETER")
fun rememberRadarOverlays(
    beta: BetaContainer,
    mapView: MapView,
    mapMoveEvents: Flow<Unit>,
    hasLocationPermission: Boolean,
): RadarOverlayState = remember { RadarOverlayState() }

@Composable
@Suppress("UNUSED_PARAMETER")
fun RadarControls(state: RadarOverlayState) = Unit
