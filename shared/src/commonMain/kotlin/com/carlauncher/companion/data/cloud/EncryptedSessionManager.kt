package com.carlauncher.companion.data.cloud

import com.carlauncher.companion.util.Logger
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json

/**
 * Stores the Supabase session (access + refresh token) in Keystore/Keychain-encrypted storage.
 *
 * This exists because supabase-kt's default `SettingsSessionManager` writes the session to
 * ordinary preferences in **plaintext**. On a rooted or backed-up device that hands over a
 * long-lived refresh token, which is equivalent to handing over the account. The refresh token
 * is the most valuable secret this app holds locally, so overriding the default is mandatory
 * rather than a hardening nicety.
 *
 * This protects tokens at rest only. It is unrelated to the end-to-end encryption of backup
 * payloads in [com.carlauncher.companion.data.cloud.crypto.CryptoBox].
 */
class EncryptedSessionManager(context: PlatformContext) : SessionManager {

    /**
     * "No session stored" — a plain local type rather than a supabase-kt exception class,
     * since those have proven unstable across even adjacent releases (the constructor for
     * the nearest equivalent, `AuthSessionMissingException`, requires an [io.ktor.client.statement.HttpResponse]
     * in 3.6.0, which doesn't exist here; other versions rename or drop the type entirely).
     * [SessionManager.loadSessionOrNull], the only caller that matters at runtime, catches any
     * [Exception], so the concrete type carries no meaning beyond "there is nothing stored".
     */
    private class NoStoredSession : Exception("No session stored")

    private val json = Json { ignoreUnknownKeys = true }

    // Lazy: opening the encrypted store touches the Keystore/Keychain, which is slow enough
    // that it should not sit on the app-startup path for users who never sign in.
    private val settings by lazy { createSecureSettings(context, FILE_NAME) }

    override suspend fun saveSession(session: UserSession) {
        settings.putString(KEY_SESSION, json.encodeToString(UserSession.serializer(), session))
    }

    override suspend fun loadSession(): UserSession {
        val raw = settings.getStringOrNull(KEY_SESSION) ?: throw NoStoredSession()
        return try {
            json.decodeFromString(UserSession.serializer(), raw)
        } catch (e: Exception) {
            // Shape changed across an app update, or the blob is damaged. Drop it and make
            // the user sign in again rather than wedging the client on every launch.
            Logger.w(TAG, "Stored session unreadable, discarding", e)
            deleteSession()
            throw NoStoredSession()
        }
    }

    override suspend fun deleteSession() {
        settings.remove(KEY_SESSION)
    }

    private companion object {
        const val TAG = "EncSessionManager"
        const val FILE_NAME = "supabase_session"
        const val KEY_SESSION = "session"
    }
}
