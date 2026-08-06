package com.carlauncher.companion.car

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.carlauncher.companion.data.bluetooth.BluetoothTriggerStore

private const val TAG = RADAR_LOG_TAG

/**
 * Catches a configured car device that's already connected by the time the app process starts.
 * [CarBluetoothReceiver] only reacts to a fresh ACL_CONNECTED broadcast, which never arrives if
 * the phone's Bluetooth radio was already linked to the car (e.g. it never dropped since an
 * earlier trip) — run once at app startup, checking the two profiles cars actually use (A2DP,
 * hands-free) for the trigger addresses.
 */
object BluetoothCarDetector {

    fun checkAlreadyConnected(context: Context) {
        val store = BluetoothTriggerStore(context)
        if (!store.isConfigured()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return

        checkProfile(context, adapter, store, BluetoothProfile.A2DP)
        checkProfile(context, adapter, store, BluetoothProfile.HEADSET)
    }

    private fun checkProfile(
        context: Context,
        adapter: BluetoothAdapter,
        store: BluetoothTriggerStore,
        profile: Int,
    ) {
        adapter.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(connectedProfile: Int, proxy: BluetoothProfile) {
                    proxy.connectedDevices.forEach { device ->
                        val shouldStart = store.onConnected(device.address)
                        Log.i(TAG, "bt: ${device.address} already connected (profile=$profile), start=$shouldStart")
                        if (shouldStart) startTracking(context)
                    }
                    adapter.closeProfileProxy(profile, proxy)
                }

                override fun onServiceDisconnected(disconnectedProfile: Int) = Unit
            },
            profile,
        )
    }
}
