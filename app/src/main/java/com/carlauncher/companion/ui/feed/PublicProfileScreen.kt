package com.carlauncher.companion.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.carlauncher.companion.R
import com.carlauncher.companion.data.cloud.SharedContentRepository
import com.carlauncher.companion.data.cloud.dto.CarRestoreRow
import com.carlauncher.companion.data.cloud.dto.EventRestoreRow
import com.carlauncher.companion.data.cloud.dto.PublicProfileRow
import com.carlauncher.companion.data.cloud.dto.TrophyUnlockRestoreRow
import com.carlauncher.companion.data.cloud.parseIsoToEpochMilli
import com.carlauncher.companion.data.model.Trophy
import com.carlauncher.companion.data.model.color
import com.carlauncher.companion.data.model.frenchDepartments
import com.carlauncher.companion.data.model.icon
import com.carlauncher.companion.ui.common.DashboardRow
import com.carlauncher.companion.ui.common.IconBadge
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.common.SectionLabel
import com.carlauncher.companion.ui.theme.AccentGarage
import com.carlauncher.companion.ui.theme.AccentProfile
import com.carlauncher.companion.ui.theme.AccentTrophy
import com.carlauncher.companion.util.formatAbsolute

/**
 * Someone else's profile — always reduced server-side, via `get_public_profile`, to whatever
 * they chose to expose (departments/trophies/garage each independently, see `schema.sql`'s
 * `share_profile`/`share_garage`/`share_trophies` columns). Nothing here is decided client-side.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PublicProfileScreen(
    userId: String,
    sharedContentRepository: SharedContentRepository,
    onOpenCar: (String) -> Unit,
    onOpenEvent: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var profile by remember(userId) { mutableStateOf<PublicProfileRow?>(null) }
    var cars by remember(userId) { mutableStateOf<List<CarRestoreRow>>(emptyList()) }
    var events by remember(userId) { mutableStateOf<List<EventRestoreRow>>(emptyList()) }
    var trophies by remember(userId) { mutableStateOf<List<TrophyUnlockRestoreRow>>(emptyList()) }
    var loading by remember(userId) { mutableStateOf(true) }

    LaunchedEffect(userId) {
        loading = true
        profile = sharedContentRepository.getProfile(userId)
        cars = sharedContentRepository.listSharedCars(userId)
        events = sharedContentRepository.listSharedEvents(userId)
        trophies = if (profile?.shareTrophies == true) sharedContentRepository.listTrophies(userId) else emptyList()
        loading = false
    }

    if (loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AccentProfile)
        }
        return
    }

    val current = profile
    if (current == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconBadge(Icons.Filled.Person, MaterialTheme.colorScheme.onSurfaceVariant, size = 64.dp)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.public_profile_not_visible), style = MaterialTheme.typography.titleMedium)
            }
        }
        return
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.Person, AccentProfile, size = 56.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(current.displayName?.takeIf { it.isNotBlank() } ?: current.username, style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.friends_at_username_format, current.username), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        current.city?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodyLarge)
        }

        if (current.departmentCodes.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionLabel(stringResource(R.string.public_profile_would_like_to_meet_label), tint = AccentProfile)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                current.departmentCodes.forEach { code ->
                    val label = frenchDepartments.firstOrNull { it.code == code }?.label ?: code
                    NeonPill(text = label, accent = AccentProfile)
                }
            }
        }

        if (current.shareTrophies && trophies.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.public_profile_trophies_count_format, trophies.size), tint = AccentTrophy)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                trophies.forEach { row ->
                    val trophy = runCatching { Trophy.valueOf(row.trophyId) }.getOrNull()
                    if (trophy != null) {
                        IconBadge(trophy.icon, trophy.tier.color, size = 44.dp)
                    }
                }
            }
        }

        if (current.shareGarage && cars.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.profile_garage_section_label), tint = AccentGarage)
            cars.forEach { car ->
                DashboardRow(
                    icon = Icons.Filled.DirectionsCar,
                    iconTint = AccentGarage,
                    title = car.name,
                    subtitle = listOfNotNull(car.brand, car.model, car.year?.toString()).joinToString(" ").ifBlank { null },
                    onClick = { onOpenCar(car.id) },
                )
            }
        }

        if (events.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.public_profile_shared_events_label), tint = AccentProfile)
            events.forEach { event ->
                DashboardRow(
                    icon = Icons.Filled.Route,
                    iconTint = AccentProfile,
                    title = event.title,
                    subtitle = formatAbsolute(event.startTs.parseIsoToEpochMilli()),
                    onClick = { onOpenEvent(event.id) },
                )
            }
        }

        if (!current.shareGarage && !current.shareTrophies && cars.isEmpty() && events.isEmpty()) {
            Spacer(Modifier.height(32.dp))
            Text(
                stringResource(R.string.public_profile_nothing_shared),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
