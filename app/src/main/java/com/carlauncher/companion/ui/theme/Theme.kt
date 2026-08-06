package com.carlauncher.companion.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Chunky, pill-leaning radii — the arcade look lives as much in the silhouette as in the
 * colour. Anything smaller than ~14.dp starts reading as a business form again.
 */
private val CompanionShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

/**
 * Every slot is set explicitly. The container slots in particular used to fall back to
 * stock Material 3 (a lilac-ish purple), which is what tinted the bottom-nav selected
 * pill and every default [androidx.compose.material3.FilterChip].
 */
private val CompanionColorScheme = darkColorScheme(
    primary = NeonLime,
    onPrimary = Ink950,
    primaryContainer = Ink850,
    onPrimaryContainer = NeonLime,

    secondary = NeonCyan,
    onSecondary = Ink950,
    secondaryContainer = Ink800,
    onSecondaryContainer = NeonCyan,

    tertiary = NeonMagenta,
    onTertiary = Ink950,
    tertiaryContainer = Ink800,
    onTertiaryContainer = NeonMagenta,

    background = Ink950,
    onBackground = TextPrimary,
    surface = Ink900,
    onSurface = TextPrimary,
    surfaceVariant = Ink800,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = Ink950,
    surfaceContainerLow = Ink900,
    surfaceContainer = Ink850,
    surfaceContainerHigh = Ink800,
    surfaceContainerHighest = Ink700,
    surfaceTint = NeonLime,

    outline = Ink600,
    outlineVariant = Ink700,

    error = NeonRed,
    onError = Ink950,
    errorContainer = Ink800,
    onErrorContainer = NeonRed,

    scrim = Ink950,
)

/**
 * The app is intentionally dark-only (car console aesthetic) — there is no light theme
 * branch and no dynamic colour, because the neon palette is the app's identity.
 */
@Composable
fun CompanionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CompanionColorScheme,
        typography = CompanionTypography,
        shapes = CompanionShapes,
        content = content,
    )
}
