package com.carlauncher.companion.data.model

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Sports
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.carlauncher.companion.R
import com.carlauncher.companion.ui.theme.NeonAmber
import com.carlauncher.companion.ui.theme.NeonCyan
import com.carlauncher.companion.ui.theme.NeonMagenta
import com.carlauncher.companion.ui.theme.NeonRed

enum class EventType(@param:StringRes val labelRes: Int, val icon: ImageVector, val color: Color) {
    CAR_MEET(R.string.event_type_car_meet, Icons.Filled.Groups, NeonMagenta),
    RACETRACK(R.string.event_type_racetrack, Icons.Filled.Sports, NeonRed),
    EXPLORATION(R.string.event_type_exploration, Icons.Filled.Explore, NeonCyan),
    OTHER(R.string.event_type_other, Icons.Filled.Category, NeonAmber),
}
