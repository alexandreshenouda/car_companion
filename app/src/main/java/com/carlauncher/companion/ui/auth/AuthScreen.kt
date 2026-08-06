package com.carlauncher.companion.ui.auth

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.carlauncher.companion.R
import com.carlauncher.companion.data.cloud.AuthError
import com.carlauncher.companion.data.cloud.AuthRepository
import com.carlauncher.companion.data.cloud.SignInResult
import com.carlauncher.companion.data.cloud.SignUpResult
import com.carlauncher.companion.ui.common.IconBadge
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.common.NeonSegmentedSelector
import com.carlauncher.companion.ui.common.SectionLabel
import com.carlauncher.companion.ui.legal.LegalDocument
import com.carlauncher.companion.ui.theme.AccentAlert
import com.carlauncher.companion.ui.theme.AccentProfile
import kotlinx.coroutines.launch

private enum class AuthMode { SIGN_IN, SIGN_UP }

/**
 * Sign in or create an account.
 *
 * Note the password lives in a [String] in Compose state, because `TextField` gives no
 * choice. It is converted to a `CharArray` at the repository boundary, which is where it
 * can actually be wiped — see [AuthRepository]. Nothing here logs it.
 */
@Composable
fun AuthScreen(
    authRepository: AuthRepository,
    onSignedIn: () -> Unit,
    onShowRecoveryCode: (String) -> Unit,
    onForgotPassword: () -> Unit,
    onOpenLegal: (LegalDocument) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val accent = AccentProfile

    var mode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var email by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var acceptedTerms by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val signingUp = mode == AuthMode.SIGN_UP
    val canSubmit = email.isNotBlank() && password.isNotBlank() && !busy &&
        (!signingUp || (username.isNotBlank() && acceptedTerms))

    fun submit() {
        error = null
        notice = null
        busy = true
        scope.launch {
            if (signingUp) {
                when (val result = authRepository.signUp(email, password.toCharArray(), username, acceptedTerms)) {
                    is SignUpResult.Active -> {
                        password = ""
                        onShowRecoveryCode(result.recoveryCode)
                    }
                    SignUpResult.NeedsEmailConfirmation -> {
                        password = ""
                        notice = context.getString(R.string.auth_notice_account_created)
                        mode = AuthMode.SIGN_IN
                    }
                    is SignUpResult.Failure -> error = result.error.message(context)
                }
            } else {
                when (val result = authRepository.signIn(email, password.toCharArray())) {
                    is SignInResult.Success -> {
                        password = ""
                        // Non-null when signup couldn't provision keys (email-confirmation
                        // flow) and this sign-in did — the code must still be shown once.
                        result.recoveryCode?.let(onShowRecoveryCode) ?: onSignedIn()
                    }
                    is SignInResult.Failure -> error = result.error.message(context)
                }
            }
            busy = false
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(Icons.Filled.CloudQueue, accent, size = 64.dp)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.auth_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.auth_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        NeonSegmentedSelector(
            options = listOf(AuthMode.SIGN_IN, AuthMode.SIGN_UP),
            selected = mode,
            label = { if (it == AuthMode.SIGN_IN) stringResource(R.string.auth_mode_sign_in) else stringResource(R.string.auth_mode_create_account) },
            onSelect = { mode = it; error = null; notice = null },
            accent = accent,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))

        NeonCard(accent, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim() },
                    label = { Text(stringResource(R.string.auth_field_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (signingUp) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' } },
                        label = { Text(stringResource(R.string.auth_field_username)) },
                        supportingText = { Text(stringResource(R.string.auth_username_supporting_text)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.auth_field_password)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    supportingText = if (signingUp) {
                        { Text(stringResource(R.string.auth_password_supporting_text_format, AuthRepository.MIN_PASSWORD_LENGTH)) }
                    } else {
                        null
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) {
                                    stringResource(R.string.auth_hide_password_content_description)
                                } else {
                                    stringResource(R.string.auth_show_password_content_description)
                                },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (signingUp) {
                    Spacer(Modifier.height(16.dp))
                    TermsGate(
                        accepted = acceptedTerms,
                        onAcceptedChange = { acceptedTerms = it },
                        onOpenLegal = onOpenLegal,
                        accent = accent,
                    )
                }

                error?.let {
                    Spacer(Modifier.height(14.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = AccentAlert)
                }
                notice?.let {
                    Spacer(Modifier.height(14.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = accent)
                }

                Spacer(Modifier.height(18.dp))
                if (busy) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp))
                    }
                } else {
                    NeonPill(
                        text = if (signingUp) stringResource(R.string.auth_mode_create_account) else stringResource(R.string.auth_mode_sign_in),
                        accent = accent,
                        selected = canSubmit,
                        large = true,
                        onClick = { if (canSubmit) submit() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (!signingUp) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onForgotPassword, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.auth_forgot_password), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (signingUp) {
            Spacer(Modifier.height(20.dp))
            NeonCard(accent, Modifier.fillMaxWidth(), glow = false) {
                Column(Modifier.padding(16.dp)) {
                    SectionLabel(stringResource(R.string.auth_before_you_start_label), tint = accent)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.auth_before_you_start_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TermsGate(
    accepted: Boolean,
    onAcceptedChange: (Boolean) -> Unit,
    onOpenLegal: (LegalDocument) -> Unit,
    accent: androidx.compose.ui.graphics.Color,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = accepted,
                onCheckedChange = onAcceptedChange,
                colors = CheckboxDefaults.colors(checkedColor = accent),
            )
            Text(
                stringResource(R.string.auth_terms_gate_text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onAcceptedChange(!accepted) },
            )
        }
        Row(Modifier.padding(start = 12.dp)) {
            TextButton(onClick = { onOpenLegal(LegalDocument.TERMS) }) {
                Text(stringResource(R.string.auth_terms_of_use), style = MaterialTheme.typography.labelMedium, color = accent)
            }
            TextButton(onClick = { onOpenLegal(LegalDocument.PRIVACY) }) {
                Text(stringResource(R.string.auth_privacy_policy), style = MaterialTheme.typography.labelMedium, color = accent)
            }
        }
    }
}

/**
 * Sign-in failures are deliberately vague: distinguishing "no such account" from "wrong
 * password" tells anyone who asks which email addresses are registered here.
 */
internal fun AuthError.message(context: Context): String = when (this) {
    AuthError.INVALID_CREDENTIALS -> context.getString(R.string.auth_error_invalid_credentials)
    AuthError.USERNAME_TAKEN -> context.getString(R.string.auth_error_username_taken)
    AuthError.INVALID_USERNAME -> context.getString(R.string.auth_error_invalid_username)
    AuthError.INVALID_EMAIL -> context.getString(R.string.auth_error_invalid_email)
    AuthError.WEAK_PASSWORD -> context.getString(R.string.auth_error_weak_password_format, AuthRepository.MIN_PASSWORD_LENGTH)
    AuthError.TERMS_NOT_ACCEPTED -> context.getString(R.string.auth_error_terms_not_accepted)
    AuthError.EMAIL_NOT_CONFIRMED -> context.getString(R.string.auth_error_email_not_confirmed)
    AuthError.RATE_LIMITED -> context.getString(R.string.auth_error_rate_limited)
    AuthError.OFFLINE -> context.getString(R.string.auth_error_offline)
    AuthError.UNKNOWN -> context.getString(R.string.auth_error_unknown)
}
