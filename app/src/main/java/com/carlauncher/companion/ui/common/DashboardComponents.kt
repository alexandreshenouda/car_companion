package com.carlauncher.companion.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlauncher.companion.ui.theme.neonBorder
import com.carlauncher.companion.ui.theme.neonSweep

/**
 * Shared "console" visual language for Profile/Garage/Events/Trophies: glowing squircle
 * icon badges, heavy titles, trailing chevron, and an accent-tinted sweep divider
 * instead of Material's flat Card list — the list reads as an instrumented car menu
 * rather than a generic settings form.
 */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 46.dp) {
    val shape = RoundedCornerShape(size * 0.32f)
    Box(
        modifier
            .size(size)
            .background(tint.copy(alpha = 0.14f), shape)
            .neonBorder(tint, shape, alpha = 0.45f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Hairline that fades out to the right — the app's signature separator. */
@Composable
fun AccentDivider(tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(neonSweep(tint, startAlpha = 0.7f)),
    )
}

/** Uppercase, wide-tracked section label ("GARAGE", "TROPHIES"). */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
    )
}

@Composable
fun DashboardRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    trailingText: String? = null,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    DashboardRow(
        leading = { IconBadge(icon, iconTint) },
        dividerTint = iconTint,
        title = title,
        subtitle = subtitle,
        trailingText = trailingText,
        showChevron = showChevron,
        onClick = onClick,
    )
}

/** Slot variant, for rows whose leading element is a photo or a trophy medal. */
@Composable
fun DashboardRow(
    leading: @Composable () -> Unit,
    dividerTint: Color,
    title: String,
    subtitle: String? = null,
    trailingText: String? = null,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            trailingText?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = dividerTint)
                Spacer(Modifier.width(6.dp))
            }
            if (showChevron) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AccentDivider(dividerTint)
    }
}

@Composable
fun DashboardHeader(icon: ImageVector, tint: Color, title: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, tint)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}
