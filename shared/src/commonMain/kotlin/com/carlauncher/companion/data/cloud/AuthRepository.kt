package com.carlauncher.companion.data.cloud

import com.carlauncher.companion.data.cloud.crypto.CryptoBox
import com.carlauncher.companion.data.cloud.crypto.KeyVault
import com.carlauncher.companion.util.Logger
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the UI needs to know about the signed-in user. */
data class CloudAccount(val userId: String, val email: String?)

sealed interface CloudSessionState {
    data object Disabled : CloudSessionState          // no Supabase credentials in this build
    data object Loading : CloudSessionState
    data object SignedOut : CloudSessionState
    data class SignedIn(val account: CloudAccount) : CloudSessionState
}

sealed interface SignUpResult {
    /** Account is live. [recoveryCode] must be shown to the user exactly once. */
    data class Active(val recoveryCode: String) : SignUpResult
    /** Supabase requires the address to be confirmed before the account can be used. */
    data object NeedsEmailConfirmation : SignUpResult
    data class Failure(val error: AuthError) : SignUpResult
}

sealed interface PasswordResetOutcome {
    /** Password changed and the encrypted backups were unlocked with the recovery code. */
    data object BackupsRecovered : PasswordResetOutcome
    /**
     * Password changed, but the old encrypted backups are permanently unreadable and have
     * been discarded. A fresh key was issued — [newRecoveryCode] must be shown once.
     */
    data class BackupsLost(val newRecoveryCode: String) : PasswordResetOutcome
    data object Failed : PasswordResetOutcome
}

sealed interface SignInResult {
    /** [recoveryCode] is non-null only when keys were provisioned during this sign-in. */
    data class Success(val recoveryCode: String?) : SignInResult
    data class Failure(val error: AuthError) : SignInResult
}

/**
 * User-facing failure reasons. Deliberately coarse: the UI must never distinguish
 * "no such account" from "wrong password", because that difference tells an attacker
 * which email addresses are registered.
 */
enum class AuthError {
    INVALID_CREDENTIALS,
    USERNAME_TAKEN,
    INVALID_USERNAME,
    INVALID_EMAIL,
    WEAK_PASSWORD,
    TERMS_NOT_ACCEPTED,
    EMAIL_NOT_CONFIRMED,
    RATE_LIMITED,
    OFFLINE,
    UNKNOWN,
}

/**
 * Account lifecycle against Supabase Auth.
 *
 * The password is never stored, hashed, or logged by this app: it is handed to GoTrue,
 * which bcrypts it server-side, and used transiently to derive the key-wrapping key in
 * [KeyVault]. Every path here takes the password as a [CharArray] so it can be wiped rather
 * than left on the heap as an immutable String.
 */
class AuthRepository(
    context: PlatformContext,
    private val provider: SupabaseClientProvider,
    private val keyVault: KeyVault,
    /** Each platform's DI root passes its own build-time terms-of-service version string
     * (Android: `BuildConfig.TERMS_VERSION`), sent with signup so the accepted version is
     * auditable server-side. */
    private val termsVersion: String,
) {

    private val client: SupabaseClient? get() = provider.client

    private val flags by lazy { createSecureSettings(context, FLAGS_FILE) }
    private val _pendingPasswordReset = MutableStateFlow(false)

    val sessionState: Flow<CloudSessionState> =
        client?.auth?.sessionStatus?.map { status ->
            when (status) {
                is SessionStatus.Authenticated ->
                    CloudSessionState.SignedIn(
                        CloudAccount(
                            userId = status.session.user?.id.orEmpty(),
                            email = status.session.user?.email,
                        ),
                    )
                is SessionStatus.NotAuthenticated -> CloudSessionState.SignedOut
                is SessionStatus.RefreshFailure -> CloudSessionState.SignedOut
                SessionStatus.Initializing -> CloudSessionState.Loading
            }
        } ?: flowOf(CloudSessionState.Disabled)

    val isEnabled: Boolean get() = provider.isConfigured

    fun currentUserId(): String? = client?.auth?.currentUserOrNull()?.id

    // ------------------------------------------------------------------ sign up

    suspend fun signUp(
        email: String,
        password: CharArray,
        username: String,
        acceptedTerms: Boolean,
    ): SignUpResult {
        val supabase = client ?: return SignUpResult.Failure(AuthError.UNKNOWN)
        if (!acceptedTerms) return SignUpResult.Failure(AuthError.TERMS_NOT_ACCEPTED)

        validateEmail(email)?.let { return SignUpResult.Failure(it) }
        validateUsername(username)?.let { return SignUpResult.Failure(it) }
        validatePassword(password)?.let { return SignUpResult.Failure(it) }

        return try {
            supabase.auth.signUpWith(Email) {
                this.email = email.trim()
                this.password = password.concatToString()
                // Read by the handle_new_user() trigger, which creates the profile row in
                // the same transaction — so a taken username aborts signup atomically
                // instead of leaving an account with no profile.
                data = buildJsonObject {
                    put("username", username.trim().lowercase())
                    put("terms_version", termsVersion)
                }
            }

            val userId = supabase.auth.currentUserOrNull()?.id
                ?: return SignUpResult.NeedsEmailConfirmation

            SignUpResult.Active(keyVault.provision(supabase, userId, password))
        } catch (e: Exception) {
            SignUpResult.Failure(classify(e))
        } finally {
            CryptoBox.wipe(password)
        }
    }

    // ------------------------------------------------------------------ sign in

    suspend fun signIn(email: String, password: CharArray): SignInResult {
        val supabase = client ?: return SignInResult.Failure(AuthError.UNKNOWN)
        return try {
            supabase.auth.signInWith(Email) {
                this.email = email.trim()
                this.password = password.concatToString()
            }
            val userId = supabase.auth.currentUserOrNull()?.id
                ?: return SignInResult.Failure(AuthError.UNKNOWN)

            // Provision on first sign-in when signup couldn't (email-confirmation flow), so
            // both signup paths converge here rather than leaving an account without keys.
            val recoveryCode = if (hasKeys(supabase, userId)) {
                keyVault.unlock(supabase, userId, password)
                null
            } else {
                keyVault.provision(supabase, userId, password)
            }
            SignInResult.Success(recoveryCode)
        } catch (e: Exception) {
            SignInResult.Failure(classify(e))
        } finally {
            CryptoBox.wipe(password)
        }
    }

    suspend fun signOut() {
        runCatching { client?.auth?.signOut() }
        // Always drop local key material, even if the network call failed.
        keyVault.lock()
    }

    // ------------------------------------------------------------------ password reset

    /**
     * Emails a password-reset link pointing at this app's deep-link scheme.
     *
     * A link rather than a typed code because Supabase locked auth email template editing
     * for new free-tier projects on the default email provider (June 2026), so `{{ .Token }}`
     * cannot be added to the template. The stock template already contains the link, so this
     * works with no dashboard customisation — only the redirect URL needs allow-listing.
     *
     * Always reports success, whether or not the address has an account: doing otherwise
     * turns this endpoint into an oracle for which emails are registered.
     */
    suspend fun requestPasswordReset(email: String) {
        // Recorded before sending: when the link is tapped the app may be a cold start, and
        // this is what tells it the arriving session is a recovery rather than a normal
        // sign-in. Survives process death.
        flags.putBoolean(KEY_PENDING_RECOVERY, true)
        runCatching { client?.auth?.resetPasswordForEmail(email.trim()) }
            .onFailure { Logger.w(TAG, "Password reset request failed", it) }
    }

    /**
     * True when a session just arrived from a reset link and the user still owes us a new
     * password. Drives navigation straight to the set-password screen.
     */
    val pendingPasswordReset: StateFlow<Boolean> get() = _pendingPasswordReset

    /**
     * Called from the activity when a deep link produced a session.
     *
     * [flowHint] is the `flow` query parameter Supabase echoes back from the redirect URL,
     * used when present; otherwise we fall back to the locally recorded flag, which covers
     * the ordinary same-device case.
     */
    fun onDeepLinkSession(flowHint: String?) {
        val isRecovery = flowHint == FLOW_RECOVERY || flags.getBoolean(KEY_PENDING_RECOVERY, false)
        if (isRecovery) _pendingPasswordReset.value = true
    }

    /** Clears the pending-reset state once the set-password screen is done with it. */
    fun clearPendingPasswordReset() {
        flags.remove(KEY_PENDING_RECOVERY)
        _pendingPasswordReset.value = false
    }

    /**
     * Sets a new password, once a reset link has produced a session.
     *
     * [recoveryCode] is the user's E2E recovery code and is optional. Without a working one
     * the account is fully restored but the encrypted GPS/stats backups are gone for good,
     * because the key that opened them was derived from the password that was just lost.
     * In that case the vault is re-provisioned with a fresh key — otherwise the stored key
     * would stay wrapped under the lost password and future backups would silently fail too.
     */
    suspend fun completePasswordReset(
        newPassword: CharArray,
        recoveryCode: String?,
    ): PasswordResetOutcome {
        val supabase = client ?: return PasswordResetOutcome.Failed
        return try {
            supabase.auth.updateUser { password = newPassword.concatToString() }
            val userId = supabase.auth.currentUserOrNull()?.id ?: return PasswordResetOutcome.Failed

            val recovered = !recoveryCode.isNullOrBlank() &&
                keyVault.recoverWithCode(supabase, userId, recoveryCode, newPassword)

            if (recovered) {
                PasswordResetOutcome.BackupsRecovered
            } else {
                PasswordResetOutcome.BackupsLost(
                    keyVault.reprovisionAfterLostKey(supabase, userId, newPassword),
                )
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Password reset completion failed", e)
            PasswordResetOutcome.Failed
        } finally {
            CryptoBox.wipe(newPassword)
        }
    }

    /** In-app password change. Re-wraps the DEK, so backups survive transparently. */
    suspend fun changePassword(newPassword: CharArray): Boolean {
        val supabase = client ?: return false
        return try {
            validatePassword(newPassword)?.let { return false }
            supabase.auth.updateUser { password = newPassword.concatToString() }
            val userId = supabase.auth.currentUserOrNull()?.id ?: return false
            keyVault.rewrapForNewPassword(supabase, userId, newPassword)
        } catch (e: Exception) {
            Logger.w(TAG, "Password change failed", e)
            false
        } finally {
            CryptoBox.wipe(newPassword)
        }
    }

    // ------------------------------------------------------------------ deletion

    /** GDPR erasure. Deletes the auth user; every table cascades from it. */
    suspend fun deleteAccount(): Boolean {
        val supabase = client ?: return false
        return runCatching {
            supabase.postgrest.rpc("delete_my_account")
            keyVault.lock()
            supabase.auth.signOut()
            true
        }.getOrElse {
            Logger.w(TAG, "Account deletion failed", it)
            false
        }
    }

    // ------------------------------------------------------------------ helpers

    @Serializable
    private data class OwnerIdOnly(@SerialName("owner_id") val ownerId: String)

    private suspend fun hasKeys(supabase: SupabaseClient, userId: String): Boolean =
        runCatching {
            supabase.postgrest.from("user_keys")
                .select(Columns.list("owner_id")) { filter { eq("owner_id", userId) } }
                .decodeList<OwnerIdOnly>()
                .isNotEmpty()
        }.getOrDefault(false)

    private fun classify(e: Exception): AuthError {
        val message = e.message.orEmpty().lowercase()
        return when {
            "username_taken" in message -> AuthError.USERNAME_TAKEN
            "invalid_username" in message -> AuthError.INVALID_USERNAME
            "terms_not_accepted" in message -> AuthError.TERMS_NOT_ACCEPTED
            "email not confirmed" in message -> AuthError.EMAIL_NOT_CONFIRMED
            "invalid login" in message || "invalid_credentials" in message -> AuthError.INVALID_CREDENTIALS
            "rate" in message && "limit" in message -> AuthError.RATE_LIMITED
            "unable to resolve host" in message || "timeout" in message ||
                "failed to connect" in message -> AuthError.OFFLINE
            else -> {
                Logger.w(TAG, "Unclassified auth failure", e)
                AuthError.UNKNOWN
            }
        }
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 10
        /** Query parameter appended to the reset redirect so the app can identify the flow. */
        const val FLOW_RECOVERY = "recovery"

        private const val TAG = "AuthRepository"
        private const val FLAGS_FILE = "cloud_flags"
        private const val KEY_PENDING_RECOVERY = "pending_recovery"

        private val USERNAME_REGEX = Regex("^[a-z0-9_]{3,20}$")
        private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")

        /** Mirrors the CHECK constraint on `profiles.username`; the DB is the real gate. */
        fun validateUsername(username: String): AuthError? =
            if (USERNAME_REGEX.matches(username.trim().lowercase())) null else AuthError.INVALID_USERNAME

        fun validateEmail(email: String): AuthError? =
            if (EMAIL_REGEX.matches(email.trim())) null else AuthError.INVALID_EMAIL

        /**
         * Length is the only rule worth enforcing. Composition requirements ("must contain a
         * symbol") measurably push people toward `Password1!` and are not recommended by
         * NIST; 10 characters with no other constraint is the better trade.
         */
        fun validatePassword(password: CharArray): AuthError? =
            if (password.size >= MIN_PASSWORD_LENGTH) null else AuthError.WEAK_PASSWORD
    }
}
