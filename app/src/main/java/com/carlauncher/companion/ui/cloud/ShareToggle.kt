package com.carlauncher.companion.ui.cloud

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlauncher.companion.R
import com.carlauncher.companion.data.cloud.AuthRepository
import com.carlauncher.companion.data.cloud.CloudPrefsRepository
import com.carlauncher.companion.data.cloud.CloudSessionState
import com.carlauncher.companion.data.cloud.Visibility
import com.carlauncher.companion.data.cloud.labelRes
import com.carlauncher.companion.ui.common.NeonCard

/**
 * Per-item share switch for a car or event detail screen. Hidden entirely when signed out or
 * cloud isn't configured — there is nothing for it to do, and showing a switch that silently
 * does nothing would be worse than not showing one.
 *
 * Deliberately shows the account's current global visibility level next to the switch: "shared"
 * on its own is ambiguous about *who* that means, and the two settings are easy to conflate —
 * this is the one place in the per-item flow where that gets spelled out.
 */
@Composable
fun ShareToggleCard(
    authRepository: AuthRepository,
    cloudPrefsRepository: CloudPrefsRepository,
    isShared: Boolean,
    accent: Color,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sessionState by authRepository.sessionState.collectAsStateWithLifecycle(initialValue = CloudSessionState.Loading)
    if (sessionState !is CloudSessionState.SignedIn) return

    val prefs by cloudPrefsRepository.prefs.collectAsStateWithLifecycle(initialValue = null)
    val visibility = Visibility.from(prefs?.visibility)

    NeonCard(accent, modifier.fillMaxWidth(), glow = false) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.share_toggle_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(2.dp))
                Text(
                    if (isShared) {
                        stringResource(R.string.share_toggle_visible_to_format, stringResource(visibility.labelRes))
                    } else {
                        stringResource(R.string.share_toggle_private)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = isShared,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = accent, checkedTrackColor = accent.copy(alpha = 0.4f)),
            )
        }
    }
}
