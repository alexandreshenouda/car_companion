package com.carlauncher.companion.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.carlauncher.companion.ui.theme.neonBorder
import com.carlauncher.companion.ui.theme.neonGlow
import com.carlauncher.companion.ui.theme.neonSweep

/**
 * Surfaces built on the [com.carlauncher.companion.ui.theme] neon primitives. These are
 * the arcade replacements for stock Material `Card` / `LinearProgressIndicator`: an
 * accent rim plus a top sweep bar, rather than elevation and a fill.
 */

/**
 * Panel with an accent rim, an optional glow, and a gradient bar across the top edge.
 * The bar is what makes a stack of these read as a HUD rather than a list of boxes.
 */
@Composable
fun NeonCard(
    accent: Color,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    glow: Boolean = true,
    topBar: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .then(if (glow) Modifier.neonGlow(accent, shape, elevation = 10.dp) else Modifier)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .neonBorder(accent, shape, alpha = 0.4f)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (topBar) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(neonSweep(accent)))
        }
        content()
    }
}

/** Flat capsule track with a glowing accent fill. Used by trophy progress and stats. */
@Composable
fun NeonProgressBar(
    progress: Float,
    accent: Color,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 8.dp,
) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), label = "neon-progress")
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (animated > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(animated)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(accent),
            )
        }
    }
}

/**
 * Small bordered capsule for counts and statuses ("12 TRIPS", "LOCKED"). Filled when
 * [selected], hollow otherwise — the same shape language as [NeonSegmentedSelector].
 */
@Composable
fun NeonPill(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    /** Taller padding for a primary full-width action button (sign in, sync now, sign out —
     * anything that's the main thing to tap on its screen) — the compact default reads fine
     * for tags and filter chips, but is a cramped touch target as a real button. */
    large: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .clip(CircleShape)
            .background(if (selected) accent else Color.Transparent)
            .neonBorder(accent, CircleShape, alpha = if (selected) 0f else 0.55f)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = if (large) 16.dp else 7.dp),
        // Centered rather than Start: harmless when the pill hugs its content (the common
        // case), but lets a fillMaxWidth() pill — e.g. a dialog's primary action — read
        // as a proper button instead of left-aligned text.
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else accent,
        )
    }
}

/**
 * Segmented control: one recessed track, glowing capsule on the active option. Shared by
 * every range/filter picker so History, Stats, Share and Trophies agree.
 */
@Composable
fun <T> NeonSegmentedSelector(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .then(if (isSelected) Modifier.background(accent) else Modifier)
                    .clickable { onSelect(option) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
