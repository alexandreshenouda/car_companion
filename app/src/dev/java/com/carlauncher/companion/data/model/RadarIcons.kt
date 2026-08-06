package com.carlauncher.companion.data.model

import com.carlauncher.companion.R

/** Single source of truth for which drawable represents each [RadarType] — shared by the phone map and the Android Auto module. */
val RADAR_TYPE_ICONS = mapOf(
    RadarType.FIXED to R.drawable.ic_radar_fixed,
    RadarType.RED_LIGHT to R.drawable.ic_radar_red_light,
    RadarType.SECTION_CONTROL to R.drawable.ic_radar_section,
    RadarType.TUNNEL to R.drawable.ic_radar_tunnel,
    RadarType.CONSTRUCTION to R.drawable.ic_radar_construction,
    RadarType.TRUCK to R.drawable.ic_radar_truck,
    RadarType.CARPOOL to R.drawable.ic_radar_carpool,
    RadarType.LEVEL_CROSSING to R.drawable.ic_radar_crossing,
)
