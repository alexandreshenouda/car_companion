package com.carlauncher.companion.data

import android.content.Context
import com.carlauncher.companion.data.bluetooth.BluetoothTriggerStore
import com.carlauncher.companion.data.repo.RadarRepository
import com.carlauncher.companion.data.repo.SectionRepository
import com.carlauncher.companion.data.settings.BackgroundFeatureSettings

/**
 * Dev half of the beta-feature seam: the singletons only beta code touches (radars, the
 * Bluetooth trigger). Held by [AppContainer] so they live as long as the process — the radar
 * repository in particular caches parsed country files and must not be rebuilt per screen.
 *
 * The prod flavor declares a same-named empty class, so `src/main` compiles either way with no
 * `BuildConfig` check and none of this code reaches the prod APK.
 */
/**
 * Dev half of the beta-singleton seam: the three stores only beta features use. Held behind
 * [AppContainer.beta] so shared code can carry one reference around without ever naming a
 * radar/Bluetooth type — the prod flavor declares an empty `BetaContainer` of the same name.
 */
class BetaContainer(context: Context) {
    val radarRepository = RadarRepository(context.assets)
    val sectionRepository = SectionRepository(context.assets)
    val bluetoothTriggerStore = BluetoothTriggerStore(context)
    val backgroundFeatureSettings = BackgroundFeatureSettings(context)
}
