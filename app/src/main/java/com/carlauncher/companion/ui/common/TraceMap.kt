package com.carlauncher.companion.ui.common

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.carlauncher.companion.R
import com.carlauncher.companion.data.db.LocationPointEntity
import com.carlauncher.companion.ui.map.CartoDarkMatterTileSource
import com.carlauncher.companion.ui.map.awaitFirstLayout
import com.carlauncher.companion.util.buildSpeedSegments
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/**
 * Compact, non-live preview of a GPS trace — same tile style and speed-colored trail as
 * [com.carlauncher.companion.ui.map.MapScreen]'s history trail. Shared by the local event
 * detail screen and the shared/read-only one ([com.carlauncher.companion.ui.feed.SharedEventDetailScreen])
 * so a friend's shared trace renders through the identical code path as one of the viewer's own
 * events, not a parallel reimplementation of it. Tapping it opens an almost-full-screen dialog
 * with the same trail so the trajectory can actually be read.
 */
@Composable
fun TraceMap(points: List<LocationPointEntity>, modifier: Modifier = Modifier, expandable: Boolean = true) {
    var fullScreen by remember { mutableStateOf(false) }

    NeonCard(accent = MaterialTheme.colorScheme.secondary, modifier = modifier, topBar = false) {
        Box(Modifier.fillMaxSize()) {
            TraceMapCanvas(points = points, modifier = Modifier.fillMaxSize())
            // Overlaid *above* the MapView (rather than a NeonCard-level onClick) because the
            // MapView consumes touch for its own pan/zoom gestures and would otherwise swallow
            // taps before they reach an ancestor's clickable.
            if (expandable && points.isNotEmpty()) {
                Box(Modifier.matchParentSize().clickable { fullScreen = true })
            }
        }
    }

    if (fullScreen) {
        Dialog(
            onDismissRequest = { fullScreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize().padding(12.dp)) {
                NeonCard(accent = MaterialTheme.colorScheme.secondary, modifier = Modifier.fillMaxSize(), topBar = false) {
                    TraceMapCanvas(points = points, modifier = Modifier.fillMaxSize())
                }
                IconButton(onClick = { fullScreen = false }, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                }
            }
        }
    }
}

@Composable
private fun TraceMapCanvas(points: List<LocationPointEntity>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }

    DisposableEffect(mapView) {
        mapView.setTileSource(CartoDarkMatterTileSource)
        val contrast = 1.15f
        val brightness = 25f
        mapView.overlayManager.tilesOverlay.setColorFilter(
            ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        contrast, 0f, 0f, 0f, brightness,
                        0f, contrast, 0f, 0f, brightness,
                        0f, 0f, contrast, 0f, brightness,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            ),
        )
        mapView.setMultiTouchControls(true)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mapView.minZoomLevel = 3.0
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    LaunchedEffect(points) {
        if (points.isEmpty()) return@LaunchedEffect
        mapView.awaitFirstLayout()
        if (points.size == 1) {
            mapView.controller.setZoom(15.0)
            mapView.controller.setCenter(GeoPoint(points[0].lat, points[0].lng))
        } else {
            val bbox = BoundingBox.fromGeoPoints(points.map { GeoPoint(it.lat, it.lng) })
            mapView.zoomToBoundingBox(bbox, false, 96)
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            view.overlays.clear()
            for (segment in buildSpeedSegments(points)) {
                view.overlays.add(
                    Polyline(view).apply {
                        setPoints(segment.points)
                        outlinePaint.color = segment.color
                        outlinePaint.strokeWidth = 8f
                    },
                )
            }
            view.invalidate()
        },
    )
}
