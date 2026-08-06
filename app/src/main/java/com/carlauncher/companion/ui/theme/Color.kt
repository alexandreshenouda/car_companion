package com.carlauncher.companion.ui.theme

import androidx.compose.ui.graphics.Color

// Near-black "track at night" surfaces — cold cast so the neon accents read as emitted
// light rather than paint. Ramp is what gives depth; there is almost no elevation.
val Ink950 = Color(0xFF06080C)
val Ink900 = Color(0xFF0D1016)
val Ink850 = Color(0xFF131820)
val Ink800 = Color(0xFF1A202B)
val Ink700 = Color(0xFF262E3C)
val Ink600 = Color(0xFF3A4557)

// Neon accents. Deliberately few and very saturated: on Ink950 they behave like a
// racing-game HUD, and mixing more than three in one screen turns to mush.
val NeonLime = Color(0xFFC6FF3D)
val NeonCyan = Color(0xFF22E5FF)
val NeonMagenta = Color(0xFFFF3DA5)
val NeonAmber = Color(0xFFFFC93D)
val NeonRed = Color(0xFFFF4D5E)

val TextPrimary = Color(0xFFEDF3FF)
val TextSecondary = Color(0xFF8794AB)
val TextDim = Color(0xFF5C687C)

// Semantic section accents. Screens import these, never the raw Neon* tokens, so the
// palette can be re-tuned in one place without touching a dozen files.
val AccentGarage = NeonLime
val AccentProfile = NeonCyan
val AccentEvents = NeonMagenta
val AccentTrophy = NeonAmber
val AccentAlert = NeonRed
