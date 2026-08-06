package com.carlauncher.companion.data.model

import androidx.annotation.StringRes
import com.carlauncher.companion.R

enum class RadarType(@param:StringRes val labelRes: Int) {
    FIXED(R.string.radar_type_fixed),
    RED_LIGHT(R.string.radar_type_red_light),
    SECTION_CONTROL(R.string.radar_type_section_control),
    TUNNEL(R.string.radar_type_tunnel),
    CONSTRUCTION(R.string.radar_type_construction),
    TRUCK(R.string.radar_type_truck),
    CARPOOL(R.string.radar_type_carpool),
    LEVEL_CROSSING(R.string.radar_type_level_crossing),
    ;

    companion object {
        fun fromLabel(label: String): RadarType = when {
            label.contains("Feu Rouge", ignoreCase = true) -> RED_LIGHT
            label.contains("Troncon", ignoreCase = true) -> SECTION_CONTROL
            label.contains("Tunnel", ignoreCase = true) -> TUNNEL
            label.contains("Chantier", ignoreCase = true) -> CONSTRUCTION
            label.contains("Poid lourd", ignoreCase = true) -> TRUCK
            label.contains("Covoiturage", ignoreCase = true) -> CARPOOL
            label.contains("Passage Niveau", ignoreCase = true) -> LEVEL_CROSSING
            else -> FIXED
        }
    }
}
