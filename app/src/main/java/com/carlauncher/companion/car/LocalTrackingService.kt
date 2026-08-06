package com.carlauncher.companion.car

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.carlauncher.companion.CompanionApp
import com.carlauncher.companion.R
import com.carlauncher.companion.data.db.LocationPointEntity
import com.carlauncher.companion.data.repo.DeviceRepository
import com.carlauncher.companion.data.repo.TrackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val CHANNEL_ID = "local_tracking_service"
private const val NOTIFICATION_ID = 4300
private const val UPDATE_INTERVAL_MS = 5000L
private const val UPDATE_DISTANCE_M = 10f

/**
 * Foreground service that records the phone's own GPS fixes into `location_points` under
 * [DeviceRepository.LOCAL_DEVICE_ID] ("This phone") — an alternative to relying on the car's
 * GPS, started/stopped by a record button on [com.carlauncher.companion.ui.map.MapScreen] that
 * only shows while "This phone" is the selected device. Always started from an in-app button
 * tap (app in the foreground at start time), so unlike `RadarAlertService` (dev flavor) this never needs
 * ACCESS_BACKGROUND_LOCATION.
 */
class LocalTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(RADAR_LOG_TAG, "LocalTrackingService: fine location not granted, stopping")
            stopSelf()
            return
        }
        val lm = ContextCompat.getSystemService(this, LocationManager::class.java)
        val provider = when {
            lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true -> LocationManager.GPS_PROVIDER
            lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (lm == null || provider == null) {
            Log.w(RADAR_LOG_TAG, "LocalTrackingService: no location provider enabled, stopping")
            stopSelf()
            return
        }

        ensureChannel(this)
        startForeground(NOTIFICATION_ID, buildNotification(this))

        val deviceRepository = (application as CompanionApp).container.deviceRepository
        val trackRepository = (application as CompanionApp).container.trackRepository
        scope.launch { deviceRepository.setLocalRecordingActive(true) }

        locationManager = lm
        val listener = LocationListener { location -> onLocation(trackRepository, location) }
        locationListener = listener
        lm.requestLocationUpdates(provider, UPDATE_INTERVAL_MS, UPDATE_DISTANCE_M, listener)
        Log.i(RADAR_LOG_TAG, "LocalTrackingService: started on $provider")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        Log.i(RADAR_LOG_TAG, "LocalTrackingService: destroyed")
        locationListener?.let { locationManager?.removeUpdates(it) }
        locationListener = null
        locationManager = null
        val container = (application as CompanionApp).container
        scope.launch {
            container.deviceRepository.setLocalRecordingActive(false)
            // Recording just stopped, so this is the moment new trophies can land.
            TrophyNotifier.notifyUnlocked(applicationContext, container.trophyRepository.refresh())
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun onLocation(trackRepository: TrackRepository, location: Location) {
        scope.launch {
            trackRepository.recordLocalPoint(
                LocationPointEntity(
                    deviceId = DeviceRepository.LOCAL_DEVICE_ID,
                    lat = location.latitude,
                    lng = location.longitude,
                    ts = location.time,
                    speedKmh = if (location.hasSpeed()) (location.speed * 3.6f).roundToInt() else 0,
                    pushedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }
}

private fun buildNotification(context: Context) =
    NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_phone_marker)
        .setContentTitle(context.getString(R.string.local_tracking_notification_title))
        .setContentText(context.getString(R.string.local_tracking_notification_text))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setSilent(true)
        .setOngoing(true)
        .build()

private fun ensureChannel(context: Context) {
    val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManager.IMPORTANCE_DEFAULT)
        .setName(context.getString(R.string.local_tracking_channel_name))
        .setDescription(context.getString(R.string.local_tracking_channel_description))
        .setSound(null, null)
        .setVibrationEnabled(false)
        .build()
    NotificationManagerCompat.from(context).createNotificationChannel(channel)
}
