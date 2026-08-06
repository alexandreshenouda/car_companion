package com.carlauncher.companion.ui.auth

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
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.carlauncher.companion.R
import com.carlauncher.companion.data.cloud.AuthRepository
import com.carlauncher.companion.data.cloud.PasswordResetOutcome
import com.carlauncher.companion.data.cloud.crypto.CryptoBox
import com.carlauncher.companion.ui.common.IconBadge
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.common.SectionLabel
import com.carlauncher.companion.ui.theme.AccentAlert
import com.carlauncher.companion.ui.theme.AccentProfile
import kotlinx.coroutines.launch

/**
 * Step 1 of the forgotten-password flow: ask Supabase to email a reset link.
 *
 * A link, not a typed code, because Supabase locked auth email template customisation for
 * new free-tier projects on its default email provider (June 2026) — `{{ .Token }}` can't be
 * added, but the stock template already contains the link. Tapping it reopens the app on
 * [SetNewPasswordScreen] via the deep-link scheme registered in the manifest.
 */
@Composable
fun PasswordResetRequestScreen(
    authRepository: AuthRepository,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val accent = AccentProfile

    var email by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(
            if (sent) Icons.Filled.MarkEmailRead else Icons.Filled.LockReset,
            accent,
            size = 64.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (sent) stringResource(R.string.password_reset_check_email_title) else stringResource(R.string.password_reset_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))

        NeonCard(accent, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                if (sent) {
                    Text(
                        stringResource(R.string.password_reset_sent_body_format, email),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    NeonPill(stringResource(R.string.common_done), accent, selected = true, large = true, onClick = onDone, modifier = Modifier.fillMaxWidth())
                } else {
                    Text(
                        stringResource(R.string.password_reset_email_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it.trim() },
                        label = { Text(stringResource(R.string.auth_field_email)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    BusyAction(busy, accent, stringResource(R.string.password_reset_send_link_button), email.isNotBlank()) {
                        busy = true
                        scope.launch {
                            authRepository.requestPasswordReset(email)
                            busy = false
                            // Always advances, registered or not: saying "no such account"
                            // would make this an oracle for which emails have signed up.
                            sent = true
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Step 2, reached when a reset link produced a session.
 *
 * The E2E recovery code is asked for here and is genuinely optional: without a working one
 * the account comes back but the encrypted GPS/statistics backups do not, because the key
 * that opened them came from the password being replaced. That is stated before the user
 * commits, and a fresh key is issued afterwards so future backups still work.
 */
@Composable
fun SetNewPasswordScreen(
    authRepository: AuthRepository,
    onDone: () -> Unit,
    onNewRecoveryCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val accent = AccentProfile

    var newPassword by remember { mutableStateOf("") }
    var recoveryCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var succeeded by remember { mutableStateOf(false) }

    // Backing out abandons the reset rather than leaving the flag set — otherwise the user
    // is signed in, stuck in a half-finished reset, with no button anywhere to return here.
    DisposableEffect(Unit) {
        onDispose { authRepository.clearPendingPasswordReset() }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(Icons.Filled.LockReset, accent, size = 64.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            if (succeeded) stringResource(R.string.password_reset_changed_title) else stringResource(R.string.password_reset_choose_new_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))

        NeonCard(accent, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                if (succeeded) {
                    Text(
                        stringResource(R.string.password_reset_backups_restored),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    NeonPill(stringResource(R.string.common_done), accent, selected = true, large = true, onClick = onDone, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text(stringResource(R.string.password_reset_new_password_field)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next,
                        ),
                        supportingText = { Text(stringResource(R.string.password_reset_new_password_supporting_format, AuthRepository.MIN_PASSWORD_LENGTH)) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(16.dp))
                    SectionLabel(stringResource(R.string.password_reset_encrypted_backups_label), tint = accent)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.password_reset_recovery_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = recoveryCode,
                        onValueChange = { recoveryCode = it.uppercase() },
                        label = { Text(stringResource(R.string.password_reset_recovery_code_field)) },
                        singleLine = true,
                        isError = recoveryCode.isNotBlank() && !CryptoBox.isPlausibleRecoveryCode(recoveryCode),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(16.dp))
                    val ready = newPassword.length >= AuthRepository.MIN_PASSWORD_LENGTH
                    BusyAction(busy, accent, stringResource(R.string.password_reset_set_button), ready) {
                        error = null
                        busy = true
                        scope.launch {
                            val outcome = authRepository.completePasswordReset(
                                newPassword.toCharArray(),
                                recoveryCode.takeIf { it.isNotBlank() },
                            )
                            newPassword = ""
                            recoveryCode = ""
                            busy = false
                            when (outcome) {
                                is PasswordResetOutcome.BackupsLost -> {
                                    authRepository.clearPendingPasswordReset()
                                    // A fresh key was issued, so its recovery code must be
                                    // shown — same one-time screen as at signup.
                                    onNewRecoveryCode(outcome.newRecoveryCode)
                                }
                                PasswordResetOutcome.BackupsRecovered -> {
                                    authRepository.clearPendingPasswordReset()
                                    succeeded = true
                                }
                                PasswordResetOutcome.Failed ->
                                    error = context.getString(R.string.password_reset_failed)
                            }
                        }
                    }
                }

                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = AccentAlert)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BusyAction(
    busy: Boolean,
    accent: Color,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (busy) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp))
        }
    } else {
        NeonPill(
            text = label,
            accent = accent,
            selected = enabled,
            large = true,
            onClick = { if (enabled) onClick() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
