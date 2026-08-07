package com.carlauncher.companion.data.cloud.crypto

import com.carlauncher.companion.data.cloud.PlatformContext
import com.carlauncher.companion.data.cloud.createSecureSettings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile

/**
 * Owns the lifecycle of the end-to-end data encryption key (DEK).
 *
 * See [CryptoBox] for the key hierarchy. This class is the only thing that ever holds an
 * unwrapped DEK, and the only thing that talks to the `user_keys` table.
 *
 * ## Why the DEK is cached on disk
 *
 * The unwrapped DEK is written to Keystore-encrypted preferences, not merely held in
 * memory. Background uploads run in WorkManager long after the process that had the
 * password has died; without a persisted DEK, encrypted backup would only ever work while
 * the user happened to be looking at the app, which makes the feature useless in practice.
 *
 * The trade this makes is explicit and worth understanding: the threat model here is
 * "the server, or anyone who breaches it, must not be able to read my movements". It is
 * *not* "an attacker with root on my unlocked phone". The latter already has the plaintext
 * Room database sitting right next to it, so protecting the DEK harder than the data it
 * protects would buy nothing.
 */
class KeyVault(context: PlatformContext) {

    private val settings by lazy { createSecureSettings(context, FILE_NAME) }

    @Volatile
    private var cachedDek: ByteArray? = null

    /** True once a DEK is available, i.e. encrypted backup can run. */
    val isUnlocked: Boolean get() = dekOrNull() != null

    fun dekOrNull(): ByteArray? {
        cachedDek?.let { return it }
        val stored = settings.getStringOrNull(KEY_DEK) ?: return null
        return stored.fromBase64().also { cachedDek = it }
    }

    /**
     * First-time setup, during signup. Generates a DEK and both wrappings, then persists
     * them server-side.
     *
     * @return the recovery code, which the caller MUST show to the user exactly once.
     */
    suspend fun provision(client: SupabaseClient, userId: String, password: CharArray): String =
        withContext(Dispatchers.Default) {
            val dek = CryptoBox.generateDek()
            val recoveryCode = CryptoBox.generateRecoveryCode()
            val recoveryChars = CryptoBox.normalizeRecoveryCode(recoveryCode)

            val saltPw = CryptoBox.generateSalt()
            val saltRec = CryptoBox.generateSalt()
            var kekPw: ByteArray? = null
            var kekRec: ByteArray? = null
            try {
                kekPw = CryptoBox.deriveKek(password, saltPw)
                kekRec = CryptoBox.deriveKek(recoveryChars, saltRec)

                val wrappedPw = CryptoBox.wrapDek(dek, kekPw, userId, CryptoBox.WrapPurpose.PASSWORD)
                val wrappedRec = CryptoBox.wrapDek(dek, kekRec, userId, CryptoBox.WrapPurpose.RECOVERY)

                client.postgrest.from(TABLE).upsert(
                    UserKeysRow(
                        ownerId = userId,
                        wrappedDekPassword = wrappedPw.ciphertext.toBase64(),
                        saltPassword = saltPw.toBase64(),
                        noncePassword = wrappedPw.nonce.toBase64(),
                        wrappedDekRecovery = wrappedRec.ciphertext.toBase64(),
                        saltRecovery = saltRec.toBase64(),
                        nonceRecovery = wrappedRec.nonce.toBase64(),
                        kdf = CryptoBox.KDF_NAME,
                        kdfIterations = CryptoBox.KDF_ITERATIONS,
                    ),
                )
                cache(dek)
                recoveryCode
            } finally {
                CryptoBox.wipe(kekPw, kekRec)
                CryptoBox.wipe(recoveryChars)
            }
        }

    /**
     * Normal unlock at sign-in.
     *
     * @return false when the stored wrapping cannot be opened with this password. That is
     *   effectively unreachable in normal use (the password just authenticated), so it means
     *   the key row is missing or corrupt — the caller should surface "backups unavailable"
     *   rather than blocking sign-in.
     */
    suspend fun unlock(client: SupabaseClient, userId: String, password: CharArray): Boolean =
        withContext(Dispatchers.Default) {
            val row = fetchKeys(client, userId) ?: return@withContext false
            var kek: ByteArray? = null
            try {
                kek = CryptoBox.deriveKek(password, row.saltPassword.fromBase64(), row.kdfIterations)
                val dek = CryptoBox.unwrapDek(
                    CryptoBox.Sealed(row.wrappedDekPassword.fromBase64(), row.noncePassword.fromBase64()),
                    kek,
                    userId,
                    CryptoBox.WrapPurpose.PASSWORD,
                )
                cache(dek)
                true
            } catch (e: Exception) {
                false
            } finally {
                CryptoBox.wipe(kek)
            }
        }

    /**
     * Re-wraps the existing DEK under a new password. Backed-up data is untouched — this is
     * the whole reason for the two-level key hierarchy.
     *
     * Requires the vault to already be unlocked (i.e. a normal in-app password change).
     */
    suspend fun rewrapForNewPassword(
        client: SupabaseClient,
        userId: String,
        newPassword: CharArray,
    ): Boolean = withContext(Dispatchers.Default) {
        val dek = dekOrNull() ?: return@withContext false
        val salt = CryptoBox.generateSalt()
        var kek: ByteArray? = null
        try {
            kek = CryptoBox.deriveKek(newPassword, salt)
            val wrapped = CryptoBox.wrapDek(dek, kek, userId, CryptoBox.WrapPurpose.PASSWORD)
            client.postgrest.from(TABLE).update(
                mapOf(
                    "wrapped_dek_password" to wrapped.ciphertext.toBase64(),
                    "salt_password" to salt.toBase64(),
                    "nonce_password" to wrapped.nonce.toBase64(),
                ),
            ) { filter { eq("owner_id", userId) } }
            true
        } finally {
            CryptoBox.wipe(kek)
        }
    }

    /**
     * Forgotten-password path: unwrap with the recovery code, then re-wrap under the new
     * password so the user is back to normal.
     *
     * @return false if the code is wrong — in which case the encrypted backups stay
     *   unreadable, and the caller must say so plainly rather than implying a retry will help.
     */
    suspend fun recoverWithCode(
        client: SupabaseClient,
        userId: String,
        recoveryCode: String,
        newPassword: CharArray,
    ): Boolean = withContext(Dispatchers.Default) {
        val row = fetchKeys(client, userId) ?: return@withContext false
        val chars = CryptoBox.normalizeRecoveryCode(recoveryCode)
        var kek: ByteArray? = null
        try {
            kek = CryptoBox.deriveKek(chars, row.saltRecovery.fromBase64(), row.kdfIterations)
            val dek = CryptoBox.unwrapDek(
                CryptoBox.Sealed(row.wrappedDekRecovery.fromBase64(), row.nonceRecovery.fromBase64()),
                kek,
                userId,
                CryptoBox.WrapPurpose.RECOVERY,
            )
            cache(dek)
            rewrapForNewPassword(client, userId, newPassword)
        } catch (e: Exception) {
            false
        } finally {
            CryptoBox.wipe(kek)
            CryptoBox.wipe(chars)
        }
    }

    /**
     * Starts over with a brand-new DEK after the old one became unreachable — i.e. the
     * password was reset without a working recovery code.
     *
     * Also deletes the existing encrypted backups. They are permanently undecryptable at
     * this point, so keeping them would only burn free-tier storage and mislead the user
     * into thinking the data is still there. Without this, `user_keys` would stay wrapped
     * under the lost password and *future* backups would silently never work either.
     *
     * @return the new recovery code, which must be shown to the user exactly once.
     */
    suspend fun reprovisionAfterLostKey(
        client: SupabaseClient,
        userId: String,
        password: CharArray,
    ): String {
        runCatching {
            client.postgrest.from(BACKUPS_TABLE).delete { filter { eq("owner_id", userId) } }
        }
        lock()
        return provision(client, userId, password)
    }

    /** Clears the DEK from memory and disk. Called on sign-out. */
    fun lock() {
        CryptoBox.wipe(cachedDek)
        cachedDek = null
        settings.remove(KEY_DEK)
    }

    private fun cache(dek: ByteArray) {
        cachedDek = dek
        settings.putString(KEY_DEK, dek.toBase64())
    }

    private suspend fun fetchKeys(client: SupabaseClient, userId: String): UserKeysRow? =
        runCatching {
            client.postgrest.from(TABLE)
                .select { filter { eq("owner_id", userId) } }
                .decodeSingleOrNull<UserKeysRow>()
        }.getOrNull()

    @Serializable
    private data class UserKeysRow(
        @SerialName("owner_id") val ownerId: String,
        @SerialName("wrapped_dek_password") val wrappedDekPassword: String,
        @SerialName("salt_password") val saltPassword: String,
        @SerialName("nonce_password") val noncePassword: String,
        @SerialName("wrapped_dek_recovery") val wrappedDekRecovery: String,
        @SerialName("salt_recovery") val saltRecovery: String,
        @SerialName("nonce_recovery") val nonceRecovery: String,
        @SerialName("kdf") val kdf: String = CryptoBox.KDF_NAME,
        @SerialName("kdf_iterations") val kdfIterations: Int = CryptoBox.KDF_ITERATIONS,
    )

    private companion object {
        const val FILE_NAME = "cloud_keys"
        const val KEY_DEK = "dek"
        const val TABLE = "user_keys"
        const val BACKUPS_TABLE = "private_backups"
    }
}
