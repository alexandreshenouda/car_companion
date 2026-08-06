package com.carlauncher.companion.ui.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.carlauncher.companion.R
import com.carlauncher.companion.data.cloud.AuthRepository
import com.carlauncher.companion.data.cloud.CloudPrefsRepository
import com.carlauncher.companion.data.cloud.CloudSessionState
import com.carlauncher.companion.data.cloud.CloudSyncManager
import com.carlauncher.companion.data.cloud.CloudSyncWorker
import com.carlauncher.companion.ui.common.IconBadge
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.legal.LegalDocument
import com.carlauncher.companion.ui.theme.AccentAlert
import com.carlauncher.companion.ui.theme.AccentProfile
import kotlinx.coroutines.launch

/**
 * Single entry point for everything cloud: shows the sign-in form or the account panel
 * depending on session state, so "Cloud account" in Profile always lands somewhere sensible.
 */
@Composable
fun CloudEntryScreen(
    authRepository: AuthRepository,
    cloudPrefsRepository: CloudPrefsRepository,
    cloudSyncManager: CloudSyncManager,
    onShowRecoveryCode: (String) -> Unit,
    onForgotPassword: () -> Unit,
    onOpenLegal: (LegalDocument) -> Unit,
    onOpenCloudSettings: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by authRepository.sessionState
        .collectAsStateWithLifecycle(initialValue = CloudSessionState.Loading)

    when (val current = state) {
        CloudSessionState.Disabled -> CloudDisabledNotice(modifier)

        CloudSessionState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AccentProfile)
        }

        CloudSessionState.SignedOut -> AuthScreen(
            authRepository = authRepository,
            onSignedIn = onDone,
            onShowRecoveryCode = onShowRecoveryCode,
            onForgotPassword = onForgotPassword,
            onOpenLegal = onOpenLegal,
            modifier = modifier,
        )

        is CloudSessionState.SignedIn -> SignedInPanel(
            email = current.account.email,
            authRepository = authRepository,
            cloudPrefsRepository = cloudPrefsRepository,
            cloudSyncManager = cloudSyncManager,
            onOpenLegal = onOpenLegal,
            onOpenCloudSettings = onOpenCloudSettings,
            onSignedOut = onDone,
            modifier = modifier,
        )
    }
}

@Composable
private fun CloudDisabledNotice(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(Icons.Filled.CloudOff, MaterialTheme.colorScheme.onSurfaceVariant, size = 64.dp)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.cloud_entry_disabled_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.cloud_entry_disabled_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SignedInPanel(
    email: String?,
    authRepository: AuthRepository,
    cloudPrefsRepository: CloudPrefsRepository,
    cloudSyncManager: CloudSyncManager,
    onOpenLegal: (LegalDocument) -> Unit,
    onOpenCloudSettings: () -> Unit,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = AccentProfile
    var confirmingDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(Icons.Filled.CloudQueue, accent, size = 64.dp)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.cloud_entry_signed_in_title), style = MaterialTheme.typography.headlineMedium)
        email?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(20.dp))
        NeonPill(
            text = stringResource(R.string.cloud_entry_settings_pill),
            accent = accent,
            large = true,
            onClick = onOpenCloudSettings,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        NeonPill(
            text = stringResource(R.string.cloud_entry_sign_out),
            accent = accent,
            large = true,
            onClick = {
                if (busy) return@NeonPill
                busy = true
                scope.launch {
                    authRepository.signOut()
                    // Signing out must not leave this device primed to re-upload, or looking
                    // like it already synced, the moment somebody else signs in on it: local
                    // preferences, per-row "already synced" markers, and the background job
                    // all have to be cleared together.
                    cloudPrefsRepository.resetOnSignOut()
                    cloudSyncManager.resetLocalSyncMarkers()
                    CloudSyncWorker.cancelAll(context)
                    busy = false
                    onSignedOut()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))
        NeonPill(
            text = stringResource(R.string.cloud_entry_delete_account),
            accent = AccentAlert,
            large = true,
            onClick = { confirmingDelete = true },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { onOpenLegal(LegalDocument.TERMS) }) {
            Text(stringResource(R.string.auth_terms_of_use), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { onOpenLegal(LegalDocument.PRIVACY) }) {
            Text(stringResource(R.string.auth_privacy_policy), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.cloud_entry_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.cloud_entry_delete_confirm_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        scope.launch {
                            authRepository.deleteAccount()
                            cloudPrefsRepository.resetOnSignOut()
                            onSignedOut()
                        }
                    },
                ) { Text(stringResource(R.string.common_delete), color = AccentAlert) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
