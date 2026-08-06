package com.carlauncher.companion.data.bluetooth

import android.content.Context
import android.content.SharedPreferences

private const val PREFS_NAME = "bluetooth_trigger"
private const val KEY_TRIGGERS = "trigger_addresses"
private const val KEY_CONNECTED = "connected_addresses"

/**
 * Which bonded Bluetooth devices (by MAC address) gate background radar tracking, plus which of
 * them are currently connected.
 *
 * SharedPreferences rather than Room deliberately: [com.carlauncher.companion.car.CarBluetoothReceiver]
 * has to answer "is this one of the car's devices, and is it the last one to go?" synchronously
 * inside onReceive, and Room would mean either a blocking main-thread query or a goAsync() dance
 * for what is two string sets.
 *
 * The connected set is tracked here rather than queried from the platform because there is no
 * synchronous "is this classic Bluetooth device connected" API — only the async profile proxies,
 * which are useless inside a broadcast receiver. It is reconciled whenever the adapter turns off
 * (see [clearConnected]), which is the one case where per-device ACL_DISCONNECTED broadcasts
 * aren't guaranteed to arrive.
 */
class BluetoothTriggerStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun triggerAddresses(): Set<String> = prefs.readSet(KEY_TRIGGERS)

    fun setTriggerAddresses(addresses: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_TRIGGERS, addresses)
            // A device that was just deselected must not keep the service alive, so drop anything
            // no longer selected from the connected set at the same time.
            .putStringSet(KEY_CONNECTED, prefs.readSet(KEY_CONNECTED).intersect(addresses))
            .apply()
    }

    /**
     * True once the user has nominated at least one device. While false the app falls back to its
     * previous triggers (Android Auto connecting, or the car-started push) so a fresh install
     * isn't silently inert — see `BetaAppInitializer.observeCarConnection` (dev flavor).
     */
    fun isConfigured(): Boolean = triggerAddresses().isNotEmpty()

    /** @return true if this is the first trigger device to connect, i.e. tracking should start. */
    fun onConnected(address: String): Boolean {
        if (address !in triggerAddresses()) return false
        val connected = prefs.readSet(KEY_CONNECTED)
        if (address in connected) return false
        prefs.edit().putStringSet(KEY_CONNECTED, connected + address).apply()
        return connected.isEmpty()
    }

    /** @return true if no trigger device is left connected, i.e. tracking should stop. */
    fun onDisconnected(address: String): Boolean {
        val connected = prefs.readSet(KEY_CONNECTED)
        if (address !in connected) return false
        val remaining = connected - address
        prefs.edit().putStringSet(KEY_CONNECTED, remaining).apply()
        return remaining.isEmpty()
    }

    /** @return true if something was actually connected, i.e. tracking should stop. */
    fun clearConnected(): Boolean {
        if (prefs.readSet(KEY_CONNECTED).isEmpty()) return false
        prefs.edit().putStringSet(KEY_CONNECTED, emptySet()).apply()
        return true
    }
}

/**
 * getStringSet hands back the live instance backing the preference and explicitly forbids mutating
 * it, so every read is copied before it escapes.
 */
private fun SharedPreferences.readSet(key: String): Set<String> =
    getStringSet(key, null)?.toSet() ?: emptySet()
