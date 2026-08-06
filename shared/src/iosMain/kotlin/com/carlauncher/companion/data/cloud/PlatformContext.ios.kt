package com.carlauncher.companion.data.cloud

import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import com.russhwolf.settings.Settings

/** No context object is needed to reach the iOS Keychain. */
actual class PlatformContext

/** `KeychainSettings` is the library's Keychain-backed [Settings] — flagged
 * `@ExperimentalSettingsImplementation` upstream but widely used in production; see the
 * dependency comment in shared/build.gradle.kts. [name] becomes the Keychain "service"
 * attribute, so distinct store names (session vs. DEK) stay in separate Keychain items exactly
 * as they're separate `EncryptedSharedPreferences` files on Android. */
@OptIn(ExperimentalSettingsImplementation::class)
actual fun createSecureSettings(context: PlatformContext, name: String): Settings =
    KeychainSettings(service = name)
