package com.carlauncher.companion.data.settings

import android.content.Context
import android.content.Intent
import com.carlauncher.companion.car.RadarAlertService
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val PREFS_NAME = "background_feature_settings"
private const val KEY_FIREBASE_LISTENERS = "firebase_listeners_enabled"
private const val KEY_BACKGROUND_RADAR = "background_radar_enabled"
private const val LAUNCHER_EVENTS_TOPIC = "launcher_events"

/**
 * User-facing kill switches for the two things in this flavor that can wake or keep running this
 * app's process while it's backgrounded or closed: the "car started" FCM push channel, and radar
 * proximity tracking ([RadarAlertService], however it was triggered — Bluetooth, Android Auto, or
 * that same push). Both default to on; this is an opt-out for battery-conscious users, not a
 * fresh-install gate. Reached from the Settings screen.
 *
 * Plain SharedPreferences + a StateFlow mirror rather than Room: every reader here needs a
 * synchronous, no-coroutine answer — [com.carlauncher.companion.car.CarBluetoothReceiver.onReceive],
 * [com.carlauncher.companion.BetaAppInitializer], [com.carlauncher.companion.push.CompanionFcmService]
 * — and `StateFlow.value` is exactly that, while the settings screen collects the same flow.
 */
class BackgroundFeatureSettings(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _firebaseListenersEnabled = MutableStateFlow(prefs.getBoolean(KEY_FIREBASE_LISTENERS, true))
    val firebaseListenersEnabled: StateFlow<Boolean> = _firebaseListenersEnabled

    private val _backgroundRadarEnabled = MutableStateFlow(prefs.getBoolean(KEY_BACKGROUND_RADAR, true))
    val backgroundRadarEnabled: StateFlow<Boolean> = _backgroundRadarEnabled

    /** Called once at process start to reconcile the FCM topic subscription with whatever was
     * persisted last session — e.g. a subscribe made before the toggle was last turned off. */
    fun applyFirebaseSubscription() {
        syncTopic(_firebaseListenersEnabled.value)
    }

    fun setFirebaseListenersEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FIREBASE_LISTENERS, enabled).apply()
        _firebaseListenersEnabled.value = enabled
        syncTopic(enabled)
    }

    fun setBackgroundRadarEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND_RADAR, enabled).apply()
        _backgroundRadarEnabled.value = enabled
        if (enabled) {
            // Restore the permanent "inactive" baseline notification (RadarAlertService.onDestroy
            // handles re-posting it once a trip actually ends; this covers the case where nothing
            // is running right now to trigger that).
            RadarAlertService.ensureInactiveNotification(appContext)
        } else {
            // Stop immediately regardless of what's currently keeping it alive (Bluetooth, Android
            // Auto, or a push-triggered start) rather than waiting for the next disconnect event.
            // Cancel the notification too: if the service was running, its own onDestroy already
            // does this (it checks this same flag), but if nothing was running there's a stale
            // baseline notification from app start that stopService() alone won't touch.
            appContext.stopService(Intent(appContext, RadarAlertService::class.java))
            RadarAlertService.cancelNotification(appContext)
        }
    }

    private fun syncTopic(enabled: Boolean) {
        val messaging = FirebaseMessaging.getInstance()
        if (enabled) messaging.subscribeToTopic(LAUNCHER_EVENTS_TOPIC) else messaging.unsubscribeFromTopic(LAUNCHER_EVENTS_TOPIC)
    }
}
