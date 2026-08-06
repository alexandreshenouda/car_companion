package com.carlauncher.companion.car

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.carlauncher.companion.data.bluetooth.BluetoothTriggerStore

private const val TAG = RADAR_LOG_TAG

/**
 * Starts [RadarAlertService] when the car's Bluetooth connects and stops it when it disconnects,
 * for whichever devices the user picked in
 * [com.carlauncher.companion.ui.bluetooth.BluetoothTriggerScreen].
 *
 * Declared in the manifest rather than registered at runtime because the point is to react while
 * this app is backgrounded or its process is dead. That works despite the Android 8 implicit-
 * broadcast restrictions: ACL_CONNECTED/ACL_DISCONNECTED are on the documented exemption list.
 */
class CarBluetoothReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val store = BluetoothTriggerStore(context)
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val address = intent.deviceAddress() ?: return
                val shouldStart = store.onConnected(address)
                Log.i(TAG, "bt: $address connected, start=$shouldStart")
                if (shouldStart) startTracking(context)
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val address = intent.deviceAddress() ?: return
                val shouldStop = store.onDisconnected(address)
                Log.i(TAG, "bt: $address disconnected, stop=$shouldStop")
                if (shouldStop) stopTracking(context)
            }

            // Turning the adapter off doesn't reliably emit ACL_DISCONNECTED per device, so without
            // this the tracked connected set would stay stuck non-empty and tracking would never
            // stop until the next connect/disconnect pair.
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_OFF && store.clearConnected()) stopTracking(context)
            }
        }
    }

    private fun Intent.deviceAddress(): String? =
        IntentCompat.getParcelableExtra(this, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)?.address
}

/** Shared with [BluetoothCarDetector], which needs the exact same start logic for the case where
 *  a configured device is found already connected instead of connecting via a fresh broadcast. */
internal fun startTracking(context: Context) {
    // Checked here rather than letting the service bail in onCreate: a foreground service
    // started with startForegroundService() that never reaches startForeground() is killed
    // with an exception, and without background location RadarAlertService can't legally call
    // it. MapScreen is where that grant gets requested.
    if (!RadarAlertService.canRunInBackground(context)) {
        Log.i(TAG, "Car connected but background location isn't granted — not starting radar tracking")
        return
    }
    val intent = Intent(context, RadarAlertService::class.java)
        .putExtra(RadarAlertService.EXTRA_TRIGGERED_BY_BLUETOOTH, true)
    try {
        ContextCompat.startForegroundService(context, intent)
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (API 31+). An ACL broadcast is not on the
        // background-FGS-start exemption list, so this only works while the app is exempt from
        // battery optimisation — which MainActivity asks for on first launch. Nothing useful
        // to do from here if the user declined.
        Log.w(TAG, "Not allowed to start radar tracking from the background", e)
    }
}

internal fun stopTracking(context: Context) {
    context.stopService(Intent(context, RadarAlertService::class.java))
}
