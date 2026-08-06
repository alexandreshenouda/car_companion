package com.carlauncher.companion.data.cloud.crypto

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.io.bytestring.ByteString

/**
 * End-to-end encryption for the categories that are backed up but never shared:
 * GPS history and statistics.
 *
 * ## Key hierarchy
 *
 * A single random 256-bit **data encryption key** (DEK) encrypts every private blob. It is
 * generated once, on the device, at signup, and never leaves the device unwrapped. The
 * server only ever stores it *wrapped*:
 *
 * ```
 *   password ──PBKDF2──> KEK_pw   ──AES-GCM──> wrapped DEK (password)   ┐
 *                                                                       ├─> user_keys
 *   recovery ──PBKDF2──> KEK_rec  ──AES-GCM──> wrapped DEK (recovery)   ┘
 * ```
 *
 * Two independent wrappings of the *same* DEK. That is what lets a password change re-wrap
 * without touching a single byte of backed-up data, and what lets a forgotten password be
 * recovered from the recovery code alone.
 *
 * Lose the password **and** the recovery code and the backups are unrecoverable — by
 * anyone, including Supabase. That is the whole point of doing this client-side, but it
 * means the recovery-code screen is not optional UI polish.
 *
 * ## Choices worth knowing
 *
 * - **PBKDF2-HMAC-SHA256 at 210 000 iterations** (OWASP's 2023 floor). Argon2id would be
 *   stronger against GPU attack, but it needs a native dependency; PBKDF2 is in every
 *   platform's crypto provider this runs on, and adequate given the DEK is random rather
 *   than password-shaped.
 * - **AES-256-GCM** everywhere, fresh 12-byte nonce per operation, 128-bit tag.
 * - **AAD binds every ciphertext to its position.** A GPS chunk is authenticated against
 *   `userId|kind|chunkIndex`, so a tampering server cannot swap chunk 3 for chunk 7, move
 *   a blob between the `gps` and `stats` categories, or replay one user's data into
 *   another's account. Decryption of a moved chunk fails rather than silently succeeding.
 * - **Compress before encrypting.** GPS traces gzip extremely well and ciphertext does not
 *   compress at all, so the order matters for the 500 MB free-tier budget. (The usual
 *   caveat about compression oracles doesn't apply: there is no attacker-chosen plaintext
 *   mixed into these blobs and no per-request length feedback.)
 * - Runs on [cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin) rather
 *   than platform-native crypto (javax.crypto / CryptoKit) so this file — and its test
 *   suite — is identical on Android and iOS instead of two implementations that could
 *   silently diverge on a security-critical path.
 */
@OptIn(DelicateCryptographyApi::class)
object CryptoBox {

    const val KDF_NAME = "PBKDF2-HMAC-SHA256"
    const val KDF_ITERATIONS = 210_000

    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12

    /** Crockford base32 minus the ambiguous letters, so a handwritten code stays readable. */
    private const val RECOVERY_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val RECOVERY_GROUPS = 5
    private const val RECOVERY_GROUP_LEN = 5

    private val provider = CryptographyProvider.Default
    private val aesGcm = provider.get(AES.GCM)

    // ---------------------------------------------------------------- key material

    /** A fresh random 256-bit data key. Callers must wipe it once wrapped. */
    fun generateDek(): ByteArray = CryptographyRandom.nextBytes(KEY_BITS / 8)

    fun generateSalt(): ByteArray = CryptographyRandom.nextBytes(SALT_BYTES)

    /**
     * Stretches a password (or recovery code) into a key-encryption key.
     *
     * Takes a [CharArray] rather than a String deliberately: Strings are immutable and stay
     * in the heap until GC, whereas this can be — and is — zeroed by the caller.
     */
    fun deriveKek(secret: CharArray, salt: ByteArray, iterations: Int = KDF_ITERATIONS): ByteArray {
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        require(iterations >= KDF_ITERATIONS) { "refusing to derive with weak iteration count" }
        val derivation = provider.get(PBKDF2).secretDerivation(
            digest = SHA256,
            iterations = iterations,
            outputSize = (KEY_BITS / 8).bytes,
            salt = ByteString(salt),
        )
        // PBKDF2 here operates on bytes, not chars, unlike javax.crypto's PBEKeySpec — so
        // unlike the platform-native version this replaces, the secret passes through a
        // transient immutable String on its way to UTF-8 bytes. It's short-lived and the
        // byte copy is wiped right after use, but isn't forcibly zeroed out of the heap the
        // way a CharArray can be.
        val secretBytes = secret.concatToString().encodeToByteArray()
        try {
            return derivation.deriveSecretBlocking(secretBytes).toByteArray()
        } finally {
            secretBytes.fill(0)
        }
    }

    // ---------------------------------------------------------------- seal / open

    /** Ciphertext plus the nonce needed to reverse it. Safe to hand to the server as-is. */
    data class Sealed(val ciphertext: ByteArray, val nonce: ByteArray) {
        // Data classes compare ByteArray by identity, which is never what anyone wants here.
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Sealed && ciphertext.contentEquals(other.ciphertext) && nonce.contentEquals(other.nonce))

        override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + nonce.contentHashCode()
    }

    fun seal(plaintext: ByteArray, key: ByteArray, aad: String): Sealed {
        val nonce = CryptographyRandom.nextBytes(NONCE_BYTES)
        val cipher = decodeKey(key).cipher()
        val ciphertext = cipher.encryptWithIvBlocking(
            iv = nonce,
            plaintext = plaintext,
            associatedData = aad.encodeToByteArray(),
        )
        return Sealed(ciphertext, nonce)
    }

    /** @throws CryptoOpenException if the key is wrong or the ciphertext/AAD was tampered with. */
    fun open(sealed: Sealed, key: ByteArray, aad: String): ByteArray {
        val cipher = decodeKey(key).cipher()
        try {
            return cipher.decryptWithIvBlocking(
                iv = sealed.nonce,
                ciphertext = sealed.ciphertext,
                associatedData = aad.encodeToByteArray(),
            )
        } catch (e: Exception) {
            throw CryptoOpenException("failed to open sealed data — wrong key, wrong AAD, or tampered ciphertext", e)
        }
    }

    private fun decodeKey(key: ByteArray) =
        aesGcm.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)

    /** Compress then seal. Used for every backup payload. */
    fun sealCompressed(plaintext: ByteArray, key: ByteArray, aad: String): Sealed =
        seal(compress(plaintext), key, aad)

    fun openCompressed(sealed: Sealed, key: ByteArray, aad: String): ByteArray =
        decompress(open(sealed, key, aad))

    // ---------------------------------------------------------------- DEK wrapping

    fun wrapDek(dek: ByteArray, kek: ByteArray, userId: String, purpose: WrapPurpose): Sealed =
        seal(dek, kek, wrapAad(userId, purpose))

    fun unwrapDek(wrapped: Sealed, kek: ByteArray, userId: String, purpose: WrapPurpose): ByteArray =
        open(wrapped, kek, wrapAad(userId, purpose))

    enum class WrapPurpose { PASSWORD, RECOVERY }

    private fun wrapAad(userId: String, purpose: WrapPurpose) = "dek|$userId|${purpose.name.lowercase()}"

    /**
     * AAD for a backup chunk. Binds the ciphertext to its owner, category and index so it
     * cannot be relocated by whoever controls the database.
     */
    fun backupAad(userId: String, kind: String, chunkIndex: Int) = "$userId|$kind|$chunkIndex"

    // ---------------------------------------------------------------- recovery codes

    /**
     * A 25-character code in five dash-separated groups, ~123 bits of entropy — far beyond
     * brute force, and still short enough to write on a sticky note.
     */
    fun generateRecoveryCode(): String = (0 until RECOVERY_GROUPS).joinToString("-") {
        buildString {
            repeat(RECOVERY_GROUP_LEN) {
                append(RECOVERY_ALPHABET[CryptographyRandom.nextInt(RECOVERY_ALPHABET.length)])
            }
        }
    }

    /**
     * Canonicalises user-typed input before key derivation: strips separators, upper-cases,
     * and folds the character pairs people reliably confuse when copying by hand. Without
     * this, a correctly-transcribed code with an `O` for a `0` would silently fail to
     * decrypt and look like data loss.
     */
    fun normalizeRecoveryCode(raw: String): CharArray =
        raw.filterNot { it == '-' || it.isWhitespace() }
            .uppercase()
            .map {
                when (it) {
                    'O' -> '0'
                    'I', 'L' -> '1'
                    'U' -> 'V'
                    else -> it
                }
            }
            .toCharArray()

    fun isPlausibleRecoveryCode(raw: String): Boolean {
        val normalized = normalizeRecoveryCode(raw)
        return normalized.size == RECOVERY_GROUPS * RECOVERY_GROUP_LEN &&
            normalized.all { it in RECOVERY_ALPHABET }
    }

    // ---------------------------------------------------------------- hygiene

    /** Overwrites key material in place. Call as soon as a key goes out of use. */
    fun wipe(vararg secrets: ByteArray?) {
        secrets.forEach { it?.fill(0) }
    }

    fun wipe(vararg secrets: CharArray?) {
        secrets.forEach { it?.fill(' ') }
    }
}

/** Thrown by [CryptoBox.open] when the key is wrong or the ciphertext/AAD was tampered with —
 *  the multiplatform stand-in for `java.security.GeneralSecurityException` (Android-only). */
class CryptoOpenException(message: String, cause: Throwable) : Exception(message, cause)

/**
 * Platform-native zlib (RFC 1950) compression — deliberately the raw zlib container, not full
 * gzip (RFC 1952): Android's `java.util.zip.Deflater`/`Inflater` and iOS's Compression
 * framework (`COMPRESSION_ZLIB`) both speak this format natively, so a backup compressed on
 * one platform decompresses correctly after a [com.carlauncher.companion.data.cloud.CloudRestoreManager]
 * restore onto the other — the whole reason someone would switch phones. See the actuals in
 * androidMain/iosMain. Kept out of commonMain because neither Kotlin's stdlib nor
 * cryptography-kotlin ships a multiplatform compressor.
 */
internal expect fun compress(input: ByteArray): ByteArray
internal expect fun decompress(input: ByteArray): ByteArray
