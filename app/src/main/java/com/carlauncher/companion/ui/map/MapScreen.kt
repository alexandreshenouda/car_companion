package com.carlauncher.companion.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.location.LocationManager
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlauncher.companion.R
import com.carlauncher.companion.car.LocalTrackingService
import com.carlauncher.companion.data.BetaContainer
import com.carlauncher.companion.data.MapFocusRequestHolder
import com.carlauncher.companion.data.db.LocationPointEntity
import com.carlauncher.companion.data.model.HistoryRange
import com.carlauncher.companion.data.model.SpeedZone
import com.carlauncher.companion.data.repo.DeviceRepository
import com.carlauncher.companion.data.repo.TrackRepository
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.common.RangeSelector
import com.carlauncher.companion.ui.common.SectionLabel
import com.carlauncher.companion.util.buildSpeedSegments
import com.carlauncher.companion.util.formatAbsolute
import com.carlauncher.companion.util.formatRelative
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

private const val PHONE_MARKER_TINT = 0xFF4285F4.toInt()

@SuppressLint("MissingPermission")
@Composable
fun MapScreen(
    deviceId: String,
    trackRepository: TrackRepository,
    deviceRepository: DeviceRepository,
    focusRequestHolder: MapFocusRequestHolder,
    beta: BetaContainer,
    onShare: (HistoryRange) -> Unit,
) {
    val context = LocalContext.current
    val isLocalDevice = deviceId == DeviceRepository.LOCAL_DEVICE_ID
    val isRecording by deviceRepository.observeLocalRecordingActive().collectAsStateWithLifecycle(initialValue = false)
    val latestPoint by trackRepository.observeLatestPoint(deviceId).collectAsStateWithLifecycle(initialValue = null)
    val focusRequest by focusRequestHolder.request.collectAsStateWithLifecycle()

    var showHistory by remember { mutableStateOf(true) }
    var historyRange by remember { mutableStateOf(HistoryRange.LAST_7_DAYS) }
    var historyPoints by remember { mutableStateOf<List<LocationPointEntity>>(emptyList()) }
    var hasCenteredOnce by remember { mutableStateOf(false) }
    // While true, the map re-centers on the car whenever it reports a new position. Cleared as
    // soon as the user drags the map themselves; re-enabled by tapping back onto the car's card.
    var followCar by remember { mutableStateOf(true) }
    var focusedPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var phoneLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasLocationPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // Tails Firestore for as long as this screen stays composed; detaches automatically
    // when the coroutine is cancelled (navigating away cancels the LaunchedEffect). Skipped for
    // "This phone": it has no Firestore doc — its points come from LocalTrackingService instead.
    LaunchedEffect(deviceId) {
        if (!isLocalDevice) trackRepository.liveUpdates(deviceId).collect { }
    }

    LaunchedEffect(deviceId, showHistory, historyRange, latestPoint) {
        historyPoints = if (showHistory) trackRepository.pointsInRange(deviceId, historyRange) else emptyList()
    }

    val mapView = remember(deviceId) { MapView(context) }
    val accentColor = MaterialTheme.colorScheme.primary.toArgb()

    // historyPoints only actually changes on sync/range/device switches, but the AndroidView
    // update block below re-runs far more often (every 5s phone-location tick, every new GPS
    // fix) — without this, the whole segment list was rebuilt from scratch on every one of those.
    val historySegments = remember(historyPoints) { buildSpeedSegments(historyPoints) }
    val carMarkerIcon = remember(accentColor) {
        ContextCompat.getDrawable(context, R.drawable.ic_car_marker)?.mutate()?.apply { setTint(accentColor) }
    }
    val phoneMarkerIcon = remember {
        ContextCompat.getDrawable(context, R.drawable.ic_phone_marker)?.mutate()?.apply { setTint(PHONE_MARKER_TINT) }
    }

    // Radar overlays are viewport-filtered and so need to know when the map has panned/zoomed.
    // onScroll fires continuously during a drag, so the seam debounces this until motion settles.
    // The flow itself stays here because the stable recenter/follow paths emit into it too.
    // Resolved here, not inside the AndroidView `update` lambda below, because that lambda
    // is a plain callback (not @Composable) and can't call stringResource itself.
    val lastKnownPositionLabel = stringResource(R.string.map_marker_last_known_position)
    val markerSnippet = latestPoint?.let { p -> stringResource(R.string.map_marker_snippet_format, p.speedKmh, formatAbsolute(p.ts)) }
    val yourPhoneLabel = stringResource(R.string.map_marker_your_phone)
    val selectedPointLabel = stringResource(R.string.map_marker_selected_point)

    val mapMoveEvents = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST) }
    // Radars (and the background-location grant they need) exist in the dev flavor only — this
    // returns an inert state object in prod.
    val radarOverlays = rememberRadarOverlays(beta, mapView, mapMoveEvents, hasLocationPermission)

    DisposableEffect(mapView) {
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                mapMoveEvents.tryEmit(Unit)
                return false
            }
            override fun onZoom(event: ZoomEvent?): Boolean {
                mapMoveEvents.tryEmit(Unit)
                return false
            }
        }
        mapView.addMapListener(listener)
        onDispose { mapView.removeMapListener(listener) }
    }

    LaunchedEffect(focusRequest) {
        focusRequest?.let { req ->
            followCar = false
            focusedPoint = GeoPoint(req.lat, req.lng)
            mapView.controller.setZoom(16.0)
            mapView.controller.animateTo(GeoPoint(req.lat, req.lng))
            hasCenteredOnce = true
            focusRequestHolder.consume()
            mapMoveEvents.tryEmit(Unit)
        }
    }

    // Only a real finger-drag should break auto-follow — our own programmatic animateTo/setCenter
    // calls dispatch MapListener scroll events too, but never touch events, so they're unaffected.
    DisposableEffect(mapView) {
        val gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                    followCar = false
                    return false
                }
            },
        )
        mapView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
        onDispose { mapView.setOnTouchListener(null) }
    }

    // Keyed to the lifecycle, not to composition: backgrounding the app stops the Activity but
    // never leaves composition, so a DisposableEffect here would keep GPS running at 5s/10m for as
    // long as the process lived — the phone marker is only ever visible while this screen is
    // resumed, so that was pure battery drain.
    LifecycleResumeEffect(hasLocationPermission) {
        val locationManager = ContextCompat.getSystemService(context, LocationManager::class.java)
        if (!hasLocationPermission || locationManager == null) return@LifecycleResumeEffect onPauseOrDispose {}

        val listener = android.location.LocationListener { location ->
            phoneLocation = GeoPoint(location.latitude, location.longitude)
        }
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) return@LifecycleResumeEffect onPauseOrDispose {}

        locationManager.getLastKnownLocation(provider)?.let {
            phoneLocation = GeoPoint(it.latitude, it.longitude)
        }
        locationManager.requestLocationUpdates(provider, 5000L, 10f, listener)
        onPauseOrDispose { locationManager.removeUpdates(listener) }
    }

    DisposableEffect(mapView) {
        mapView.setTileSource(CartoDarkMatterTileSource)
        // dark_all's baked-in label/road color is quite subdued; boost brightness+contrast on
        // the raster tiles themselves so street names stay legible against the dark theme.
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
        // The on-screen +/- buttons osmdroid draws while panning collide with our own
        // overlay controls at the bottom of the screen — pinch/double-tap zoom still work.
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mapView.minZoomLevel = 3.0
        mapView.controller.setZoom(14.0)
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.overlays.clear()

                // First, so osmdroid's list-order rendering keeps the section bands and radar
                // markers underneath the history trail and the car/phone markers. No-op in prod.
                radarOverlays.applyOverlays(view)

                if (showHistory && historyPoints.size > 1) {
                    for (segment in historySegments) {
                        view.overlays.add(
                            Polyline(view).apply {
                                setPoints(segment.points)
                                outlinePaint.color = segment.color
                                outlinePaint.strokeWidth = 6f
                            },
                        )
                    }
                }

                latestPoint?.let { p ->
                    val marker = Marker(view).apply {
                        position = GeoPoint(p.lat, p.lng)
                        title = lastKnownPositionLabel
                        snippet = markerSnippet
                        icon = carMarkerIcon
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    }
                    view.overlays.add(marker)
                    if (!hasCenteredOnce) {
                        view.controller.setCenter(marker.position)
                        hasCenteredOnce = true
                        mapMoveEvents.tryEmit(Unit)
                    } else if (followCar) {
                        view.controller.animateTo(marker.position)
                    }
                }

                phoneLocation?.let { loc ->
                    val marker = Marker(view).apply {
                        position = loc
                        title = yourPhoneLabel
                        icon = phoneMarkerIcon
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    }
                    view.overlays.add(marker)
                }

                focusedPoint?.let { point ->
                    val marker = Marker(view).apply {
                        position = point
                        title = selectedPointLabel
                    }
                    view.overlays.add(marker)
                }

                view.invalidate()
            },
        )

        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallFloatingActionButton(
                onClick = {
                    if (!hasLocationPermission) {
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    } else {
                        phoneLocation?.let {
                            followCar = false
                            focusedPoint = null
                            mapView.controller.setZoom(16.0)
                            mapView.controller.animateTo(it)
                            mapMoveEvents.tryEmit(Unit)
                        }
                    }
                },
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.map_recenter_content_description))
            }
            SmallFloatingActionButton(onClick = { onShare(historyRange) }) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.map_share_trip_content_description))
            }
            if (isLocalDevice) {
                SmallFloatingActionButton(
                    onClick = {
                        if (!hasLocationPermission) {
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        } else if (isRecording) {
                            context.stopService(Intent(context, LocalTrackingService::class.java))
                        } else {
                            ContextCompat.startForegroundService(context, Intent(context, LocalTrackingService::class.java))
                        }
                    },
                    containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                        contentDescription = if (isRecording) {
                            stringResource(R.string.map_stop_recording_content_description)
                        } else {
                            stringResource(R.string.map_record_location_content_description)
                        },
                    )
                }
            }
        }

        if (showHistory) {
            SpeedLegend(modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val liveAccent = latestPoint?.let { Color(SpeedZone.forSpeed(it.speedKmh).color) }
                ?: MaterialTheme.colorScheme.outline
            NeonCard(
                accent = liveAccent,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    latestPoint?.let {
                        followCar = true
                        focusedPoint = null
                        mapView.controller.setZoom(16.0)
                        mapView.controller.animateTo(GeoPoint(it.lat, it.lng))
                        mapMoveEvents.tryEmit(Unit)
                    }
                },
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    val p = latestPoint
                    if (p == null) {
                        Text(stringResource(R.string.map_no_position_data), style = MaterialTheme.typography.bodyLarge)
                    } else {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "${p.speedKmh}",
                                style = MaterialTheme.typography.displayMedium,
                                color = liveAccent,
                            )
                            Spacer(Modifier.width(8.dp))
                            SectionLabel(stringResource(R.string.map_speed_unit_kmh), tint = liveAccent, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        Text(
                            stringResource(R.string.map_last_update_format, formatRelative(p.ts), formatAbsolute(p.ts)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonPill(
                    text = stringResource(R.string.map_history_trail_label),
                    accent = MaterialTheme.colorScheme.secondary,
                    selected = showHistory,
                    onClick = { showHistory = !showHistory },
                )

                // Renders nothing in prod.
                RadarControls(radarOverlays)
            }

            if (showHistory) {
                RangeSelector(selected = historyRange, onSelect = { historyRange = it })
            }
        }
    }
}

@Composable
private fun SpeedLegend(modifier: Modifier = Modifier) {
    NeonCard(
        accent = MaterialTheme.colorScheme.secondary,
        modifier = modifier,
        glow = false,
        topBar = false,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            SpeedZone.entries.forEach { zone ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(2.dp)) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(Color(zone.color), CircleShape),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.map_legend_speed_format, zone.label), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
