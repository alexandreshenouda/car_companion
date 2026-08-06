package com.carlauncher.companion.car

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.carlauncher.companion.MainActivity
import com.carlauncher.companion.R
import com.carlauncher.companion.data.model.Trophy
import com.carlauncher.companion.data.model.titleRes

private const val CHANNEL_ID = "trophy_unlocks"
private const val NOTIFICATION_ID = 4400

/**
 * Announces newly-earned trophies. Unlike `RadarAlertNotifier` (dev flavor) this is a plain phone
 * notification — it fires after a trip has ended, so it must never try to reach the car
 * screen or heads-up over navigation.
 */
object TrophyNotifier {

    /**
     * Tapping through opens the Trophies screen; [MainActivity] reads this extra on
     * launch and hands it to the nav host.
     */
    const val EXTRA_OPEN_TROPHIES = "open_trophies"

    fun notifyUnlocked(context: Context, unlocked: List<Trophy>) {
        if (unlocked.isEmpty() || !hasNotificationPermission(context)) return
        ensureChannel(context)

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_TROPHIES, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // One notification per batch, not per trophy: a long drive can unlock several at
        // once and a stack of five would just get swiped away.
        val title = if (unlocked.size == 1) {
            context.getString(R.string.trophy_unlocked_single, context.getString(unlocked.first().titleRes))
        } else {
            context.resources.getQuantityString(R.plurals.trophy_unlocked_count, unlocked.size, unlocked.size)
        }
        val body = unlocked.joinToString(" · ") { context.getString(it.titleRes) }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_car_marker)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManager.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.notif_channel_trophies_name))
            .setDescription(context.getString(R.string.notif_channel_trophies_desc))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    private fun hasNotificationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
