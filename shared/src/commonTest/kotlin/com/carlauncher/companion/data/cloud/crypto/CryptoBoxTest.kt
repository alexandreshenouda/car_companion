package com.carlauncher.companion.data.cloud.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The failure mode this guards against is unusually unforgiving: a bug here doesn't throw a
 * visible error, it silently produces backups nobody can ever decrypt again. So these tests
 * lean on the negative cases — wrong key, wrong AAD, wrong recovery code — as much as the
 * happy path.
 */
class CryptoBoxTest {

    private val userId = "11111111-2222-3333-4444-555555555555"
    private val payload = """{"points":[{"lat":48.8584,"lng":2.2945,"ts":1700000000}]}""".encodeToByteArray()

    // ------------------------------------------------------------------ seal / open

    @Test
    fun `seal then open round-trips`() {
        val key = CryptoBox.generateDek()
        val aad = CryptoBox.backupAad(userId, "gps", 0)
        val sealed = CryptoBox.seal(payload, key, aad)

        assertContentEquals(payload, CryptoBox.open(sealed, key, aad))
    }

    @Test
    fun `ciphertext does not contain the plaintext`() {
        val key = CryptoBox.generateDek()
        val sealed = CryptoBox.seal(payload, key, CryptoBox.backupAad(userId, "gps", 0))

        assertFalse(sealed.ciphertext.decodeToString(throwOnInvalidSequence = false).contains("48.8584"))
    }

    @Test
    fun `same plaintext sealed twice yields different ciphertext`() {
        val key = CryptoBox.generateDek()
        val aad = CryptoBox.backupAad(userId, "gps", 0)

        val a = CryptoBox.seal(payload, key, aad)
        val b = CryptoBox.seal(payload, key, aad)

        // Nonce reuse under GCM is catastrophic, so this is worth asserting explicitly.
        assertFalse(a.nonce.contentEquals(b.nonce))
        assertFalse(a.ciphertext.contentEquals(b.ciphertext))
    }

    @Test
    fun `open with the wrong key fails`() {
        val aad = CryptoBox.backupAad(userId, "gps", 0)
        val sealed = CryptoBox.seal(payload, CryptoBox.generateDek(), aad)

        assertFails { CryptoBox.open(sealed, CryptoBox.generateDek(), aad) }
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val key = CryptoBox.generateDek()
        val aad = CryptoBox.backupAad(userId, "gps", 0)
        val sealed = CryptoBox.seal(payload, key, aad)
        sealed.ciphertext[0] = (sealed.ciphertext[0].toInt() xor 0x01).toByte()

        assertFails { CryptoBox.open(sealed, key, aad) }
    }

    // ------------------------------------------------------------------ AAD binding

    @Test
    fun `a chunk cannot be moved to a different index`() {
        val key = CryptoBox.generateDek()
        val sealed = CryptoBox.seal(payload, key, CryptoBox.backupAad(userId, "gps", 3))

        assertFails { CryptoBox.open(sealed, key, CryptoBox.backupAad(userId, "gps", 7)) }
    }

    @Test
    fun `a chunk cannot be moved to a different category`() {
        val key = CryptoBox.generateDek()
        val sealed = CryptoBox.seal(payload, key, CryptoBox.backupAad(userId, "gps", 0))

        assertFails { CryptoBox.open(sealed, key, CryptoBox.backupAad(userId, "stats", 0)) }
    }

    @Test
    fun `a chunk cannot be replayed into another account`() {
        val key = CryptoBox.generateDek()
        val sealed = CryptoBox.seal(payload, key, CryptoBox.backupAad(userId, "gps", 0))
        val otherUser = "99999999-8888-7777-6666-555555555555"

        assertFails { CryptoBox.open(sealed, key, CryptoBox.backupAad(otherUser, "gps", 0)) }
    }

    // ------------------------------------------------------------------ compression

    @Test
    fun `compressed round-trip preserves the payload`() {
        val key = CryptoBox.generateDek()
        val aad = CryptoBox.backupAad(userId, "gps", 0)
        val big = payload.decodeToString().repeat(500).encodeToByteArray()

        val sealed = CryptoBox.sealCompressed(big, key, aad)

        assertContentEquals(big, CryptoBox.openCompressed(sealed, key, aad))
        // Gzip must actually be pulling its weight — this is the free-tier storage argument.
        assertTrue(sealed.ciphertext.size < big.size / 4)
    }

    // ------------------------------------------------------------------ DEK wrapping

    @Test
    fun `dek survives a wrap and unwrap under the password`() {
        val dek = CryptoBox.generateDek()
        val salt = CryptoBox.generateSalt()
        val kek = CryptoBox.deriveKek("correct horse battery".toCharArray(), salt)

        val wrapped = CryptoBox.wrapDek(dek, kek, userId, CryptoBox.WrapPurpose.PASSWORD)

        assertContentEquals(dek, CryptoBox.unwrapDek(wrapped, kek, userId, CryptoBox.WrapPurpose.PASSWORD))
    }

    @Test
    fun `wrong password cannot unwrap the dek`() {
        val dek = CryptoBox.generateDek()
        val salt = CryptoBox.generateSalt()
        val wrapped = CryptoBox.wrapDek(
            dek,
            CryptoBox.deriveKek("correct horse battery".toCharArray(), salt),
            userId,
            CryptoBox.WrapPurpose.PASSWORD,
        )
        val wrongKek = CryptoBox.deriveKek("incorrect horse battery".toCharArray(), salt)

        assertFails {
            CryptoBox.unwrapDek(wrapped, wrongKek, userId, CryptoBox.WrapPurpose.PASSWORD)
        }
    }

    @Test
    fun `the password wrapping cannot be opened as a recovery wrapping`() {
        val dek = CryptoBox.generateDek()
        val kek = CryptoBox.deriveKek("correct horse battery".toCharArray(), CryptoBox.generateSalt())
        val wrapped = CryptoBox.wrapDek(dek, kek, userId, CryptoBox.WrapPurpose.PASSWORD)

        assertFails {
            CryptoBox.unwrapDek(wrapped, kek, userId, CryptoBox.WrapPurpose.RECOVERY)
        }
    }

    @Test
    fun `changing the password preserves data encrypted under the old one`() {
        // The whole reason for the two-level key hierarchy: re-wrapping must not invalidate
        // a single byte of already-uploaded backup.
        val dek = CryptoBox.generateDek()
        val aad = CryptoBox.backupAad(userId, "gps", 0)
        val sealedData = CryptoBox.seal(payload, dek, aad)

        val oldSalt = CryptoBox.generateSalt()
        val oldKek = CryptoBox.deriveKek("old password 123".toCharArray(), oldSalt)
        val wrappedOld = CryptoBox.wrapDek(dek, oldKek, userId, CryptoBox.WrapPurpose.PASSWORD)

        // Unwrap with the old password, re-wrap under the new one.
        val recovered = CryptoBox.unwrapDek(wrappedOld, oldKek, userId, CryptoBox.WrapPurpose.PASSWORD)
        val newSalt = CryptoBox.generateSalt()
        val newKek = CryptoBox.deriveKek("new password 456".toCharArray(), newSalt)
        val wrappedNew = CryptoBox.wrapDek(recovered, newKek, userId, CryptoBox.WrapPurpose.PASSWORD)

        val afterChange = CryptoBox.unwrapDek(wrappedNew, newKek, userId, CryptoBox.WrapPurpose.PASSWORD)
        assertContentEquals(dek, afterChange)
        assertContentEquals(payload, CryptoBox.open(sealedData, afterChange, aad))
    }

    @Test
    fun `recovery code unwraps the same dek as the password`() {
        val dek = CryptoBox.generateDek()
        val code = CryptoBox.generateRecoveryCode()

        val pwKek = CryptoBox.deriveKek("a password here".toCharArray(), CryptoBox.generateSalt())
        val recSalt = CryptoBox.generateSalt()
        val recKek = CryptoBox.deriveKek(CryptoBox.normalizeRecoveryCode(code), recSalt)

        val wrappedPw = CryptoBox.wrapDek(dek, pwKek, userId, CryptoBox.WrapPurpose.PASSWORD)
        val wrappedRec = CryptoBox.wrapDek(dek, recKek, userId, CryptoBox.WrapPurpose.RECOVERY)

        assertContentEquals(
            CryptoBox.unwrapDek(wrappedPw, pwKek, userId, CryptoBox.WrapPurpose.PASSWORD),
            CryptoBox.unwrapDek(wrappedRec, recKek, userId, CryptoBox.WrapPurpose.RECOVERY),
        )
    }

    @Test
    fun `a wrong recovery code does not unwrap`() {
        val dek = CryptoBox.generateDek()
        val salt = CryptoBox.generateSalt()
        val realKek = CryptoBox.deriveKek(CryptoBox.normalizeRecoveryCode(CryptoBox.generateRecoveryCode()), salt)
        val wrapped = CryptoBox.wrapDek(dek, realKek, userId, CryptoBox.WrapPurpose.RECOVERY)

        val otherKek = CryptoBox.deriveKek(CryptoBox.normalizeRecoveryCode(CryptoBox.generateRecoveryCode()), salt)

        assertFails {
            CryptoBox.unwrapDek(wrapped, otherKek, userId, CryptoBox.WrapPurpose.RECOVERY)
        }
    }

    // ------------------------------------------------------------------ KDF

    @Test
    fun `derived key is deterministic for the same password and salt`() {
        val salt = CryptoBox.generateSalt()
        assertContentEquals(
            CryptoBox.deriveKek("same password".toCharArray(), salt),
            CryptoBox.deriveKek("same password".toCharArray(), salt),
        )
    }

    @Test
    fun `different salts give different keys for the same password`() {
        val a = CryptoBox.deriveKek("same password".toCharArray(), CryptoBox.generateSalt())
        val b = CryptoBox.deriveKek("same password".toCharArray(), CryptoBox.generateSalt())

        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `a weak iteration count is refused`() {
        try {
            CryptoBox.deriveKek("password".toCharArray(), CryptoBox.generateSalt(), iterations = 1000)
            fail("expected an IllegalArgumentException for a below-floor iteration count")
        } catch (expected: IllegalArgumentException) {
            // Downgrading the KDF must not be silently possible.
        }
    }

    // ------------------------------------------------------------------ recovery codes

    @Test
    fun `generated recovery codes are well formed and unique`() {
        val codes = List(50) { CryptoBox.generateRecoveryCode() }

        codes.forEach {
            assertEquals(29, it.length) // 5 groups of 5, plus 4 dashes
            assertTrue(CryptoBox.isPlausibleRecoveryCode(it))
        }
        assertEquals(50, codes.toSet().size)
    }

    @Test
    fun `recovery code normalisation folds the characters people mistype`() {
        val canonical = CryptoBox.normalizeRecoveryCode("0123-4567-89ABC-DEFGH-JKMNP")

        // Lower case, spaces instead of dashes, and O/I/L/U written for 0/1/1/V.
        assertContentEquals(
            canonical,
            CryptoBox.normalizeRecoveryCode("o123 4567 89abc defgh jkmnp"),
        )
    }

    @Test
    fun `normalisation does not collapse distinct codes`() {
        assertNotEquals(
            CryptoBox.normalizeRecoveryCode("ABCDE-FGHJK-MNPQR-STVWX-YZ234").concatToString(),
            CryptoBox.normalizeRecoveryCode("ABCDE-FGHJK-MNPQR-STVWX-YZ235").concatToString(),
        )
    }

    @Test
    fun `malformed recovery codes are rejected before use`() {
        assertFalse(CryptoBox.isPlausibleRecoveryCode(""))
        assertFalse(CryptoBox.isPlausibleRecoveryCode("too-short"))
        assertFalse(CryptoBox.isPlausibleRecoveryCode("ABCDE-FGHJK-MNPQR-STVWX-YZ234-EXTRA"))
    }

    // ------------------------------------------------------------------ hygiene

    @Test
    fun `wipe zeroes key material`() {
        val secret = CryptoBox.generateDek()
        CryptoBox.wipe(secret)

        assertTrue(secret.all { it == 0.toByte() })
    }
}
