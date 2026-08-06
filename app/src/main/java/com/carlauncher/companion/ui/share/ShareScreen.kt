package com.carlauncher.companion.ui.share

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlauncher.companion.R
import com.carlauncher.companion.data.db.CarEntity
import com.carlauncher.companion.data.db.LocationPointEntity
import com.carlauncher.companion.data.model.HistoryRange
import com.carlauncher.companion.data.model.ShareTemplate
import com.carlauncher.companion.data.model.SpeedZone
import com.carlauncher.companion.data.model.TrackStats
import com.carlauncher.companion.ui.common.CarPhoto
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.data.repo.CarRepository
import com.carlauncher.companion.data.repo.DeviceRepository
import com.carlauncher.companion.data.repo.EventRepository
import com.carlauncher.companion.data.repo.TrackRepository
import com.carlauncher.companion.data.repo.computeStats
import com.carlauncher.companion.ui.map.CartoDarkMatterTileSource
import com.carlauncher.companion.ui.map.awaitFirstLayout
import com.carlauncher.companion.util.buildSpeedSegments
import com.carlauncher.companion.util.formatDuration
import com.carlauncher.companion.util.formatTimeRange
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

private const val CARD_ASPECT = 4f / 5f
private const val TILE_SETTLE_DELAY_MS = 800L

/**
 * Share-card preview: renders the same points/duration the user had selected on the screen they
 * came from ([range] is fixed, not re-selectable here), lets them pick a visual [ShareTemplate],
 * then captures the card to a PNG and hands it to the system share sheet.
 */
@Composable
fun ShareScreen(
    deviceId: String,
    range: HistoryRange,
    trackRepository: TrackRepository,
    deviceRepository: DeviceRepository,
) {
    var points by remember { mutableStateOf<List<LocationPointEntity>>(emptyList()) }
    var loadingData by remember { mutableStateOf(true) }
    var carLabel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(deviceId, range) {
        loadingData = true
        points = trackRepository.pointsInRange(deviceId, range)
        loadingData = false
    }

    LaunchedEffect(deviceId) {
        val device = deviceRepository.getDevice(deviceId)
        carLabel = listOfNotNull(
            device?.brand?.takeIf { it.isNotBlank() },
            device?.model?.takeIf { it.isNotBlank() },
        ).joinToString(" ").ifBlank { null }
    }

    ShareScreenContent(title = stringResource(R.string.share_title_trip), points = points, carLabel = carLabel, loadingData = loadingData)
}

/**
 * Share-card preview for a single [eventId] (from `ui/events/EventDetailScreen.kt`): seeds the
 * same [ShareScreenContent] with an event's own points/title instead of a device+range query —
 * an event's track may come from a linked car's device history *or* an imported GPX file (no
 * device at all), so it can't be described by [ShareScreen]'s `deviceId`/`range` pair.
 */
@Composable
fun EventShareScreen(
    eventId: String,
    eventRepository: EventRepository,
    carRepository: CarRepository,
) {
    val event by eventRepository.observeEvent(eventId).collectAsStateWithLifecycle(initialValue = null)
    val eventPoints by eventRepository.observePoints(eventId).collectAsStateWithLifecycle(initialValue = emptyList())
    val cars by carRepository.observeCars().collectAsStateWithLifecycle(initialValue = emptyList())

    val points = remember(eventPoints) {
        eventPoints.map { LocationPointEntity(deviceId = "", lat = it.lat, lng = it.lng, ts = it.ts, speedKmh = it.speedKmh, pushedAtMillis = it.ts) }
    }
    val car = remember(event, cars) { cars.firstOrNull { it.id == event?.carId } }

    ShareScreenContent(
        title = event?.title ?: stringResource(R.string.share_title_event),
        points = points,
        carLabel = null,
        car = car,
        loadingData = event == null,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareScreenContent(
    title: String,
    points: List<LocationPointEntity>,
    carLabel: String?,
    car: CarEntity? = null,
    loadingData: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var template by remember { mutableStateOf(ShareTemplate.SPEED_ZONES) }
    var mapReady by remember { mutableStateOf(false) }
    var cardBounds by remember { mutableStateOf<Rect?>(null) }
    var sharing by remember { mutableStateOf(false) }

    val stats = remember(points) { computeStats(points) }

    val mapView = remember { MapView(context) }

    LaunchedEffect(mapView) {
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
        mapView.setMultiTouchControls(false)
        mapView.setOnTouchListener { _, _ -> true }
        mapView.minZoomLevel = 3.0
    }

    LaunchedEffect(points) {
        mapReady = false
        if (points.isEmpty()) return@LaunchedEffect
        mapView.awaitFirstLayout()
        if (points.size == 1) {
            mapView.controller.setZoom(15.0)
            mapView.controller.setCenter(GeoPoint(points[0].lat, points[0].lng))
        } else {
            val bbox = BoundingBox.fromGeoPoints(points.map { GeoPoint(it.lat, it.lng) })
            mapView.zoomToBoundingBox(bbox, false, 96)
        }
        delay(TILE_SETTLE_DELAY_MS)
        mapReady = true
    }

    val accentColor = MaterialTheme.colorScheme.primary.toArgb()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ShareTemplate.entries.forEach { entry ->
                NeonPill(
                    text = stringResource(entry.labelRes),
                    accent = MaterialTheme.colorScheme.primary,
                    selected = entry == template,
                    onClick = { template = entry },
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(CARD_ASPECT)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .onGloballyPositioned { coordinates ->
                    val pos = coordinates.positionInWindow()
                    val size = coordinates.size
                    cardBounds = Rect(
                        pos.x.toInt(),
                        pos.y.toInt(),
                        pos.x.toInt() + size.width,
                        pos.y.toInt() + size.height,
                    )
                },
        ) {
            // Always mounted, regardless of template: osmdroid's MapView permanently breaks
            // once detached from the window (MapViewRepository.onDetach() nulls its MapView
            // reference for good — see MapViewRepository.java — so conditionally composing this
            // AndroidView in/out per template would detach-then-reattach the same remembered
            // instance and crash on the next Polyline() the moment the map template comes back).
            // Templates without a map just cover it with an opaque background instead.
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    view.overlays.clear()
                    if (template.showMap && points.size > 1) {
                        if (template.showZoneColors) {
                            for (segment in buildSpeedSegments(points)) {
                                view.overlays.add(
                                    Polyline(view).apply {
                                        setPoints(segment.points)
                                        outlinePaint.color = segment.color
                                        outlinePaint.strokeWidth = 10f
                                    },
                                )
                            }
                        } else {
                            view.overlays.add(
                                Polyline(view).apply {
                                    setPoints(points.map { GeoPoint(it.lat, it.lng) })
                                    outlinePaint.color = accentColor
                                    outlinePaint.strokeWidth = 10f
                                },
                            )
                        }
                    }
                    view.invalidate()
                },
            )
            if (template.showMap) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f),
                            ),
                        ),
                )
                if (template.showLegend) {
                    ShareLegend(modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
                }
            } else if (car?.photoPath != null) {
                Box(Modifier.fillMaxSize()) {
                    CarPhoto(photoPath = car.photoPath, modifier = Modifier.fillMaxSize())
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                                    1f to MaterialTheme.colorScheme.scrim.copy(alpha = 0.85f),
                                ),
                            ),
                    )
                }
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
            }

            Watermark(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))

            if (loadingData) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (points.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.share_no_data),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                ShareStats(
                    stats = stats,
                    template = template,
                    carLabel = carLabel,
                    car = car,
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                    onLight = !template.showMap && car?.photoPath == null,
                )
                if (template.showMap && !mapReady) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            enabled = points.isNotEmpty() && !sharing && (!template.showMap || mapReady),
            onClick = {
                val bounds = cardBounds
                val activity = context.findActivity()
                if (bounds != null && activity != null) {
                    scope.launch {
                        sharing = true
                        try {
                            val bitmap = ShareImageExporter.capture(activity.window, bounds)
                            val uri = ShareImageExporter.saveToCache(context, bitmap)
                            context.startActivity(ShareImageExporter.buildShareIntent(uri))
                        } finally {
                            sharing = false
                        }
                    }
                }
            },
        ) {
            if (sharing) {
                CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Share, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.common_share))
        }
    }
}

@Composable
private fun Watermark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(18.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

@Composable
private fun ShareLegend(modifier: Modifier = Modifier) {
    Column(modifier) {
        SpeedZone.entries.forEach { zone ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(2.dp)) {
                Box(Modifier.size(10.dp).background(Color(zone.color), CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.map_legend_speed_format, zone.label),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ShareStats(
    stats: TrackStats,
    template: ShareTemplate,
    carLabel: String?,
    car: CarEntity? = null,
    modifier: Modifier = Modifier,
    onLight: Boolean,
) {
    val textColor = if (onLight) MaterialTheme.colorScheme.onSurfaceVariant else Color.White
    Column(modifier) {
        if (car != null) {
            Text(
                car.name,
                style = MaterialTheme.typography.titleMedium,
                color = textColor.copy(alpha = 0.9f),
            )
            listOfNotNull(car.brand, car.model, car.year?.toString())
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
                ?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelLarge,
                        color = textColor.copy(alpha = 0.75f),
                    )
                }
        } else {
            carLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor.copy(alpha = 0.8f),
                )
            }
        }
        Text(
            formatDuration(stats.movingTimeSeconds),
            style = MaterialTheme.typography.displaySmall,
            color = textColor,
        )
        Text(
            stringResource(R.string.share_avg_speed_format, stats.avgSpeedKmh),
            style = MaterialTheme.typography.titleMedium,
            color = textColor,
        )
        val firstTs = stats.firstTs
        val lastTs = stats.lastTs
        if (firstTs != null && lastTs != null) {
            Text(
                formatTimeRange(firstTs, lastTs),
                style = MaterialTheme.typography.bodyMedium,
                color = textColor.copy(alpha = 0.8f),
            )
        }
        if (template.showExtendedStats) {
            Text(
                stringResource(R.string.share_max_median_format, stats.maxSpeedKmh, stats.medianSpeedKmh),
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
            )
            Text(
                stringResource(R.string.common_distance_km, stats.distanceKm),
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
