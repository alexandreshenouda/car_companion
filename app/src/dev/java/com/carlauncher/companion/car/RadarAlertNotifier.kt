package com.carlauncher.companion.car

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.carlauncher.companion.MainActivity
import com.carlauncher.companion.R
import com.carlauncher.companion.data.repo.NearestRadar

private const val CHANNEL_ID = "radar_alerts"
private const val NOTIFICATION_ID = 4201

/**
 * Posts the radar-proximity heads-up alert to the car screen. This app has no car-app screen of
 * its own, so the notification surfaces over whatever the driver has up (Maps, Waze, launcher...).
 * Must go through [CarNotificationManager], not the plain [android.app.NotificationManager] /
 * [androidx.core.app.NotificationManagerCompat], or the notification won't reach the car.
 */
object RadarAlertNotifier {

    private var channelCreated = false

    fun notifyRadar(context: Context, nearest: NearestRadar, level: Int) {
        if (!hasNotificationPermission(context)) return
        ensureChannel(context)

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val distance = nearest.distanceMeters.toInt()
        val title = context.getString(
            R.string.radar_alert_title_format,
            context.getString(nearest.point.type.labelRes),
        )
        val text = context.getString(R.string.radar_alert_text_format, distance, barText(level))
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            // A mipmap adaptive icon is not a valid notification small icon — the car host in
            // particular needs a flat white-on-transparent vector to tint.
            .setSmallIcon(R.drawable.ic_radar_fixed)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Android Auto only lets a third-party app trigger a heads-up notification (i.e. pop
            // over whatever app currently has the screen) if the category is CALL, MESSAGE, or
            // NAVIGATION — an uncategorized notification never gets a HUN, only a status-bar/
            // center entry, no matter how high its channel importance is. CATEGORY_NAVIGATION
            // turned out to only HUN while our own screen was the one focused in the car — it's
            // apparently gated to whichever app currently owns the nav/map surface, same as the
            // "currently active nav app" suppression setOngoing(true) triggers. CATEGORY_MESSAGE
            // is on the same allowlist without that gating, so it should pop up over Maps/Waze
            // too — needs on-device confirmation.
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // No setOnlyAlertOnce: RadarAlertEngine only calls this when the danger level actually
            // changed, so every call here should re-trigger a fresh heads-up popup.
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            // Without a CarAppExtender the host drops the notification entirely — it only ever
            // reaches the phone's shade, no matter the category/importance or that it's posted
            // through CarNotificationManager. The extender is what marks a notification as
            // "also for the car screen", even when it overrides nothing.
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_radar_fixed)
                    .setContentIntent(contentIntent)
                    .setImportance(NotificationManager.IMPORTANCE_HIGH)
                    .setChannelId(CHANNEL_ID)
                    .build()
            )

        CarNotificationManager.from(context).notify(NOTIFICATION_ID, builder)
    }

    fun cancel(context: Context) {
        CarNotificationManager.from(context).cancel(NOTIFICATION_ID)
    }

    private fun hasNotificationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        if (channelCreated) return
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManager.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.radar_alert_channel_name))
            .setDescription(context.getString(R.string.radar_alert_channel_description))
            .build()
        CarNotificationManager.from(context).createNotificationChannel(channel)
        channelCreated = true
    }
}
