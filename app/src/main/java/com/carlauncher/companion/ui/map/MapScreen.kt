package com.carlauncher.companion.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.location.Location
import android.location.LocationManager
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.flowWithLifecycle
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
import kotlinx.coroutines.delay
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

/** Zoom the map snaps to whenever the user explicitly asks to be taken somewhere. */
private const val FOCUS_ZOOM = 16.0

/** A fix older than this is considered stale enough that any newer one supersedes it. */
private const val LOCATION_STALE_MS = 30_000L

/** How long a recenter tap waits for a first fix before giving up rather than spinning forever. */
private const val LOCATION_WAIT_TIMEOUT_MS = 20_000L

/**
 * One "take the camera there" order. [serial] makes two consecutive requests for the *same* point
 * distinct, so tapping recenter twice in a row re-fires instead of being swallowed as no state
 * change.
 */
private data class CameraRequest(val point: GeoPoint, val zoom: Double, val serial: Int)

private data class FocusedPoint(
    val geoPoint: GeoPoint,
    val speedKmh: Int? = null,
    val ts: Long? = null,
)

/**
 * Whether [candidate] should replace [current] as the phone's position. We listen to GPS *and*
 * network, so fixes arrive interleaved and out of order: prefer the newer one once the old one has
 * gone stale, otherwise keep whichever is more accurate.
 */
private fun isBetterFix(candidate: Location, current: Location?): Boolean {
    if (current == null) return true
    val newerBy = candidate.time - current.time
    if (newerBy > LOCATION_STALE_MS) return true
    if (newerBy < 0) return false
    if (!current.hasAccuracy()) return true
    if (!candidate.hasAccuracy()) return false
    return candidate.accuracy <= current.accuracy
}

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
    var focusedPoint by remember { mutableStateOf<FocusedPoint?>(null) }
    var phoneLocation by remember { mutableStateOf<GeoPoint?>(null) }
    // Set when the user asks to be located before the first fix has arrived; the request is then
    // honoured as soon as one does, instead of the tap silently doing nothing.
    var awaitingLocationFix by remember { mutableStateOf(false) }
    var cameraRequest by remember { mutableStateOf<CameraRequest?>(null) }
    var cameraSerial by remember { mutableIntStateOf(0) }
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

    // Tails Firestore while this screen is both composed and resumed. flowWithLifecycle detaches
    // the Firestore listener as soon as the Activity backgrounds (screen off, home button, another
    // app) and resubscribes on resume — without it the listener stayed registered for as long as
    // the process lived, since backgrounding stops the Activity but never leaves composition (the
    // same pattern the phone-location listener below was fixed for). Skipped for "This phone": it
    // has no Firestore doc — its points come from LocalTrackingService instead.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(deviceId, lifecycleOwner) {
        if (!isLocalDevice) {
            trackRepository.liveUpdates(deviceId)
                .flowWithLifecycle(lifecycleOwner.lifecycle, Lifecycle.State.RESUMED)
                .collect { }
        }
    }

    LaunchedEffect(deviceId, showHistory, historyRange, latestPoint) {
        historyPoints = if (showHistory) trackRepository.pointsInRange(deviceId, historyRange) else emptyList()
    }

    // Deliberately NOT keyed on deviceId: AndroidView's factory below only ever runs once, so a
    // fresh MapView here on every device switch would leave the on-screen view (and everything
    // that manipulates it — camera effects, the touch listener, tile setup) pointed at a new,
    // never-attached instance while the real view silently stops receiving any of it.
    val mapView = remember { MapView(context) }
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
    val neonInfoWindow = remember(mapView) { NeonInfoWindow(mapView) }

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

    // Every deliberate "take me there" goes through here instead of poking mapView.controller
    // straight from a click lambda. Two reasons, both of which used to make a recenter tap look
    // like it did nothing:
    //  - a controller call made before the MapView's first Android layout pass is not executed,
    //    it is queued in osmdroid's ReplayController and replayed at first layout, so it could be
    //    swallowed or fired at the wrong moment. The effect below waits for layout first.
    //  - marking hasCenteredOnce here means the one-shot "centre on the car" below can no longer
    //    fire afterwards and yank the map back off whatever the user just asked for (that one
    //    triggers on latestPoint's *first* arrival, which can easily be seconds after the app
    //    opened, i.e. right after the user panned around and hit recenter).
    val moveCameraTo: (GeoPoint) -> Unit = { point ->
        hasCenteredOnce = true
        cameraSerial += 1
        cameraRequest = CameraRequest(point, FOCUS_ZOOM, cameraSerial)
    }

    LaunchedEffect(cameraRequest) {
        val request = cameraRequest ?: return@LaunchedEffect
        mapView.awaitFirstLayout()
        // osmdroid's animateTo() never actually cancels a still-running previous animator (it
        // only resets its own bookkeeping), so back-to-back animateTo calls within ~1s leave two
        // ValueAnimators fighting over the camera and the recenter can lose. stopAnimation(true)
        // jumps any in-flight animation/fling to its end first so this one always wins.
        mapView.controller.stopAnimation(true)
        mapView.controller.setZoom(request.zoom)
        mapView.controller.animateTo(request.point)
        mapMoveEvents.tryEmit(Unit)
    }
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
            focusedPoint = FocusedPoint(
                geoPoint = GeoPoint(req.lat, req.lng),
                speedKmh = req.speedKmh,
                ts = req.ts,
            )
            moveCameraTo(GeoPoint(req.lat, req.lng))
            focusRequestHolder.consume()
        }
    }

    // Honour a recenter tap that arrived before the first fix did.
    LaunchedEffect(phoneLocation, awaitingLocationFix) {
        if (!awaitingLocationFix) return@LaunchedEffect
        val loc = phoneLocation
        if (loc != null) {
            awaitingLocationFix = false
            moveCameraTo(loc)
            return@LaunchedEffect
        }
        delay(LOCATION_WAIT_TIMEOUT_MS)
        awaitingLocationFix = false
    }

    // Auto-centre and auto-follow used to live in the AndroidView update block below. That block
    // is re-run on every unrelated state change (a phone-location tick, a radar viewport reload,
    // a history reload) and also runs before the first layout pass, so it fired controller calls
    // at essentially arbitrary times — including on top of a recenter the user had just asked
    // for. Both are camera decisions, so they belong in an effect keyed to the thing that
    // actually justifies moving: a new car position.
    LaunchedEffect(latestPoint) {
        val p = latestPoint ?: return@LaunchedEffect
        val initialCentering = !hasCenteredOnce
        if (!initialCentering && !followCar) return@LaunchedEffect
        if (initialCentering) hasCenteredOnce = true

        val serialBefore = cameraSerial
        mapView.awaitFirstLayout()
        // A recenter the user asked for while we were waiting for layout outranks the automatic
        // move, so drop this one rather than stomping on it.
        if (cameraSerial != serialBefore) return@LaunchedEffect

        val carPoint = GeoPoint(p.lat, p.lng)
        // Same overlapping-animator hazard as the explicit-request effect above: this fires on
        // every live position tick while follow is on, which can land inside a prior animateTo's
        // ~1s window.
        mapView.controller.stopAnimation(true)
        if (initialCentering) mapView.controller.setCenter(carPoint) else mapView.controller.animateTo(carPoint)
        mapMoveEvents.tryEmit(Unit)
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

        // Seed from whichever provider has the freshest cached fix, rather than from one chosen
        // provider: getLastKnownLocation(GPS_PROVIDER) is very often null just after a cold start
        // while network/passive still hold something usable.
        var bestFix: Location? = null
        for (provider in locationManager.getProviders(true)) {
            val cached = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (isBetterFix(cached, bestFix)) bestFix = cached
        }
        bestFix?.let { phoneLocation = GeoPoint(it.latitude, it.longitude) }

        val listener = android.location.LocationListener { location ->
            if (isBetterFix(location, bestFix)) {
                bestFix = location
                phoneLocation = GeoPoint(location.latitude, location.longitude)
            }
        }
        // Subscribe to GPS *and* network. Preferring GPS and only falling back to network when GPS
        // is switched off meant that indoors — where GPS never gets a first fix — phoneLocation
        // stayed null indefinitely and the recenter button was a silent no-op.
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return@LifecycleResumeEffect onPauseOrDispose {}

        providers.forEach { locationManager.requestLocationUpdates(it, 5000L, 10f, listener) }
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
                                infoWindow = neonInfoWindow
                                setOnClickListener { line, _, eventPos ->
                                    val nearest = historyPoints.minByOrNull { pt ->
                                        val dLat = pt.lat - eventPos.latitude
                                        val dLng = pt.lng - eventPos.longitude
                                        dLat * dLat + dLng * dLng
                                    }
                                    if (nearest != null) {
                                        line.title = selectedPointLabel
                                        line.snippet = context.getString(
                                            R.string.map_marker_snippet_format,
                                            nearest.speedKmh,
                                            formatAbsolute(nearest.ts),
                                        )
                                        line.subDescription = String.format(
                                            java.util.Locale.US,
                                            "%.5f, %.5f",
                                            nearest.lat,
                                            nearest.lng,
                                        )
                                        line.setInfoWindowLocation(GeoPoint(nearest.lat, nearest.lng))
                                        line.showInfoWindow()
                                    }
                                    true
                                }
                            },
                        )
                    }
                }

                latestPoint?.let { p ->
                    val marker = Marker(view).apply {
                        position = GeoPoint(p.lat, p.lng)
                        title = lastKnownPositionLabel
                        snippet = markerSnippet
                        subDescription = String.format(java.util.Locale.US, "%.5f, %.5f", p.lat, p.lng)
                        icon = carMarkerIcon
                        infoWindow = neonInfoWindow
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    }
                    view.overlays.add(marker)
                }

                phoneLocation?.let { loc ->
                    val marker = Marker(view).apply {
                        position = loc
                        title = yourPhoneLabel
                        subDescription = String.format(java.util.Locale.US, "%.5f, %.5f", loc.latitude, loc.longitude)
                        icon = phoneMarkerIcon
                        infoWindow = neonInfoWindow
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    }
                    view.overlays.add(marker)
                }

                focusedPoint?.let { point ->
                    val marker = Marker(view).apply {
                        position = point.geoPoint
                        title = selectedPointLabel
                        snippet = when {
                            point.speedKmh != null && point.ts != null ->
                                context.getString(R.string.map_marker_snippet_format, point.speedKmh, formatAbsolute(point.ts))
                            point.speedKmh != null ->
                                context.getString(R.string.common_speed_kmh, point.speedKmh)
                            point.ts != null ->
                                formatAbsolute(point.ts)
                            else -> null
                        }
                        subDescription = String.format(java.util.Locale.US, "%.5f, %.5f", point.geoPoint.latitude, point.geoPoint.longitude)
                        infoWindow = neonInfoWindow
                        showInfoWindow()
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
                        followCar = false
                        focusedPoint = null
                        val known = phoneLocation
                        if (known != null) {
                            awaitingLocationFix = false
                            moveCameraTo(known)
                        } else {
                            // No fix yet: remember the intent and honour it the moment one lands,
                            // rather than swallowing the tap.
                            awaitingLocationFix = true
                        }
                    }
                },
            ) {
                val recenterLabel = stringResource(R.string.map_recenter_content_description)
                if (awaitingLocationFix) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp).semantics { contentDescription = recenterLabel },
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Filled.MyLocation, contentDescription = recenterLabel)
                }
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
                        awaitingLocationFix = false
                        moveCameraTo(GeoPoint(it.lat, it.lng))
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
    var expanded by rememberSaveable { mutableStateOf(true) }

    AnimatedContent(
        targetState = expanded,
        modifier = modifier,
        contentAlignment = Alignment.TopStart,
        label = "speed_legend",
    ) { isExpanded ->
        if (isExpanded) {
            NeonCard(
                accent = MaterialTheme.colorScheme.secondary,
                glow = false,
                topBar = false,
                shape = RoundedCornerShape(14.dp),
                onClick = { expanded = false },
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
        } else {
            SmallFloatingActionButton(
                onClick = { expanded = true },
            ) {
                Icon(
                    Icons.Filled.Speed,
                    contentDescription = stringResource(R.string.map_speed_legend_content_description),
                )
            }
        }
    }
}
