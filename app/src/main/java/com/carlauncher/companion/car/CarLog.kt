package com.carlauncher.companion.car

/**
 * Shared logcat tag for everything in this package that records or reacts to driving —
 * [LocalTrackingService] here in `src/main`, plus the radar alert engine/service and the
 * Bluetooth trigger over in the dev flavor. Kept as one tag (and kept under this name, which
 * predates local tracking) so a single `adb logcat -s RadarAlerts` still shows a whole trip.
 */
internal const val RADAR_LOG_TAG = "RadarAlerts"
