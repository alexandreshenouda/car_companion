package com.carlauncher.companion.car

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.carlauncher.companion.CompanionApp
import com.carlauncher.companion.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// v2: the original channel was IMPORTANCE_LOW, which files this into the shade's aggregated
// "silent" bundle where it's effectively invisible — and this notification's whole job is to tell
// you radar watching is armed. Channel settings are immutable once created, so raising the
// importance needs a new id; the old one is deleted in ensureChannel().
private const val CHANNEL_ID = "radar_alert_service_v2"
private const val LEGACY_CHANNEL_ID = "radar_alert_service"
private const val NOTIFICATION_ID = 4200
private const val CONNECT_GRACE_PERIOD_MS = 3 * 60 * 1000L

/**
 * Foreground service that keeps [RadarAlertEngine] tracking location for the whole time the
 * phone is connected to Android Auto — started/stopped by [com.carlauncher.companion.CompanionApp]
 * observing [androidx.car.app.connection.CarConnection], or proactively by
 * [com.carlauncher.companion.push.CompanionFcmService] as soon as the car's own launcher reports
 * "car started" (needed because a plain [CarConnection] change can't wake this app's process if
 * it was killed). In the latter case Android Auto may never actually connect this drive, so this
 * self-stops after [CONNECT_GRACE_PERIOD_MS] if it never sees [CarConnection.CONNECTION_TYPE_PROJECTION].
 *
 * Once the user has nominated a car Bluetooth device those two triggers stand down entirely and
 * [CarBluetoothReceiver] owns the lifetime instead, starting this with [EXTRA_TRIGGERED_BY_BLUETOOTH]
 * on connect and stopping it on disconnect. That start skips the grace period: being connected to
 * the car is itself the signal, whether or not Android Auto ever projects.
 * The "location" foreground service type requires this ongoing notification; it's deliberately
 * separate from and lower-priority than the actual radar alert channel in [RadarAlertNotifier].
 * The notification outlives the service itself: [onDestroy] detaches it instead of letting it
 * disappear, then flips its text to "inactive" — same id, same channel — so it's a permanent,
 * truthful readout of whether monitoring is actually running, not just silence when it isn't.
 * [ensureInactiveNotification] posts that baseline from [com.carlauncher.companion.CompanionApp]
 * so it exists even before this service has ever run.
 */
class RadarAlertService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var everConnected = false
    private var engineStarted = false
    private var carConnectionType: LiveData<Int>? = null
    private val connectionObserver = Observer<Int> { type ->
        if (type == CarConnection.CONNECTION_TYPE_PROJECTION) {
            everConnected = true
            handler.removeCallbacks(stopIfNeverConnected)
        }
    }
    private val stopIfNeverConnected = Runnable {
        if (!everConnected) stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Belt and braces: background starters check these themselves, but a sticky restart can
        // land here after the user disabled the setting or revoked the grant mid-trip.
        val container = (application as CompanionApp).container
        if (!container.beta.backgroundFeatureSettings.backgroundRadarEnabled.value) {
            Log.w(RADAR_LOG_TAG, "service: background radar checks disabled in settings, stopping")
            stopSelf()
            return
        }
        if (!canRunInBackground(this)) {
            Log.w(RADAR_LOG_TAG, "service: background location not granted, stopping")
            stopSelf()
            return
        }
        ensureChannel(this)
        startForeground(NOTIFICATION_ID, buildNotification(this, active = true))
        RadarAlertEngine.start(this)
        engineStarted = true
        Log.i(RADAR_LOG_TAG, "service: started")
        // Cache the LiveData instance: CarConnection(context).type constructs a fresh
        // CarConnectionTypeLiveData (and its own broadcast receiver) on every call, so calling it
        // again in onDestroy() to remove the observer would target a different instance and leak
        // this one's receiver registration.
        val connectionType = CarConnection(this).type
        carConnectionType = connectionType
        connectionType.observeForever(connectionObserver)
        handler.postDelayed(stopIfNeverConnected, CONNECT_GRACE_PERIOD_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val byBluetooth = intent?.getBooleanExtra(EXTRA_TRIGGERED_BY_BLUETOOTH, false) == true
        Log.i(RADAR_LOG_TAG, "service: onStartCommand byBluetooth=$byBluetooth")
        if (byBluetooth) {
            // The car's Bluetooth is connected, so keep tracking for as long as it stays that way —
            // CarBluetoothReceiver stops us on disconnect. Cancelling the grace period matters
            // because Android Auto may never project this drive (phone in a pocket, cable out).
            everConnected = true
            handler.removeCallbacks(stopIfNeverConnected)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(RADAR_LOG_TAG, "service: destroyed")
        // The car just disconnected, i.e. a trip ended — re-score the trophies.
        val container = (application as CompanionApp).container
        CoroutineScope(Dispatchers.IO).launch {
            TrophyNotifier.notifyUnlocked(applicationContext, container.trophyRepository.refresh())
        }
        handler.removeCallbacks(stopIfNeverConnected)
        carConnectionType?.removeObserver(connectionObserver)
        if (engineStarted) {
            RadarAlertEngine.stop()
            stopForeground(STOP_FOREGROUND_DETACH)
            if (container.beta.backgroundFeatureSettings.backgroundRadarEnabled.value) {
                // Detach rather than let stopForeground() take the notification down with it:
                // leaving a plain "inactive" notification behind (same id) is the whole point — a
                // permanent, truthful signal of whether monitoring is actually running, not just
                // silence. But if this stop is itself the toggle being turned off, that baseline
                // would just be replacing one background notification with another — remove it
                // instead so disabling the setting actually leaves no trace.
                postNotification(this, active = false)
            } else {
                NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
            }
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TRIGGERED_BY_BLUETOOTH = "triggered_by_bluetooth"

        /**
         * Whether this service can legally start right now. Starting a "location" foreground
         * service requires either a currently-visible UI (which a push- or Bluetooth-triggered
         * start never has) or ACCESS_BACKGROUND_LOCATION — without it the OS throws a
         * SecurityException straight out of startForeground() rather than degrading gracefully.
         * Callers that start this from the background should check first, since a service started
         * with startForegroundService() that never reaches startForeground() is killed anyway.
         */
        fun canRunInBackground(context: Context): Boolean {
            val permission = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Manifest.permission.ACCESS_FINE_LOCATION
            } else {
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            }
            return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

        /** Posts the baseline "inactive" notification so it exists from first app launch, before
         *  any car connection has ever happened. Only meaningful while background radar checks
         *  are enabled in settings — see [cancelNotification] for the opposite case. */
        fun ensureInactiveNotification(context: Context) {
            ensureChannel(context)
            postNotification(context, active = false)
        }

        /** Removes the baseline/active notification entirely, so turning background radar checks
         *  off in settings leaves no lingering "inactive" notification behind. */
        fun cancelNotification(context: Context) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }
}

private fun buildNotification(context: Context, active: Boolean) =
    NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_radar_fixed)
        .setContentTitle(
            context.getString(
                if (active) R.string.radar_service_notification_title_active else R.string.radar_service_notification_title_inactive,
            ),
        )
        .setContentText(
            context.getString(
                if (active) R.string.radar_service_notification_text_active else R.string.radar_service_notification_text_inactive,
            ),
        )
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setSilent(true)
        .setOngoing(true)
        .build()

private fun postNotification(context: Context, active: Boolean) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, buildNotification(context, active))
}

private fun ensureChannel(context: Context) {
    val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManager.IMPORTANCE_DEFAULT)
        .setName(context.getString(R.string.radar_service_channel_name))
        .setDescription(context.getString(R.string.radar_service_channel_description))
        // Visible in the main part of the shade, but still silent — DEFAULT importance would
        // otherwise ping every time the car connects.
        .setSound(null, null)
        .setVibrationEnabled(false)
        .build()
    NotificationManagerCompat.from(context).apply {
        createNotificationChannel(channel)
        deleteNotificationChannel(LEGACY_CHANNEL_ID)
    }
}
