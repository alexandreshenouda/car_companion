package com.carlauncher.companion.data.model

/** Label string resource lives as an extension property in `:app` (`HistoryRangeUi.kt`) since
 * Android string resources aren't reachable from this module. */
enum class HistoryRange { TODAY, LAST_7_DAYS, LAST_30_DAYS, ALL }
