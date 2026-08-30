package com.carlauncher.companion

import android.app.Application
import android.content.Context
import com.carlauncher.companion.car.TrophyNotifier
import com.carlauncher.companion.data.AppContainer
import com.carlauncher.companion.data.cloud.CloudSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.io.File

class CompanionApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CoroutineScope(Dispatchers.IO).launch {
            container.deviceRepository.ensureLocalDeviceExists()
            // Catches anything earned while the app was closed — a trip recorded by the
            // car's own launcher and synced down, for instance.
            TrophyNotifier.notifyUnlocked(this@CompanionApp, container.trophyRepository.refresh().newlyUnlocked)
        }
        configureOsmdroid()
        // Safe to schedule unconditionally, signed in or not, cloud-configured build or not:
        // CloudSyncManager.syncAll() itself no-ops when there's no client or no session.
        CloudSyncWorker.schedulePeriodic(this)
        // Also fire one immediately, so a fresh launch doesn't wait up to 30 minutes for the
        // periodic tick to back up anything that changed since the app was last open.
        CloudSyncWorker.enqueueImmediate(this)
        // Firebase auth/push and the radar-tracking triggers, in the dev flavor only — this is a
        // no-op object in prod, so none of that code is compiled into the prod APK.
        BetaAppInitializer.initialize(this, container)
    }

    private fun configureOsmdroid() {
        val config = Configuration.getInstance()
        config.load(this, getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE))
        // Tile-server etiquette: identify the app, and cache to app-specific storage
        // (scoped storage, no legacy WRITE_EXTERNAL_STORAGE permission needed).
        config.userAgentValue = packageName
        val basePath = File(getExternalFilesDir(null), "osmdroid")
        config.osmdroidBasePath = basePath
        config.osmdroidTileCache = File(basePath, "tiles")
    }
}
