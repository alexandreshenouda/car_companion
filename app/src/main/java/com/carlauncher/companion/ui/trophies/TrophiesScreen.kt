package com.carlauncher.companion.ui.trophies

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlauncher.companion.R
import com.carlauncher.companion.data.model.TROPHY_LOCKED_COLOR
import com.carlauncher.companion.data.model.Trophy
import com.carlauncher.companion.data.model.TrophyCategory
import com.carlauncher.companion.data.model.TrophyStats
import com.carlauncher.companion.data.model.accent
import com.carlauncher.companion.data.model.color
import com.carlauncher.companion.data.model.descriptionRes
import com.carlauncher.companion.data.model.icon
import com.carlauncher.companion.data.model.labelRes
import com.carlauncher.companion.data.model.titleRes
import com.carlauncher.companion.data.repo.TrophyRepository
import com.carlauncher.companion.data.repo.TrophyState
import com.carlauncher.companion.ui.common.AccentDivider
import com.carlauncher.companion.ui.common.IconBadge
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.common.NeonProgressBar
import com.carlauncher.companion.ui.common.SectionLabel
import com.carlauncher.companion.ui.theme.neonBorder
import com.carlauncher.companion.ui.theme.neonGlow
import com.carlauncher.companion.util.formatAbsolute

private const val COLUMNS = 3

@Composable
fun TrophiesScreen(trophyRepository: TrophyRepository) {
    val state by trophyRepository.observeState().collectAsStateWithLifecycle(initialValue = TrophyState())
    var detail by remember { mutableStateOf<Trophy?>(null) }

    // Opening the screen is the one moment the user is definitely looking at trophies, so
    // never show them a stale snapshot. Anything unlocked right here is also marked seen
    // immediately — the medal grid lighting up in front of them already is the
    // celebration, so the app-open popup shouldn't repeat it on next launch.
    LaunchedEffect(Unit) {
        val (newlyUnlocked, _) = trophyRepository.refresh()
        trophyRepository.acknowledgeCelebration(newlyUnlocked)
    }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    ) {
        item(key = "header") {
            TrophyHeader(state)
            Spacer(Modifier.height(24.dp))
        }

        TrophyCategory.entries.forEach { category ->
            val trophies = Trophy.entries.filter { it.category == category }

            item(key = "head-${category.name}") {
                val unlockedInCategory = trophies.count { it in state.unlockedAt }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(category.icon, category.accent, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    SectionLabel(stringResource(category.labelRes), tint = category.accent, modifier = Modifier.weight(1f))
                    Text(
                        stringResource(R.string.trophies_category_progress_format, unlockedInCategory, trophies.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = category.accent,
                    )
                }
                Spacer(Modifier.height(8.dp))
                AccentDivider(category.accent)
                Spacer(Modifier.height(12.dp))
            }

            // Rows of three, built by hand rather than with LazyVerticalGrid so the whole
            // page stays a single scrolling list.
            trophies.chunked(COLUMNS).forEachIndexed { index, rowTrophies ->
                item(key = "row-${category.name}-$index") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowTrophies.forEach { trophy ->
                            TrophyMedal(
                                trophy = trophy,
                                stats = state.stats,
                                unlockedAt = state.unlockedAt[trophy],
                                modifier = Modifier.weight(1f),
                                onClick = { detail = trophy },
                            )
                        }
                        repeat(COLUMNS - rowTrophies.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }

            item(key = "gap-${category.name}") { Spacer(Modifier.height(18.dp)) }
        }
    }

    detail?.let { trophy ->
        TrophyDetailSheet(
            trophy = trophy,
            stats = state.stats,
            unlockedAt = state.unlockedAt[trophy],
            onDismiss = { detail = null },
        )
    }
}

@Composable
private fun TrophyHeader(state: TrophyState) {
    val accent = MaterialTheme.colorScheme.primary
    NeonCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(
                progress = if (state.totalCount == 0) 0f else state.unlockedCount.toFloat() / state.totalCount,
                accent = accent,
            )
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                SectionLabel(stringResource(R.string.trophies_unlocked_label), tint = accent)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.trophies_header_progress_format, state.unlockedCount, state.totalCount),
                    style = MaterialTheme.typography.displaySmall,
                    color = accent,
                )
                Spacer(Modifier.height(8.dp))
                val km = state.stats.totalDistanceKm
                Text(
                    stringResource(R.string.trophies_summary_format, km, state.stats.tripCount, state.stats.distinctDrivingDays),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.stats.currentStreakDays > 1) {
                    Spacer(Modifier.height(8.dp))
                    NeonPill(
                        pluralStringResource(R.plurals.trophies_streak, state.stats.currentStreakDays, state.stats.currentStreakDays),
                        MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressRing(progress: Float, accent: Color, size: Dp = 76.dp) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(Modifier.size(size)) {
        val stroke = 9.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(
            color = track,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        if (progress > 0f) {
            drawArc(
                color = accent,
                // Start at 12 o'clock and run clockwise, like a rev counter sweeping up.
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun TrophyMedal(
    trophy: Trophy,
    stats: TrophyStats,
    unlockedAt: Long?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val unlocked = unlockedAt != null
    val accent = if (unlocked) trophy.tier.color else TROPHY_LOCKED_COLOR
    val shape = RoundedCornerShape(18.dp)

    Column(
        modifier
            .then(if (unlocked) Modifier.neonGlow(accent, shape, elevation = 10.dp) else Modifier)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .neonBorder(accent, shape, alpha = if (unlocked) 0.6f else 0.2f)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .background(accent.copy(alpha = if (unlocked) 0.18f else 0.07f), CircleShape)
                .neonBorder(accent, CircleShape, alpha = if (unlocked) 0.7f else 0.25f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                trophy.icon,
                contentDescription = null,
                tint = accent.copy(alpha = if (unlocked) 1f else 0.55f),
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(trophy.titleRes),
            style = MaterialTheme.typography.labelMedium,
            color = if (unlocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        if (unlocked) {
            SectionLabel(stringResource(trophy.tier.labelRes), tint = accent)
        } else {
            // Locked tiles show how close you are — a plain grey badge gives no reason
            // to keep driving.
            NeonProgressBar(trophy.progress(stats), accent, height = 4.dp)
            Spacer(Modifier.height(6.dp))
            val progress = trophy.progressLabel(stats)
            Text(
                stringResource(R.string.trophy_progress_format, progress.current, progress.target, stringResource(progress.unit.labelRes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrophyDetailSheet(trophy: Trophy, stats: TrophyStats, unlockedAt: Long?, onDismiss: () -> Unit) {
    val unlocked = unlockedAt != null
    val accent = if (unlocked) trophy.tier.color else TROPHY_LOCKED_COLOR

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(trophy.icon, accent, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(trophy.titleRes), style = MaterialTheme.typography.headlineSmall)
                    SectionLabel(
                        stringResource(R.string.trophies_tier_category_format, stringResource(trophy.tier.labelRes), stringResource(trophy.category.labelRes)),
                        tint = accent,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(trophy.descriptionRes), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(20.dp))

            NeonProgressBar(trophy.progress(stats), accent)
            Spacer(Modifier.height(8.dp))
            val progress = trophy.progressLabel(stats)
            Text(
                stringResource(R.string.trophy_progress_format, progress.current, progress.target, stringResource(progress.unit.labelRes)),
                style = MaterialTheme.typography.labelLarge,
                color = accent,
            )
            if (unlockedAt != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.trophies_unlocked_at_format, formatAbsolute(unlockedAt)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Compact strip of the trophies a single car's own history would have earned. Derived on
 * the fly, never persisted — the global set in `trophy_unlocks` stays the source of truth.
 */
@Composable
fun CarTrophyStrip(stats: TrophyStats, accent: Color, modifier: Modifier = Modifier) {
    val earned = Trophy.entries.filter { it.category != TrophyCategory.COLLECTION && it.isUnlocked(stats) }
    if (earned.isEmpty()) return

    Column(modifier.fillMaxWidth()) {
        SectionLabel(stringResource(R.string.trophies_earned_by_car_label), tint = accent)
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(earned, key = { it.name }) { trophy ->
                Column(
                    Modifier.width(72.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .aspectRatio(1f)
                            .background(trophy.tier.color.copy(alpha = 0.16f), CircleShape)
                            .neonBorder(trophy.tier.color, CircleShape, alpha = 0.6f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(trophy.icon, null, tint = trophy.tier.color, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(trophy.titleRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
