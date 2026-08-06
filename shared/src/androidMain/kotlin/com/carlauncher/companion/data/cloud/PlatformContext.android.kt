package com.carlauncher.companion.data.cloud

import android.content.Context
import com.russhwolf.settings.SharedPreferencesSettings
import com.russhwolf.settings.Settings

/** Wraps rather than `actual typealias`es `Context` directly: `Context` is `abstract`, and
 * Kotlin's expect/actual checker requires matching modality, which an `expect class` with no
 * declared members can't satisfy against an abstract actual. */
actual class PlatformContext(val context: Context)

/** `commit = true`: matches the original `EncryptedPrefs` call sites, which all used
 * `SharedPreferences.Editor.commit()` (synchronous) rather than `apply()` — deliberate, since
 * these values (session tokens, the DEK) must be durably written before a suspend function
 * that just wrote them returns, not eventually flushed. */
actual fun createSecureSettings(context: PlatformContext, name: String): Settings =
    SharedPreferencesSettings(EncryptedPrefs.open(context.context, name), commit = true)
