package com.carlauncher.companion.ui.trophies

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.carlauncher.companion.R
import com.carlauncher.companion.data.model.Trophy
import com.carlauncher.companion.data.model.color
import com.carlauncher.companion.data.model.icon
import com.carlauncher.companion.data.model.labelRes
import com.carlauncher.companion.data.model.titleRes
import com.carlauncher.companion.ui.common.IconBadge
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.common.SectionLabel
import com.carlauncher.companion.ui.theme.neonBorder

/**
 * Fired the moment the user opens the app (or resumes it) with trophies earned since the
 * last time this popup was shown — including ones unlocked in the background by
 * [com.carlauncher.companion.car.RadarAlertService] or
 * [com.carlauncher.companion.car.LocalTrackingService] while the app was closed.
 *
 * A plain [Dialog] rather than [androidx.compose.material3.AlertDialog]: the neon-medal
 * layout doesn't fit Material's title/text/buttons slots cleanly.
 */
@Composable
fun TrophyCelebrationDialog(trophies: List<Trophy>, onDismiss: () -> Unit) {
    if (trophies.isEmpty()) return
    val accent = MaterialTheme.colorScheme.primary

    Dialog(onDismissRequest = onDismiss) {
        NeonCard(accent = accent, topBar = false) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                IconBadge(trophies.first().icon, accent, size = 64.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    pluralStringResource(R.plurals.trophy_celebration_title, trophies.size, trophies.size),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))

                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(trophies, key = { it.name }) { trophy ->
                        CelebrationRow(trophy)
                    }
                }

                Spacer(Modifier.height(20.dp))
                NeonPill(
                    text = stringResource(R.string.trophy_celebration_dismiss),
                    accent = accent,
                    selected = true,
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CelebrationRow(trophy: Trophy) {
    val tierColor = trophy.tier.color
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier
                .size(40.dp)
                .clip(shape)
                .background(tierColor.copy(alpha = 0.16f))
                .neonBorder(tierColor, shape, alpha = 0.6f),
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                trophy.icon,
                contentDescription = null,
                tint = tierColor,
                modifier = Modifier.padding(8.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(trophy.titleRes), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            SectionLabel(stringResource(trophy.tier.labelRes), tint = tierColor)
        }
    }
}
