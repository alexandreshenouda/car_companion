package com.carlauncher.companion.data.model

import androidx.annotation.StringRes
import com.carlauncher.companion.R

/** Drives what the share-card preview (see `ui/share/ShareScreen.kt`) renders. */
enum class ShareTemplate(
    @param:StringRes val labelRes: Int,
    val showMap: Boolean,
    val showZoneColors: Boolean,
    val showLegend: Boolean,
    val showExtendedStats: Boolean,
) {
    MINIMAL(R.string.share_template_minimal, showMap = true, showZoneColors = false, showLegend = false, showExtendedStats = false),
    SPEED_ZONES(R.string.share_template_speed_zones, showMap = true, showZoneColors = true, showLegend = true, showExtendedStats = false),
    DETAILED(R.string.share_template_detailed, showMap = true, showZoneColors = true, showLegend = true, showExtendedStats = true),
    STATS_ONLY(R.string.share_template_stats_only, showMap = false, showZoneColors = false, showLegend = false, showExtendedStats = true),
}
