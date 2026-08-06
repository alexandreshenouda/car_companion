package com.carlauncher.companion.car

// Detection zones, outermost first: level N lights up once distance drops to
// THRESHOLDS_METERS[N - 1] or closer. Not evenly spaced — bands narrow sharply near the radar,
// where a few meters matter a lot more than they do out at 1km.
private val THRESHOLDS_METERS = listOf(1000.0, 800.0, 600.0, 500.0, 400.0, 300.0, 200.0, 100.0, 50.0, 20.0)
private val LEVELS = THRESHOLDS_METERS.size

/** Maps a distance to a 0-10 proximity level: 0 beyond the outermost zone, 10 at 20m or closer. */
fun levelFor(distanceMeters: Double): Int = THRESHOLDS_METERS.count { distanceMeters <= it }

fun barText(level: Int): String = "■".repeat(level) + "□".repeat(LEVELS - level)
