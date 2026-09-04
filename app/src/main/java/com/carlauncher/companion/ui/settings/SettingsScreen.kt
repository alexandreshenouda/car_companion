package com.carlauncher.companion.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlauncher.companion.R
import com.carlauncher.companion.data.AppContainer
import com.carlauncher.companion.data.repo.GasStationRepository
import com.carlauncher.companion.data.repo.GasStationUpdateState
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.common.SectionLabel
import com.carlauncher.companion.ui.nav.BetaBackgroundSettings
import com.carlauncher.companion.ui.nav.BetaTopBarState
import com.carlauncher.companion.ui.theme.NeonAmber
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings screen accessible from the Profile tab in all flavors.
 *
 * Configurable display options (such as the main top bar toggle) and background activity
 * kill switches are flavor-scoped and provided via beta seams.
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    betaTopBarState: BetaTopBarState,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        SectionLabel(stringResource(R.string.settings_section_display), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.settings_display_section_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (betaTopBarState.isAvailable) {
            SettingsToggleRow(
                title = stringResource(R.string.settings_display_top_bar_title),
                description = stringResource(R.string.settings_display_top_bar_desc),
                enabled = betaTopBarState.isTopBarEnabled,
                onToggle = betaTopBarState.setTopBarEnabled,
            )
        } else {
            Text(
                stringResource(R.string.settings_display_no_options),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (betaTopBarState.isAvailable) {
            Spacer(Modifier.height(20.dp))
        }
        BetaBackgroundSettings(container)

        Spacer(Modifier.height(24.dp))
        GasStationSettingsSection(container.gasStationRepository)
    }
}

@Composable
fun SettingsToggleRow(
    title: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = accent,
                checkedTrackColor = accent.copy(alpha = 0.4f),
            ),
        )
    }
}

@Composable
private fun GasStationSettingsSection(repository: GasStationRepository) {
    val updateState by repository.updateState.collectAsStateWithLifecycle()
    val lastUpdateEpochMs by repository.lastUpdateEpochMs.collectAsStateWithLifecycle()
    val stationCount by repository.stationCount.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val accent = NeonAmber

    SectionLabel(stringResource(R.string.settings_gas_station_section_label), tint = accent)
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.settings_gas_station_section_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    NeonCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val statusText = if (lastUpdateEpochMs > 0L) {
                val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                stringResource(
                    R.string.settings_gas_station_last_update_format,
                    dateFormat.format(Date(lastUpdateEpochMs)),
                    stationCount,
                )
            } else {
                stringResource(R.string.settings_gas_station_never_updated)
            }

            Text(
                statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (updateState is GasStationUpdateState.Error) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.settings_gas_station_error_format,
                        (updateState as GasStationUpdateState.Error).message,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(14.dp))

            when (updateState) {
                is GasStationUpdateState.Downloading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = accent,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.settings_gas_station_downloading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = accent,
                        )
                    }
                }
                is GasStationUpdateState.Indexing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = accent,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.settings_gas_station_indexing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = accent,
                        )
                    }
                }
                else -> {
                    NeonPill(
                        text = stringResource(R.string.settings_gas_station_update_button),
                        accent = accent,
                        selected = true,
                        large = true,
                        onClick = {
                            scope.launch {
                                repository.updateData()
                            }
                        },
                    )
                }
            }
        }
    }
}

