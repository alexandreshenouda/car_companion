package com.carlauncher.companion.ui.cloud

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlauncher.companion.R
import com.carlauncher.companion.data.cloud.CloudPrefsRepository
import com.carlauncher.companion.data.cloud.CloudRestoreManager
import com.carlauncher.companion.data.cloud.CloudSyncManager
import com.carlauncher.companion.data.cloud.CloudSyncWorker
import com.carlauncher.companion.data.cloud.FeedScope
import com.carlauncher.companion.data.cloud.LeaderboardVisibility
import com.carlauncher.companion.data.cloud.ProfileSection
import com.carlauncher.companion.data.cloud.SyncCategory
import com.carlauncher.companion.data.cloud.Visibility
import com.carlauncher.companion.data.cloud.descriptionRes
import com.carlauncher.companion.data.cloud.isEnabled
import com.carlauncher.companion.data.cloud.labelRes
import com.carlauncher.companion.data.db.CloudPrefsEntity
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.common.NeonSegmentedSelector
import com.carlauncher.companion.ui.common.SectionLabel
import com.carlauncher.companion.ui.theme.AccentAlert
import com.carlauncher.companion.ui.theme.AccentProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * What gets backed up, how widely it's shared, and the manual sync/restore actions. Reached
 * from the signed-in Cloud account screen ([com.carlauncher.companion.ui.auth.CloudEntryScreen]).
 */
@Composable
fun CloudSettingsScreen(
    cloudPrefsRepository: CloudPrefsRepository,
    cloudSyncManager: CloudSyncManager,
    cloudRestoreManager: CloudRestoreManager,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = AccentProfile

    val prefs by cloudPrefsRepository.prefs.collectAsStateWithLifecycle(initialValue = CloudPrefsEntity())

    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var restoreMessage by remember { mutableStateOf<String?>(null) }
    var confirmingRestore by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        SectionLabel(stringResource(R.string.cloud_settings_backup_label), tint = accent)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.cloud_settings_backup_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        NeonCard(accent, Modifier.fillMaxWidth(), glow = false) {
            Column(Modifier.padding(4.dp)) {
                SyncCategory.entries.forEachIndexed { index, category ->
                    CategoryToggleRow(
                        category = category,
                        enabled = prefs.isEnabled(category),
                        accent = accent,
                        onToggle = { on ->
                            scope.launch {
                                cloudPrefsRepository.setEnabled(category, on)
                                // Backs up promptly rather than waiting for the next periodic
                                // tick (up to 30 minutes away) — flipping a switch should feel
                                // like it did something.
                                if (on) CloudSyncWorker.enqueueImmediate(context)
                            }
                        },
                    )
                    if (index != SyncCategory.entries.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.cloud_settings_visibility_label), tint = accent)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.cloud_settings_visibility_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        NeonSegmentedSelector(
            options = Visibility.entries,
            selected = Visibility.from(prefs.visibility),
            label = { stringResource(it.labelRes) },
            onSelect = {
                scope.launch {
                    cloudPrefsRepository.setVisibility(it)
                    // Pushed right away rather than left to the next periodic tick: this is a
                    // privacy control, and "I just set it to private" should take effect
                    // promptly, not whenever WorkManager next gets around to it.
                    CloudSyncWorker.enqueueImmediate(context)
                }
            },
            accent = accent,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))
        SectionLabel(stringResource(R.string.cloud_settings_feed_scope_label), tint = accent)
        Spacer(Modifier.height(10.dp))
        NeonSegmentedSelector(
            options = FeedScope.entries,
            selected = FeedScope.from(prefs.feedScope),
            label = { stringResource(it.labelRes) },
            onSelect = {
                scope.launch {
                    cloudPrefsRepository.setFeedScope(it)
                    CloudSyncWorker.enqueueImmediate(context)
                }
            },
            accent = accent,
            modifier = Modifier.fillMaxWidth(),
        )

        // Independent of Visibility above: a user can keep GPS/events private while still
        // competing on the leaderboard, or vice versa — so this is its own setting rather than
        // reusing that one.
        Spacer(Modifier.height(20.dp))
        SectionLabel(stringResource(R.string.cloud_settings_leaderboard_visibility_label), tint = accent)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.cloud_settings_leaderboard_visibility_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        NeonSegmentedSelector(
            options = LeaderboardVisibility.entries,
            selected = LeaderboardVisibility.from(prefs.leaderboardVisibility),
            label = { stringResource(it.labelRes) },
            onSelect = {
                scope.launch {
                    cloudPrefsRepository.setLeaderboardVisibility(it)
                    CloudSyncWorker.enqueueImmediate(context)
                }
            },
            accent = accent,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.cloud_settings_profile_sections_label), tint = accent)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.cloud_settings_profile_sections_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        NeonCard(accent, Modifier.fillMaxWidth(), glow = false) {
            Column(Modifier.padding(4.dp)) {
                ProfileSection.entries.forEachIndexed { index, section ->
                    ProfileSectionToggleRow(
                        section = section,
                        enabled = prefs.isEnabled(section),
                        accent = accent,
                        onToggle = { on ->
                            scope.launch {
                                cloudPrefsRepository.setEnabled(section, on)
                                CloudSyncWorker.enqueueImmediate(context)
                            }
                        },
                    )
                    if (index != ProfileSection.entries.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel(stringResource(R.string.cloud_settings_sync_label), tint = accent)
        Spacer(Modifier.height(10.dp))
        Text(
            lastSyncLabel(prefs.lastSyncAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        val syncSignInFirst = stringResource(R.string.cloud_settings_sync_sign_in_first)
        val syncNothingEnabled = stringResource(R.string.cloud_settings_sync_nothing_enabled)
        val syncPartialFormat = stringResource(R.string.cloud_settings_sync_partial_format)
        SyncActionButton(
            label = stringResource(R.string.cloud_settings_sync_now),
            busy = syncing,
            accent = accent,
            onClick = {
                syncing = true
                syncMessage = null
                scope.launch {
                    val result = withContext(Dispatchers.Default) { cloudSyncManager.syncAll() }
                    syncing = false
                    syncMessage = when {
                        result == null -> syncSignInFirst
                        result.failed.isEmpty() && result.attempted.isEmpty() -> syncNothingEnabled
                        result.failed.isEmpty() -> context.resources.getQuantityString(R.plurals.cloud_settings_sync_success, result.succeeded.size, result.succeeded.size)
                        else -> {
                            val names = result.failed.keys.joinToString { context.getString(it.labelRes) }
                            syncPartialFormat.format(names)
                        }
                    }
                }
            },
        )
        syncMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.cloud_settings_restore_label), tint = accent)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.cloud_settings_restore_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        SyncActionButton(
            label = stringResource(R.string.cloud_settings_restore_button),
            busy = restoring,
            accent = accent,
            onClick = { confirmingRestore = true },
        )
        restoreMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmingRestore) {
        AlertDialog(
            onDismissRequest = { confirmingRestore = false },
            title = { Text(stringResource(R.string.cloud_settings_restore_confirm_title)) },
            text = {
                Text(stringResource(R.string.cloud_settings_restore_confirm_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingRestore = false
                        restoring = true
                        restoreMessage = null
                        scope.launch {
                            val result = cloudRestoreManager.restoreAll()
                            restoring = false
                            restoreMessage = when {
                                result == null -> context.getString(R.string.cloud_settings_restore_sign_in_first)
                                else -> buildString {
                                    append(context.getString(R.string.cloud_settings_restore_result_format, result.succeeded.size, result.attempted.size))
                                    if (result.gpsSkippedNoKey) {
                                        append(" ")
                                        append(context.getString(R.string.cloud_settings_restore_gps_skipped))
                                    }
                                }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.cloud_settings_restore_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRestore = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun CategoryToggleRow(
    category: SyncCategory,
    enabled: Boolean,
    accent: Color,
    onToggle: (Boolean) -> Unit,
) = ToggleRow(stringResource(category.labelRes), stringResource(category.descriptionRes), enabled, accent, onToggle)

@Composable
private fun ProfileSectionToggleRow(
    section: ProfileSection,
    enabled: Boolean,
    accent: Color,
    onToggle: (Boolean) -> Unit,
) = ToggleRow(stringResource(section.labelRes), stringResource(section.descriptionRes), enabled, accent, onToggle)

@Composable
private fun ToggleRow(title: String, description: String, enabled: Boolean, accent: Color, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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

@Composable
private fun SyncActionButton(label: String, busy: Boolean, accent: Color, onClick: () -> Unit) {
    if (busy) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = accent, modifier = Modifier.height(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.cloud_settings_working), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        NeonPill(text = label, accent = accent, large = true, onClick = onClick, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun lastSyncLabel(lastSyncAt: Long?): String =
    if (lastSyncAt == null) {
        stringResource(R.string.cloud_settings_sync_never)
    } else {
        stringResource(R.string.cloud_settings_sync_last_format, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastSyncAt)))
    }
