package com.carlauncher.companion.data.cloud

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Opens a Keystore-backed [EncryptedSharedPreferences] file, recovering rather than
 * crashing when the underlying key has been invalidated.
 *
 * That recovery path is not theoretical: the Keystore entry can be lost by a restore from
 * backup, a lock-screen credential reset, or an OEM Keystore bug, after which the existing
 * file is permanently undecryptable and every future `create()` throws. Wiping and starting
 * fresh costs the user a re-login; not handling it bricks the app on launch.
 */
internal object EncryptedPrefs {

    private const val TAG = "EncryptedPrefs"

    fun open(context: Context, fileName: String): SharedPreferences {
        val appContext = context.applicationContext
        return try {
            create(appContext, fileName)
        } catch (e: Exception) {
            Log.w(TAG, "Encrypted prefs '$fileName' unreadable, recreating", e)
            appContext.deleteSharedPreferences(fileName)
            create(appContext, fileName)
        }
    }

    private fun create(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
