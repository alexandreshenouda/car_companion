package com.carlauncher.companion.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.carlauncher.companion.CompanionApp
import com.carlauncher.companion.data.repo.NearestRadar
import com.carlauncher.companion.data.repo.RadarRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// RADAR_LOG_TAG is shared with LocalTrackingService — declared in src/main's CarLog.kt.

private const val RADIUS_METERS = 1000.0
private const val UPDATE_INTERVAL_MS = 5000L
private const val UPDATE_DISTANCE_M = 10f

/**
 * How old the seed fix from `getLastKnownLocation` may be before it's ignored. Nothing keeps GPS
 * warm between drives (MapScreen only listens while it's on screen), so on a Bluetooth-triggered
 * start the cached fix can be hours old and hundreds of kilometres away. Feeding that to
 * [RadarAlertEngine.updateAlert] wouldn't just misfire once — it would latch `alertedRadarKey` onto
 * a radar nowhere near the route, and the real alert for that radar would then be suppressed as a
 * duplicate when it's actually approached.
 */
private const val MAX_SEED_FIX_AGE_MS = 2 * 60 * 1000L

/**
 * App-wide singleton owning radar-proximity location tracking + heads-up alert throttling, started
 * and stopped by [RadarAlertService] for as long as Android Auto (or the nominated car Bluetooth
 * device) is connected.
 */
object RadarAlertEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _nearestRadar = MutableStateFlow<NearestRadar?>(null)
    val nearestRadar: StateFlow<NearestRadar?> = _nearestRadar

    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var alertedRadarKey: String? = null
    private var alertedLevel: Int = -1

    @Synchronized
    fun start(context: Context) {
        if (locationListener != null) {
            Log.i(RADAR_LOG_TAG, "start(): already tracking")
            return
        }

        val appContext = context.applicationContext
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(RADAR_LOG_TAG, "start(): ACCESS_FINE_LOCATION not granted, not tracking")
            return
        }
        val lm = ContextCompat.getSystemService(appContext, LocationManager::class.java)
        if (lm == null) {
            Log.w(RADAR_LOG_TAG, "start(): no LocationManager, not tracking")
            return
        }
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                Log.w(RADAR_LOG_TAG, "start(): no location provider enabled, not tracking")
                return
            }
        }
        val repository = (appContext as CompanionApp).container.beta.radarRepository

        locationManager = lm
        val listener = LocationListener { location -> onLocation(appContext, repository, location) }
        locationListener = listener
        val lastKnown = lm.getLastKnownLocation(provider)
        val seed = lastKnown?.takeIf { it.ageMillis() <= MAX_SEED_FIX_AGE_MS }
        seed?.let { onLocation(appContext, repository, it) }
        lm.requestLocationUpdates(provider, UPDATE_INTERVAL_MS, UPDATE_DISTANCE_M, listener)
        val seedState = when {
            lastKnown == null -> "none"
            seed == null -> "stale (${lastKnown.ageMillis() / 1000}s old, ignored)"
            else -> "${seed.ageMillis() / 1000}s old"
        }
        Log.i(RADAR_LOG_TAG, "start(): tracking on $provider, seed fix $seedState")
    }

    @Synchronized
    fun stop() {
        Log.i(RADAR_LOG_TAG, "stop(): tracking off")

        locationListener?.let { locationManager?.removeUpdates(it) }
        locationListener = null
        locationManager = null
        _nearestRadar.value = null
        alertedRadarKey = null
        alertedLevel = -1
    }

    /**
     * Age measured on the monotonic clock rather than from [Location.getTime]: the latter carries
     * the satellite/network timestamp, so a system clock change can make an ancient fix look new.
     */
    private fun Location.ageMillis(): Long =
        (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000

    private fun onLocation(context: Context, repository: RadarRepository, location: Location) {
        scope.launch {
            val nearest = repository.nearestWithinRadius(location.latitude, location.longitude, RADIUS_METERS)
            Log.i(
                RADAR_LOG_TAG,
                "fix ${location.latitude},${location.longitude} -> " +
                    (nearest?.let { "${it.point.type} at ${it.distanceMeters.toInt()}m" } ?: "no radar within ${RADIUS_METERS.toInt()}m"),
            )
            _nearestRadar.value = nearest
            updateAlert(context, nearest)
        }
    }

    /**
     * Posts a fresh heads-up alert whenever the closest radar changes, or the danger level (one
     * of the 10 bands the danger bar shows) advances — roughly every 90m as the car closes in,
     * rather than on every 10m/5s location tick.
     */
    private fun updateAlert(context: Context, nearest: NearestRadar?) {
        if (nearest == null) {
            if (alertedRadarKey != null) {
                RadarAlertNotifier.cancel(context)
                alertedRadarKey = null
                alertedLevel = -1
            }
            return
        }
        val key = "${nearest.point.lat},${nearest.point.lon}"
        val level = levelFor(nearest.distanceMeters)
        if (key != alertedRadarKey || level != alertedLevel) {
            alertedRadarKey = key
            alertedLevel = level
            RadarAlertNotifier.notifyRadar(context, nearest, level)
        }
    }
}
