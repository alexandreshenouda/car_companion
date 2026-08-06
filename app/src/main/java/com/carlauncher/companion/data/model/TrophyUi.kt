package com.carlauncher.companion.data.model

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Grid4x4
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.carlauncher.companion.R
import com.carlauncher.companion.ui.theme.NeonAmber
import com.carlauncher.companion.ui.theme.NeonCyan
import com.carlauncher.companion.ui.theme.NeonLime
import com.carlauncher.companion.ui.theme.NeonMagenta
import com.carlauncher.companion.ui.theme.TextSecondary

/**
 * Android/Compose decoration for the [Trophy]/[TrophyTier]/[TrophyCategory]/[TrophyUnit]
 * catalogue, which itself lives in `:shared` (pure data, reused by iOS) — see the doc comment
 * on [TrophyTier] there for why this split exists.
 */

/** Colour for a medal that hasn't been earned yet. */
val TROPHY_LOCKED_COLOR: Color = TextSecondary

@get:StringRes
val TrophyTier.labelRes: Int
    get() = when (this) {
        TrophyTier.BRONZE -> R.string.trophy_tier_bronze
        TrophyTier.SILVER -> R.string.trophy_tier_silver
        TrophyTier.GOLD -> R.string.trophy_tier_gold
        TrophyTier.PLATINUM -> R.string.trophy_tier_platinum
    }

val TrophyTier.color: Color
    get() = when (this) {
        TrophyTier.BRONZE -> Color(0xFFD08A4E)
        TrophyTier.SILVER -> Color(0xFFC3D0E3)
        TrophyTier.GOLD -> NeonAmber
        TrophyTier.PLATINUM -> NeonCyan
    }

@get:StringRes
val TrophyCategory.labelRes: Int
    get() = when (this) {
        TrophyCategory.DISTANCE -> R.string.trophy_category_distance
        TrophyCategory.HABIT -> R.string.trophy_category_habit
        TrophyCategory.EXPLORATION -> R.string.trophy_category_exploration
        TrophyCategory.COLLECTION -> R.string.trophy_category_collection
    }

val TrophyCategory.icon: ImageVector
    get() = when (this) {
        TrophyCategory.DISTANCE -> Icons.Filled.Speed
        TrophyCategory.HABIT -> Icons.Filled.Repeat
        TrophyCategory.EXPLORATION -> Icons.Filled.Explore
        TrophyCategory.COLLECTION -> Icons.Filled.DirectionsCar
    }

val TrophyCategory.accent: Color
    get() = when (this) {
        TrophyCategory.DISTANCE -> NeonLime
        TrophyCategory.HABIT -> NeonCyan
        TrophyCategory.EXPLORATION -> NeonMagenta
        TrophyCategory.COLLECTION -> NeonAmber
    }

@get:StringRes
val TrophyUnit.labelRes: Int
    get() = when (this) {
        TrophyUnit.TRIP -> R.string.trophy_unit_trip
        TrophyUnit.TRIPS -> R.string.trophy_unit_trips
        TrophyUnit.KM -> R.string.trophy_unit_km
        TrophyUnit.KMH -> R.string.trophy_unit_kmh
        TrophyUnit.DAYS -> R.string.trophy_unit_days
        TrophyUnit.SEASONS -> R.string.trophy_unit_seasons
        TrophyUnit.HOURS -> R.string.trophy_unit_hours
        TrophyUnit.DEPARTEMENTS -> R.string.trophy_unit_departements
        TrophyUnit.SQUARES -> R.string.trophy_unit_squares
        TrophyUnit.CAR -> R.string.trophy_unit_car
        TrophyUnit.CARS -> R.string.trophy_unit_cars
        TrophyUnit.MODS -> R.string.trophy_unit_mods
        TrophyUnit.EVENTS -> R.string.trophy_unit_events
        TrophyUnit.IMPORT -> R.string.trophy_unit_import
        TrophyUnit.FIELDS -> R.string.trophy_unit_fields
    }

private data class TrophySpec(@param:StringRes val titleRes: Int, @param:StringRes val descriptionRes: Int, val icon: ImageVector)

private val TROPHY_SPECS: Map<Trophy, TrophySpec> = mapOf(
    // ---- Distance & speed ----
    Trophy.FIRST_LIGHT to TrophySpec(R.string.trophy_first_light_title, R.string.trophy_first_light_desc, Icons.Filled.Route),
    Trophy.CENTURY to TrophySpec(R.string.trophy_century_title, R.string.trophy_century_desc, Icons.Filled.Straighten),
    Trophy.ROAD_WARRIOR to TrophySpec(R.string.trophy_road_warrior_title, R.string.trophy_road_warrior_desc, Icons.Filled.Straighten),
    Trophy.CONTINENTAL to TrophySpec(R.string.trophy_continental_title, R.string.trophy_continental_desc, Icons.Filled.Public),
    Trophy.LONG_HAUL to TrophySpec(R.string.trophy_long_haul_title, R.string.trophy_long_haul_desc, Icons.Filled.Route),
    Trophy.TON_UP to TrophySpec(R.string.trophy_ton_up_title, R.string.trophy_ton_up_desc, Icons.Filled.Speed),
    Trophy.REDLINE to TrophySpec(R.string.trophy_redline_title, R.string.trophy_redline_desc, Icons.Filled.Whatshot),
    Trophy.AUTOBAHN to TrophySpec(R.string.trophy_autobahn_title, R.string.trophy_autobahn_desc, Icons.Filled.Bolt),
    Trophy.TERMINAL_VELOCITY to TrophySpec(R.string.trophy_terminal_velocity_title, R.string.trophy_terminal_velocity_desc, Icons.Filled.RocketLaunch),

    // ---- Habits & streaks ----
    Trophy.COMMUTER to TrophySpec(R.string.trophy_commuter_title, R.string.trophy_commuter_desc, Icons.Filled.Repeat),
    Trophy.REGULAR to TrophySpec(R.string.trophy_regular_title, R.string.trophy_regular_desc, Icons.Filled.Repeat),
    Trophy.THREE_IN_A_ROW to TrophySpec(R.string.trophy_three_in_a_row_title, R.string.trophy_three_in_a_row_desc, Icons.Filled.LocalFireDepartment),
    Trophy.WEEK_STREAK to TrophySpec(R.string.trophy_week_streak_title, R.string.trophy_week_streak_desc, Icons.Filled.LocalFireDepartment),
    Trophy.MONTH_STREAK to TrophySpec(R.string.trophy_month_streak_title, R.string.trophy_month_streak_desc, Icons.Filled.LocalFireDepartment),
    Trophy.NIGHT_OWL to TrophySpec(R.string.trophy_night_owl_title, R.string.trophy_night_owl_desc, Icons.Filled.DarkMode),
    Trophy.EARLY_BIRD to TrophySpec(R.string.trophy_early_bird_title, R.string.trophy_early_bird_desc, Icons.Filled.WbTwilight),
    Trophy.FOUR_SEASONS to TrophySpec(R.string.trophy_four_seasons_title, R.string.trophy_four_seasons_desc, Icons.Filled.WbSunny),
    Trophy.ENDURANCE to TrophySpec(R.string.trophy_endurance_title, R.string.trophy_endurance_desc, Icons.Filled.AccessTime),

    // ---- Exploration ----
    Trophy.TOURIST to TrophySpec(R.string.trophy_tourist_title, R.string.trophy_tourist_desc, Icons.Filled.Map),
    Trophy.EXPLORER to TrophySpec(R.string.trophy_explorer_title, R.string.trophy_explorer_desc, Icons.Filled.Explore),
    Trophy.CARTOGRAPHER to TrophySpec(R.string.trophy_cartographer_title, R.string.trophy_cartographer_desc, Icons.Filled.Terrain),
    Trophy.FAR_FROM_HOME to TrophySpec(R.string.trophy_far_from_home_title, R.string.trophy_far_from_home_desc, Icons.Filled.Public),
    Trophy.GRID_RUNNER to TrophySpec(R.string.trophy_grid_runner_title, R.string.trophy_grid_runner_desc, Icons.Filled.Grid4x4),

    // ---- Collection ----
    Trophy.KEYS_IN_HAND to TrophySpec(R.string.trophy_keys_in_hand_title, R.string.trophy_keys_in_hand_desc, Icons.Filled.DirectionsCar),
    Trophy.COLLECTOR to TrophySpec(R.string.trophy_collector_title, R.string.trophy_collector_desc, Icons.Filled.DirectionsCar),
    Trophy.TUNER to TrophySpec(R.string.trophy_tuner_title, R.string.trophy_tuner_desc, Icons.Filled.Build),
    Trophy.EVENT_HORIZON to TrophySpec(R.string.trophy_event_horizon_title, R.string.trophy_event_horizon_desc, Icons.Filled.Event),
    Trophy.ARCHIVIST to TrophySpec(R.string.trophy_archivist_title, R.string.trophy_archivist_desc, Icons.Filled.FolderZip),
    Trophy.FULL_PROFILE to TrophySpec(R.string.trophy_full_profile_title, R.string.trophy_full_profile_desc, Icons.Filled.Person),
)

@get:StringRes
val Trophy.titleRes: Int get() = TROPHY_SPECS.getValue(this).titleRes

@get:StringRes
val Trophy.descriptionRes: Int get() = TROPHY_SPECS.getValue(this).descriptionRes

val Trophy.icon: ImageVector get() = TROPHY_SPECS.getValue(this).icon
