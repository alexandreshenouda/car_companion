package com.carlauncher.companion.data.cloud

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage

/**
 * Builds the single [SupabaseClient] for the app, or reports that cloud features are
 * switched off.
 *
 * Cloud is **optional**: with a blank [supabaseUrl]/[supabaseAnonKey] this returns a `null`
 * [client] and the app runs exactly as it did before — a fully local trip recorder. Nothing in
 * the UI may assume a client exists. Each platform's DI root resolves the config strings from
 * its own build-time source (Android: `local.properties`-derived `BuildConfig` fields; iOS:
 * its own build-config equivalent) — this class stays config-source-agnostic so it needs no
 * per-platform seam of its own.
 */
class SupabaseClientProvider(
    context: PlatformContext,
    private val supabaseUrl: String,
    private val supabaseAnonKey: String,
    /** Where password-reset emails land; must match the platform's own deep-link registration
     * and be allow-listed in the Supabase dashboard. */
    private val authRedirectScheme: String,
    private val authRedirectHost: String,
) {

    /** False when the build carries no Supabase credentials; all cloud UI stays hidden. */
    val isConfigured: Boolean = supabaseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()

    val client: SupabaseClient? by lazy {
        if (!isConfigured) return@lazy null
        createSupabaseClient(
            supabaseUrl = supabaseUrl,
            supabaseKey = supabaseAnonKey,
        ) {
            install(Auth) {
                // Tokens at rest are Keystore/Keychain-encrypted rather than plaintext prefs.
                sessionManager = EncryptedSessionManager(context)
                // PKCE, not implicit: the emailed reset link then carries a single-use code
                // exchanged over TLS for the session, instead of putting tokens directly in
                // a URL that any app registering this scheme could read.
                flowType = FlowType.PKCE
                scheme = authRedirectScheme
                host = authRedirectHost
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
            }
            install(Postgrest)
            install(Storage)
        }
    }
}
