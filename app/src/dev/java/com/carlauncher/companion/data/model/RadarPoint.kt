package com.carlauncher.companion.data.model

data class RadarPoint(
    val lat: Double,
    val lon: Double,
    val label: String,
    val type: RadarType,
    val country: String,
    val speedLimitKmh: Int?,
)
