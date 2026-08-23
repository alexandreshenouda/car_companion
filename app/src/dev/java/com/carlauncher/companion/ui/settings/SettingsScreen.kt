package com.carlauncher.companion.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlauncher.companion.R
import com.carlauncher.companion.data.settings.BackgroundFeatureSettings
import com.carlauncher.companion.ui.common.SectionLabel

/**
 * App-behavior kill switches — currently just the two background-only mechanisms
 * [BackgroundFeatureSettings] gates. Reached from the main screen's top bar (dev flavor only).
 */
@Composable
fun SettingsScreen(settings: BackgroundFeatureSettings) {
    val firebaseListenersEnabled by settings.firebaseListenersEnabled.collectAsStateWithLifecycle()
    val backgroundRadarEnabled by settings.backgroundRadarEnabled.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        SectionLabel(stringResource(R.string.settings_background_section_label), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.settings_background_section_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        SettingsToggleRow(
            title = stringResource(R.string.settings_firebase_listeners_label),
            description = stringResource(R.string.settings_firebase_listeners_hint),
            enabled = firebaseListenersEnabled,
            onToggle = settings::setFirebaseListenersEnabled,
        )
        Spacer(Modifier.height(12.dp))
        SettingsToggleRow(
            title = stringResource(R.string.settings_background_radar_label),
            description = stringResource(R.string.settings_background_radar_hint),
            enabled = backgroundRadarEnabled,
            onToggle = settings::setBackgroundRadarEnabled,
        )
    }
}

@Composable
private fun SettingsToggleRow(title: String, description: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedThumbColor = accent, checkedTrackColor = accent.copy(alpha = 0.4f)),
        )
    }
}
