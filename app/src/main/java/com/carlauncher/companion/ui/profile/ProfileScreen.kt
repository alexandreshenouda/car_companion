package com.carlauncher.companion.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlauncher.companion.R
import com.carlauncher.companion.data.cloud.AuthRepository
import com.carlauncher.companion.data.cloud.CloudSessionState
import com.carlauncher.companion.data.db.CarEntity
import com.carlauncher.companion.data.model.departmentsFromCodes
import com.carlauncher.companion.data.model.frenchDepartments
import com.carlauncher.companion.data.repo.CarRepository
import com.carlauncher.companion.data.repo.EventRepository
import com.carlauncher.companion.data.repo.ProfileRepository
import com.carlauncher.companion.data.repo.TrophyRepository
import com.carlauncher.companion.data.repo.TrophyState
import com.carlauncher.companion.data.repo.XpRepository
import com.carlauncher.companion.data.repo.XpState
import com.carlauncher.companion.ui.common.CarPhoto
import com.carlauncher.companion.ui.common.IconBadge
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.common.NeonProgressBar
import com.carlauncher.companion.ui.common.SectionLabel
import com.carlauncher.companion.ui.theme.AccentEvents
import com.carlauncher.companion.ui.theme.AccentGarage
import com.carlauncher.companion.ui.theme.AccentProfile
import com.carlauncher.companion.ui.theme.AccentTrophy
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    profileRepository: ProfileRepository,
    carRepository: CarRepository,
    eventRepository: EventRepository,
    trophyRepository: TrophyRepository,
    xpRepository: XpRepository,
    authRepository: AuthRepository,
    onOpenGarage: () -> Unit,
    onOpenCar: (String) -> Unit,
    onOpenEvents: () -> Unit,
    onOpenTrophies: () -> Unit,
    onOpenCloud: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenLeaderboard: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val cloudState by authRepository.sessionState
        .collectAsStateWithLifecycle(initialValue = CloudSessionState.Loading)
    val profile by profileRepository.observe().collectAsStateWithLifecycle(initialValue = null)
    val cars by carRepository.observeCars().collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteCar by carRepository.observeFavoriteCar().collectAsStateWithLifecycle(initialValue = null)
    val events by eventRepository.observeEvents().collectAsStateWithLifecycle(initialValue = emptyList())
    val trophyState by trophyRepository.observeState().collectAsStateWithLifecycle(initialValue = TrophyState())
    val xpState by xpRepository.observeState()
        .collectAsStateWithLifecycle(initialValue = XpState(totalXp = 0, level = 1, xpIntoLevel = 0, xpForNextLevel = 100, currentStreakDays = 0, bestStreakDays = 0))
    val scope = rememberCoroutineScope()

    var editingProfile by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // 1. XP Level
        LevelBadge(xpState)

        // 2. Favorite car
        favoriteCar?.let { car ->
            Spacer(Modifier.height(14.dp))
            FavoriteCarHero(car = car, onClick = { onOpenCar(car.id) })
        }

        // 3. Cloud Account
        Spacer(Modifier.height(14.dp))
        NavigationTile(
            icon = Icons.Filled.CloudQueue,
            accent = AccentProfile,
            title = stringResource(R.string.profile_cloud_account_title),
            subtitle = when (val s = cloudState) {
                is CloudSessionState.SignedIn -> s.account.email ?: stringResource(R.string.profile_cloud_signed_in)
                CloudSessionState.SignedOut -> stringResource(R.string.profile_cloud_sign_in_prompt)
                CloudSessionState.Disabled -> stringResource(R.string.profile_cloud_sign_in_prompt)
                CloudSessionState.Loading -> stringResource(R.string.profile_cloud_checking)
            },
            onClick = onOpenCloud,
        )

        // 4. Line with Garage, Events, Trophies
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatTileButton(
                icon = Icons.Filled.DirectionsCar,
                accent = AccentGarage,
                value = "${cars.size}",
                label = stringResource(R.string.profile_garage_section_label),
                modifier = Modifier.weight(1f),
                onClick = onOpenGarage,
            )
            StatTileButton(
                icon = Icons.Filled.Event,
                accent = AccentEvents,
                value = "${events.size}",
                label = stringResource(R.string.profile_events_title),
                modifier = Modifier.weight(1f),
                onClick = onOpenEvents,
            )
            StatTileButton(
                icon = Icons.Filled.EmojiEvents,
                accent = AccentTrophy,
                value = "${trophyState.unlockedCount}/${trophyState.totalCount}",
                label = stringResource(R.string.profile_trophies_title),
                modifier = Modifier.weight(1f),
                onClick = onOpenTrophies,
            )
        }

        // 5. Line with Friends and Leaderboard
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NavigationTile(
                icon = Icons.Filled.People,
                accent = AccentProfile,
                title = stringResource(R.string.profile_friends_title),
                subtitle = stringResource(R.string.profile_friends_subtitle),
                modifier = Modifier.weight(1f),
                onClick = onOpenFriends,
            )
            NavigationTile(
                icon = Icons.Filled.Leaderboard,
                accent = AccentProfile,
                title = stringResource(R.string.profile_leaderboard_title),
                subtitle = stringResource(R.string.profile_leaderboard_subtitle),
                modifier = Modifier.weight(1f),
                onClick = onOpenLeaderboard,
            )
        }

        // 6. Personal info
        Spacer(Modifier.height(14.dp))
        NeonCard(accent = AccentProfile, modifier = Modifier.fillMaxWidth(), topBar = true) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        IconBadge(Icons.Filled.Person, AccentProfile)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            SectionLabel(stringResource(R.string.profile_personal_info_label), tint = AccentProfile)
                            Spacer(Modifier.height(4.dp))
                            val personalLine = listOfNotNull(
                                profile?.age?.let { pluralStringResource(R.plurals.profile_age_years, it, it) },
                                profile?.city?.takeIf { it.isNotBlank() },
                            ).joinToString(" · ").ifBlank { stringResource(R.string.profile_not_set) }
                            Text(personalLine, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    IconButton(onClick = { editingProfile = true }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.profile_edit_personal_info_content_description),
                            tint = AccentProfile,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                val departments = departmentsFromCodes(profile?.departmentCodes)
                if (departments.isEmpty()) {
                    Text(
                        stringResource(R.string.profile_no_departments_selected),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        departments.forEach { dept ->
                            NeonPill(dept.label, AccentProfile)
                        }
                    }
                }
            }
        }

        // 7. Settings
        Spacer(Modifier.height(14.dp))
        NavigationTile(
            icon = Icons.Filled.Settings,
            accent = AccentProfile,
            title = stringResource(R.string.nav_title_settings),
            subtitle = stringResource(R.string.profile_settings_subtitle),
            onClick = onOpenSettings,
        )
    }

    if (editingProfile) {
        EditProfileDialog(
            age = profile?.age,
            city = profile?.city.orEmpty(),
            departmentCodes = profile?.departmentCodes.orEmpty(),
            onDismiss = { editingProfile = false },
            onConfirm = { age, city, departmentCodes ->
                scope.launch { profileRepository.update(age, city.ifBlank { null }, departmentCodes.ifBlank { null }) }
                editingProfile = false
            },
        )
    }
}

@Composable
private fun StatTileButton(
    accent: Color,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    NeonCard(
        accent = accent,
        modifier = modifier,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    IconBadge(icon = icon, tint = accent, size = 32.dp)
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NavigationTile(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    NeonCard(
        accent = accent,
        modifier = modifier,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(icon = icon, tint = accent, size = 36.dp)
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Full-width photo hero for the favorite car — the one thing this screen leads with. */
@Composable
private fun FavoriteCarHero(car: CarEntity, onClick: () -> Unit) {
    NeonCard(accent = AccentTrophy, modifier = Modifier.fillMaxWidth(), topBar = false, onClick = onClick) {
        Box(Modifier.fillMaxWidth().height(190.dp)) {
            CarPhoto(photoPath = car.photoPath, modifier = Modifier.fillMaxSize(), targetWidthPx = 720)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to MaterialTheme.colorScheme.scrim.copy(alpha = 0.85f),
                    ),
                ),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = AccentTrophy, modifier = Modifier.height(16.dp))
                    Spacer(Modifier.width(6.dp))
                    SectionLabel(stringResource(R.string.profile_favorite_car_label), tint = AccentTrophy)
                }
                Spacer(Modifier.height(4.dp))
                Text(car.name, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                listOfNotNull(car.brand, car.model).joinToString(" ").takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f))
                }
            }
        }
    }
}

/** Level + XP progress toward the next level, plus the current login streak. */
@Composable
private fun LevelBadge(xpState: XpState) {
    NeonCard(accent = AccentTrophy, modifier = Modifier.fillMaxWidth(), glow = false) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.profile_level_format, xpState.level), style = MaterialTheme.typography.titleLarge)
                if (xpState.currentStreakDays > 0) {
                    NeonPill(
                        text = pluralStringResource(R.plurals.profile_streak_days, xpState.currentStreakDays, xpState.currentStreakDays),
                        accent = AccentTrophy,
                        leading = { Icon(Icons.Filled.LocalFireDepartment, contentDescription = null, tint = AccentTrophy, modifier = Modifier.size(14.dp)) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            NeonProgressBar(
                progress = if (xpState.xpForNextLevel > 0) xpState.xpIntoLevel.toFloat() / xpState.xpForNextLevel.toFloat() else 0f,
                accent = AccentTrophy,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.profile_xp_progress_format, xpState.xpIntoLevel, xpState.xpForNextLevel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditProfileDialog(
    age: Int?,
    city: String,
    departmentCodes: String,
    onDismiss: () -> Unit,
    onConfirm: (age: Int?, city: String, departmentCodes: String) -> Unit,
) {
    var ageText by remember { mutableStateOf(age?.toString().orEmpty()) }
    var cityText by remember { mutableStateOf(city) }
    var selectedCodes by remember { mutableStateOf(departmentCodes.split(",").filter { it.isNotBlank() }.toSet()) }
    var pickingDepartments by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_personal_info_label)) },
        text = {
            Column {
                OutlinedTextField(
                    value = ageText,
                    onValueChange = { ageText = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.profile_field_age)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cityText,
                    onValueChange = { cityText = it },
                    label = { Text(stringResource(R.string.profile_field_city)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { pickingDepartments = true }) {
                    Text(
                        if (selectedCodes.isEmpty()) {
                            stringResource(R.string.profile_select_departments)
                        } else {
                            pluralStringResource(R.plurals.profile_departments_selected, selectedCodes.size, selectedCodes.size)
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(ageText.toIntOrNull(), cityText.trim(), selectedCodes.joinToString(",")) }) {
                Text(stringResource(R.string.car_detail_save_button))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )

    if (pickingDepartments) {
        DepartmentPickerDialog(
            selected = selectedCodes,
            onDismiss = { pickingDepartments = false },
            onConfirm = { codes ->
                selectedCodes = codes
                pickingDepartments = false
            },
        )
    }
}

@Composable
private fun DepartmentPickerDialog(
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var current by remember { mutableStateOf(selected) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_departments_dialog_title)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(frenchDepartments, key = { it.code }) { dept ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                current = if (dept.code in current) current - dept.code else current + dept.code
                            },
                    ) {
                        Checkbox(
                            checked = dept.code in current,
                            onCheckedChange = { checked ->
                                current = if (checked) current + dept.code else current - dept.code
                            },
                        )
                        Text(dept.label, modifier = Modifier.padding(top = 14.dp))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(current) }) { Text(stringResource(R.string.common_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
