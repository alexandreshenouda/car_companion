package com.carlauncher.companion.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The three primitives the neon look is built from. Deliberately plain — no blur, no
 * render effects, no extra dependency — so they cost nothing on older phones and work
 * identically inside the [android.view.PixelCopy] capture used by the share cards.
 */

/** Hairline rim in [tint]. Reads as the edge-light on the accent, not as a border box. */
fun Modifier.neonBorder(
    tint: Color,
    shape: Shape = RoundedCornerShape(20.dp),
    width: Dp = 1.dp,
    alpha: Float = 0.55f,
): Modifier = border(width, tint.copy(alpha = alpha), shape)

/**
 * Coloured drop shadow standing in for a glow. `ambientColor`/`spotColor` are honoured
 * from API 28; on 26–27 this degrades to a neutral shadow, which is an acceptable
 * fallback rather than a broken one.
 */
fun Modifier.neonGlow(
    tint: Color,
    shape: Shape = RoundedCornerShape(20.dp),
    elevation: Dp = 8.dp,
): Modifier = shadow(elevation, shape, clip = false, ambientColor = tint, spotColor = tint)

/** Accent gradient that fades to nothing — used for tile top-bars and row dividers. */
fun neonSweep(tint: Color, startAlpha: Float = 0.9f): Brush =
    Brush.horizontalGradient(listOf(tint.copy(alpha = startAlpha), tint.copy(alpha = 0.05f)))

/** Vertical variant, for the left edge of cards and the trophy medal wells. */
fun neonFade(tint: Color, startAlpha: Float = 0.28f): Brush =
    Brush.verticalGradient(listOf(tint.copy(alpha = startAlpha), Color.Transparent))
