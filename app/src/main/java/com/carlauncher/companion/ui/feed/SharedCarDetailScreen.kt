package com.carlauncher.companion.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.carlauncher.companion.R
import com.carlauncher.companion.data.cloud.SharedContentRepository
import com.carlauncher.companion.data.cloud.dto.CarModificationRestoreRow
import com.carlauncher.companion.data.cloud.dto.CarRestoreRow
import com.carlauncher.companion.data.cloud.parseIsoToEpochMilli
import com.carlauncher.companion.ui.common.AccentDivider
import com.carlauncher.companion.ui.common.CarPhoto
import com.carlauncher.companion.ui.common.IconBadge
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.common.SectionLabel
import com.carlauncher.companion.ui.theme.AccentGarage
import com.carlauncher.companion.util.formatAbsolute
import kotlinx.coroutines.launch

/** A friend's shared car, read-only — no edit, no delete. */
@Composable
fun SharedCarDetailScreen(carId: String, sharedContentRepository: SharedContentRepository, modifier: Modifier = Modifier) {
    var result by remember(carId) { mutableStateOf<Pair<CarRestoreRow, List<CarModificationRestoreRow>>?>(null) }
    var photoBytes by remember(carId) { mutableStateOf<ByteArray?>(null) }
    var loading by remember(carId) { mutableStateOf(true) }
    var showReportDialog by remember(carId) { mutableStateOf(false) }
    var reportResult by remember(carId) { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(carId) {
        loading = true
        val loaded = sharedContentRepository.getSharedCar(carId)
        result = loaded
        loading = false
        loaded?.first?.photoUpdatedAt?.let { photoBytes = sharedContentRepository.getCarPhoto(carId, it) }
    }

    if (loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AccentGarage) }
        return
    }
    val (car, modifications) = result ?: run {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.shared_car_not_visible), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        if (car.photoUpdatedAt != null) {
            NeonCard(accent = AccentGarage, modifier = Modifier.fillMaxWidth(), topBar = false) {
                CarPhoto(bytes = photoBytes, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            }
            Spacer(Modifier.height(8.dp))
        }
        NeonCard(accent = AccentGarage, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.DirectionsCar, AccentGarage)
                    Spacer(Modifier.width(12.dp))
                    Text(car.name, style = MaterialTheme.typography.headlineSmall)
                }
                listOfNotNull(car.brand, car.model, car.year?.toString())
                    .joinToString(" ")
                    .takeIf { it.isNotBlank() }
                    ?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                car.details?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                car.odometerKm?.let {
                    Spacer(Modifier.height(14.dp))
                    SectionLabel(stringResource(R.string.car_detail_odometer_label), tint = AccentGarage)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.garage_odometer_km_format, it), style = MaterialTheme.typography.displaySmall, color = AccentGarage)
                }
            }
        }

        if (modifications.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.car_detail_modifications_label), tint = AccentGarage)
            modifications.forEach { mod ->
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Filled.Build, AccentGarage, size = 40.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(mod.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(mod.category, formatAbsolute(mod.installedAt.parseIsoToEpochMilli()), mod.cost?.let { stringResource(R.string.car_detail_modification_cost_format, it) })
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    AccentDivider(AccentGarage)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.shared_car_report_action),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.clickable { reportResult = null; showReportDialog = true },
        )
        reportResult?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(if (it) R.string.shared_car_report_success else R.string.shared_car_report_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showReportDialog) {
        var reason by remember(carId) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            title = { Text(stringResource(R.string.shared_car_report_dialog_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.shared_car_report_dialog_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text(stringResource(R.string.shared_car_report_reason_label)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showReportDialog = false
                    scope.launch { reportResult = sharedContentRepository.reportCar(carId, reason) }
                }) { Text(stringResource(R.string.shared_car_report_submit)) }
            },
            dismissButton = { TextButton(onClick = { showReportDialog = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
