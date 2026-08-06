package com.carlauncher.companion.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * No bundled font files — the arcade voice comes from weight, tracking and a monospace
 * numeral face that ships with every device. The three `display*` slots are the
 * instrument readouts (odometer, stat tiles, trophy counters); everything else is a
 * heavy grotesque with slightly tightened tracking so headings read as stamped rather
 * than typed.
 *
 * All 15 Material 3 slots are declared on purpose: [bodyMedium] alone has ~20 call sites
 * and used to inherit the stock Material scale, which no longer matches anything here.
 */
private val Hud = FontFamily.Monospace

val CompanionTypography = Typography(
    // ---- Instrument readouts: monospace so digits don't jitter as values tick ----
    displayLarge = TextStyle(fontFamily = Hud, fontWeight = FontWeight.Black, fontSize = 52.sp, letterSpacing = (-1.5).sp),
    displayMedium = TextStyle(fontFamily = Hud, fontWeight = FontWeight.Black, fontSize = 42.sp, letterSpacing = (-1.2).sp),
    displaySmall = TextStyle(fontFamily = Hud, fontWeight = FontWeight.Black, fontSize = 34.sp, letterSpacing = (-1).sp),

    // ---- Headings ----
    headlineLarge = TextStyle(fontWeight = FontWeight.Black, fontSize = 32.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 27.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 23.sp, letterSpacing = (-0.3).sp),

    titleLarge = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp),

    // ---- Body ----
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, letterSpacing = 0.1.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, letterSpacing = 0.2.sp),

    // ---- Labels ----
    labelLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.6.sp),
    // Uppercase console section labels ("GARAGE", "TROPHIES"). The wide tracking is what
    // makes them read as instrumented rather than as a plain settings form.
    labelSmall = TextStyle(fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 2.0.sp),
)
