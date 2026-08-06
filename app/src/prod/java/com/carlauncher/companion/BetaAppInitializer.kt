package com.carlauncher.companion

import android.app.Activity
import android.app.Application
import com.carlauncher.companion.data.AppContainer

/**
 * Prod half of the process-startup seam — see the dev flavor's `BetaAppInitializer`. Nothing to
 * start: no Firebase auth, no push subscription, no radar/Bluetooth/Android-Auto triggers, and no
 * battery-optimization prompt (that exists only so the push can wake a killed app).
 */
@Suppress("UNUSED_PARAMETER")
object BetaAppInitializer {
    fun initialize(app: Application, container: AppContainer) = Unit
    fun initializeActivity(activity: Activity) = Unit
}
