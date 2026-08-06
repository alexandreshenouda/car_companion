package com.carlauncher.companion

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.car.app.connection.CarConnection
import androidx.core.content.ContextCompat
import com.carlauncher.companion.car.BluetoothCarDetector
import com.carlauncher.companion.car.RadarAlertService
import com.carlauncher.companion.data.AppContainer
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging

private const val LAUNCHER_EVENTS_TOPIC = "launcher_events"

/**
 * Dev half of the process-startup seam: everything [CompanionApp.onCreate] used to do that only
 * beta features need — anonymous Firebase auth, the "car started" push channel + topic
 * subscription, and the three ways radar tracking can be triggered.
 *
 * The prod flavor declares the same object with an empty [initialize], so `CompanionApp` calls it
 * unconditionally and no beta startup code exists in the prod APK.
 */
object BetaAppInitializer {

    fun initialize(app: Application, container: AppContainer) {
        ensureAnonymousAuth()
        createNotificationChannel(app)
        subscribeToLauncherEvents()
        RadarAlertService.ensureInactiveNotification(app)
        observeCarConnection(app, container)
        BluetoothCarDetector.checkAlreadyConnected(app)
    }

    /**
     * Called from [MainActivity.onCreate]. Without the battery-optimization exemption, Doze can
     * prevent the app from waking up to handle the "launcher started" FCM push at all while
     * backgrounded or killed. Best-effort: some aftermarket ROMs don't implement this settings
     * screen. Prod receives no push, so it never prompts (and doesn't declare the permission).
     */
    fun initializeActivity(activity: Activity) {
        val powerManager = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (powerManager.isIgnoringBatteryOptimizations(activity.packageName)) return
        try {
            activity.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${activity.packageName}"),
                ),
            )
        } catch (e: ActivityNotFoundException) {
            // Not implemented on this ROM — nothing more we can do here.
        }
    }

    private fun ensureAnonymousAuth() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            auth.signInAnonymously()
        }
    }

    private fun createNotificationChannel(app: Application) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            LAUNCHER_EVENTS_TOPIC,
            app.getString(R.string.push_channel_car_started),
            NotificationManager.IMPORTANCE_HIGH,
        )
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun subscribeToLauncherEvents() {
        FirebaseMessaging.getInstance().subscribeToTopic(LAUNCHER_EVENTS_TOPIC)
    }

    /**
     * Starts [RadarAlertService] the moment Android Auto connects, so radar tracking/alerts begin
     * right away — and stops it on disconnect. `observeForever` is deliberate: this has to
     * keep watching for the app process's whole lifetime, not just while some Activity is visible.
     *
     * Stands down entirely once the user has nominated a car Bluetooth device: from then on
     * [com.carlauncher.companion.car.CarBluetoothReceiver] (plus [BluetoothCarDetector] for the
     * case where it's already connected at process start) is the sole authority on when tracking
     * runs, and Android Auto starting it independently would break "only while connected to the car".
     */
    private fun observeCarConnection(app: Application, container: AppContainer) {
        val bluetoothTriggerStore = container.beta.bluetoothTriggerStore
        CarConnection(app).type.observeForever { connectionType ->
            if (bluetoothTriggerStore.isConfigured()) return@observeForever
            val serviceIntent = Intent(app, RadarAlertService::class.java)
            if (connectionType == CarConnection.CONNECTION_TYPE_PROJECTION) {
                ContextCompat.startForegroundService(app, serviceIntent)
            } else {
                app.stopService(serviceIntent)
            }
        }
    }
}
