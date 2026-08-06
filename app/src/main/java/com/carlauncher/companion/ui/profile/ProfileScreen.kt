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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlauncher.companion.R
import com.carlauncher.companion.data.db.CarEntity
import com.carlauncher.companion.data.model.departmentsFromCodes
import com.carlauncher.companion.data.model.frenchDepartments
import com.carlauncher.companion.data.repo.CarRepository
import com.carlauncher.companion.data.cloud.AuthRepository
import com.carlauncher.companion.data.cloud.CloudSessionState
import com.carlauncher.companion.data.repo.EventRepository
import com.carlauncher.companion.data.repo.ProfileRepository
import com.carlauncher.companion.data.repo.TrophyRepository
import com.carlauncher.companion.data.repo.TrophyState
import com.carlauncher.companion.ui.common.AccentDivider
import com.carlauncher.companion.ui.common.CarPhoto
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.common.DashboardRow
import com.carlauncher.companion.ui.common.IconBadge
import com.carlauncher.companion.ui.common.SectionLabel
import com.carlauncher.companion.ui.theme.AccentEvents
import com.carlauncher.companion.ui.theme.AccentTrophy
import com.carlauncher.companion.ui.theme.AccentProfile
import com.carlauncher.companion.ui.theme.AccentGarage
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    profileRepository: ProfileRepository,
    carRepository: CarRepository,
    eventRepository: EventRepository,
    trophyRepository: TrophyRepository,
    authRepository: AuthRepository,
    onOpenGarage: () -> Unit,
    onOpenCar: (String) -> Unit,
    onOpenEvents: () -> Unit,
    onOpenTrophies: () -> Unit,
    onOpenCloud: () -> Unit,
    onOpenFriends: () -> Unit,
) {
    val cloudState by authRepository.sessionState
        .collectAsStateWithLifecycle(initialValue = CloudSessionState.Loading)
    val profile by profileRepository.observe().collectAsStateWithLifecycle(initialValue = null)
    val cars by carRepository.observeCars().collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteCar by carRepository.observeFavoriteCar().collectAsStateWithLifecycle(initialValue = null)
    val events by eventRepository.observeEvents().collectAsStateWithLifecycle(initialValue = emptyList())
    val trophyState by trophyRepository.observeState().collectAsStateWithLifecycle(initialValue = TrophyState())
    val scope = rememberCoroutineScope()

    var editingProfile by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))

        // Cloud account leads the screen — everything below (sharing, Friends) hinges on it,
        // so it's the first thing here rather than buried under Garage/Events/Trophies.
        // Hidden entirely when the build carries no Supabase credentials, so a cloud-less
        // build shows no dead entry point.
        if (cloudState != CloudSessionState.Disabled) {
            val signedIn = cloudState is CloudSessionState.SignedIn
            DashboardRow(
                // A different glyph, not just subtitle text, so "am I signed in" reads at a
                // glance rather than needing the subtitle line read closely.
                icon = if (signedIn) Icons.Filled.CloudDone else Icons.Filled.CloudQueue,
                iconTint = AccentProfile,
                title = stringResource(R.string.profile_cloud_account_title),
                subtitle = when (val state = cloudState) {
                    is CloudSessionState.SignedIn -> stringResource(R.string.profile_cloud_signed_in) + (state.account.email?.let { " · $it" } ?: "")
                    CloudSessionState.Loading -> stringResource(R.string.profile_cloud_checking)
                    else -> stringResource(R.string.profile_cloud_sign_in_prompt)
                },
                onClick = onOpenCloud,
            )
            // Friends only makes sense once signed in — same gating as Cloud account itself,
            // just one notch stricter (SignedOut/Loading still show Cloud account, since
            // that's how you get to sign-in; there's nothing for Friends to do until then).
            if (signedIn) {
                DashboardRow(
                    icon = Icons.Filled.People,
                    iconTint = AccentProfile,
                    title = stringResource(R.string.profile_friends_title),
                    subtitle = stringResource(R.string.profile_friends_subtitle),
                    onClick = onOpenFriends,
                )
            }
            Spacer(Modifier.height(20.dp))
            AccentDivider(AccentProfile)
        }

        // Garage is the main element of this screen: the favorite car gets a big photo hero,
        // everything else (personal info, events) is secondary below it.
        SectionLabel(stringResource(R.string.profile_garage_section_label), tint = AccentGarage)
        Spacer(Modifier.height(10.dp))
        when {
            favoriteCar != null -> {
                FavoriteCarHero(car = favoriteCar!!, onClick = { onOpenCar(favoriteCar!!.id) })
                Spacer(Modifier.height(12.dp))
            }
            cars.isNotEmpty() -> {
                Text(
                    stringResource(R.string.profile_pick_favorite_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            }
        }
        DashboardRow(
            icon = Icons.Filled.DirectionsCar,
            iconTint = AccentGarage,
            title = stringResource(R.string.profile_all_cars_title),
            subtitle = pluralStringResource(R.plurals.profile_cars_count, cars.size, cars.size),
            onClick = onOpenGarage,
        )

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.profile_edit_personal_info_content_description), tint = AccentProfile)
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                departments.forEach { dept ->
                    NeonPill(dept.label, AccentProfile)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        AccentDivider(AccentProfile)

        DashboardRow(
            icon = Icons.Filled.Event,
            iconTint = AccentEvents,
            title = stringResource(R.string.profile_events_title),
            subtitle = pluralStringResource(R.plurals.profile_events_count, events.size, events.size),
            onClick = onOpenEvents,
        )

        DashboardRow(
            icon = Icons.Filled.EmojiEvents,
            iconTint = AccentTrophy,
            title = stringResource(R.string.profile_trophies_title),
            subtitle = stringResource(R.string.profile_trophies_unlocked_format, trophyState.unlockedCount, trophyState.totalCount),
            onClick = onOpenTrophies,
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
