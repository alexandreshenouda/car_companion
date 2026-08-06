package com.carlauncher.companion.data.cloud

import com.russhwolf.settings.Settings

/** Opaque per-platform handle needed to open secure storage — an Android `Context` on Android,
 * nothing at all on iOS (Keychain access needs no context). Passed through from each platform's
 * DI root; nothing in commonMain code inspects it. */
expect class PlatformContext

/** Opens (creating if absent) an encrypted key-value store named [name]: on Android, the
 * existing Keystore-backed `EncryptedSharedPreferences`; on iOS, the platform Keychain. Used
 * for the Supabase session (tokens) and the E2E data-encryption-key — never for ordinary app
 * preferences, which have no confidentiality requirement. */
expect fun createSecureSettings(context: PlatformContext, name: String): Settings
