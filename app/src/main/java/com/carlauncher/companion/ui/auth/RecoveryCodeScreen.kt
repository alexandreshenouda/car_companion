package com.carlauncher.companion.ui.auth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carlauncher.companion.R
import com.carlauncher.companion.ui.common.IconBadge
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.common.SectionLabel
import com.carlauncher.companion.ui.theme.AccentAlert
import com.carlauncher.companion.ui.theme.AccentProfile

/**
 * Shows the end-to-end encryption recovery code, exactly once.
 *
 * This screen is load-bearing rather than decorative. The code is the only thing that can
 * recover encrypted GPS and statistics backups after a forgotten password — there is no
 * server-side copy, because a server-side copy would defeat the encryption entirely. If the
 * user skims past this, that data is one forgotten password away from being gone for good.
 *
 * Hence: no back navigation, an explicit confirmation checkbox, and blunt wording.
 */
@Composable
fun RecoveryCodeScreen(
    recoveryCode: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    /** True when this replaces a code lost with a forgotten password. */
    isReplacement: Boolean = false,
) {
    val context = LocalContext.current
    val accent = AccentProfile
    var confirmed by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(Icons.Filled.Key, accent, size = 64.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            if (isReplacement) stringResource(R.string.recovery_code_new_title) else stringResource(R.string.recovery_code_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.recovery_code_write_down),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (isReplacement) {
            Spacer(Modifier.height(16.dp))
            NeonCard(AccentAlert, Modifier.fillMaxWidth(), glow = false) {
                Column(Modifier.padding(16.dp)) {
                    SectionLabel(stringResource(R.string.recovery_code_previous_discarded_label), tint = AccentAlert)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.recovery_code_previous_discarded_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        NeonCard(accent, Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    recoveryCode,
                    style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 2.sp),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                NeonPill(
                    text = stringResource(R.string.recovery_code_copy_button),
                    accent = accent,
                    leading = {
                        androidx.compose.material3.Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.height(16.dp),
                        )
                    },
                    onClick = { copyToClipboard(context, recoveryCode) },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        NeonCard(AccentAlert, Modifier.fillMaxWidth(), glow = false) {
            Column(Modifier.padding(16.dp)) {
                SectionLabel(stringResource(R.string.recovery_code_what_for_label), tint = AccentAlert)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.recovery_code_what_for_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = confirmed,
                onCheckedChange = { confirmed = it },
                colors = CheckboxDefaults.colors(checkedColor = accent),
            )
            Text(
                stringResource(R.string.recovery_code_confirm_saved),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))
        NeonPill(
            text = stringResource(R.string.recovery_code_continue_button),
            accent = accent,
            selected = confirmed,
            large = true,
            onClick = { if (confirmed) onConfirmed() },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    // Marked sensitive so it stays out of clipboard previews and history on Android 13+.
    val clip = ClipData.newPlainText(context.getString(R.string.recovery_code_clip_label), text).apply {
        description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    clipboard.setPrimaryClip(clip)
}
