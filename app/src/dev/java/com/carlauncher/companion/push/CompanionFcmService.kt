package com.carlauncher.companion.push

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.carlauncher.companion.CompanionApp
import com.carlauncher.companion.MainActivity
import com.carlauncher.companion.R
import com.carlauncher.companion.car.RadarAlertService
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking

private const val CHANNEL_ID = "launcher_events"
private const val NOTIFICATION_ID = 1001

/**
 * The launcher (see FIREBASE_START_EVENT.md) sends a data-only push — deviceId/title/body as
 * string fields, no "notification" block — precisely so onMessageReceived always runs here
 * (foreground, background, or killed) and gets a chance to resolve deviceId to a locally-stored
 * car nickname before anything is displayed, rather than Android auto-rendering generic text.
 */
class CompanionFcmService : FirebaseMessagingService() {

    // FirebaseMessagingService (EnhancedIntentService) already dispatches onMessageReceived on
    // its own executor thread and holds a wake lock for the duration, so a direct blocking Room
    // lookup here is safe — no goAsync() (that's a BroadcastReceiver API, not available here).
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val container = (application as CompanionApp).container
        val settings = container.beta.backgroundFeatureSettings
        // Belt and braces: unsubscribing from the topic is not instant, so an in-flight push can
        // still arrive right after the user turns this off.
        if (!settings.firebaseListenersEnabled.value) return

        val fallbackTitle = remoteMessage.data["title"] ?: return
        val body = remoteMessage.data["body"]
        val deviceId = remoteMessage.data["deviceId"]

        val name = deviceId?.let {
            runBlocking { container.deviceRepository.getDeviceName(it) }
        }
        val title = if (name != null) getString(R.string.push_car_started, name) else fallbackTitle
        showNotification(title, body)

        // This push is also the only reliable way to wake this app's process when Android Auto
        // connects while it's been killed — androidx.car.app.connection.CarConnection can report
        // the current connection state but can't itself restart a dead process. RadarAlertService
        // self-stops if Android Auto doesn't actually connect shortly after this.
        //
        // Skipped once the user has nominated a car Bluetooth device: CarBluetoothReceiver then
        // owns when tracking runs, and it doesn't need waking this way — a manifest-declared
        // receiver starts the process itself. Also skipped when background radar checks are
        // turned off in settings.
        if (settings.backgroundRadarEnabled.value && !container.beta.bluetoothTriggerStore.isConfigured()) {
            ContextCompat.startForegroundService(this, Intent(this, RadarAlertService::class.java))
        }
    }

    private fun showNotification(title: String, body: String?) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val built = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_car_marker)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, built)
    }
}
