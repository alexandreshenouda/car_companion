package com.carlauncher.companion.data.model

/**
 * Shared speed-bucket definition used for both the map history trail color and stats
 * breakdown.
 *
 * Colors are raw ARGB `Int` rather than `Compose.Color` because the same value feeds an
 * osmdroid `Polyline` and a Compose swatch. They track the neon palette in
 * `ui/theme/Color.kt` — cool at the bottom of the scale, hot at the top — but must stay
 * declared here so the data layer doesn't depend on the UI layer.
 */
enum class SpeedZone(val label: String, val range: IntRange, val color: Int) {
    ZONE_0_50("0-50", 0..49, 0xFF22E5FF.toInt()),
    ZONE_50_100("50-100", 50..99, 0xFF3DFFC9.toInt()),
    ZONE_100_130("100-130", 100..129, 0xFFC6FF3D.toInt()),
    ZONE_130_150("130-150", 130..149, 0xFFFFC93D.toInt()),
    ZONE_150_200("150-200", 150..199, 0xFFFF7A3D.toInt()),
    ZONE_200_PLUS("200+", 200..Int.MAX_VALUE, 0xFFFF3DA5.toInt()),
    ;

    companion object {
        fun forSpeed(speedKmh: Int): SpeedZone = entries.first { speedKmh in it.range }
    }
}
