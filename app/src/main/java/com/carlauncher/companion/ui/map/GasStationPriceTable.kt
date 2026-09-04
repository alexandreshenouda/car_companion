package com.carlauncher.companion.ui.map

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlauncher.companion.R
import com.carlauncher.companion.data.model.FuelType
import com.carlauncher.companion.data.model.GasStation
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.theme.NeonAmber

/**
 * Top-left widget displaying the top 5 cheapest gas stations in the current viewport.
 * Order is determined by ascending price of [selectedFuel] (or Gazole if [selectedFuel] is null).
 * Tapping the expanded card closes it and turns it into a small floating action button.
 */
@Composable
fun GasStationPriceTable(
    stations: List<GasStation>,
    selectedFuel: FuelType?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onStationClick: ((GasStation) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val targetFuel = selectedFuel ?: FuelType.GAZOLE
    val top5Stations = remember(stations, targetFuel) {
        stations
            .filter { it.prices.containsKey(targetFuel) }
            .sortedBy { it.prices[targetFuel] }
            .take(5)
    }

    AnimatedContent(
        targetState = expanded,
        modifier = modifier,
        contentAlignment = Alignment.TopStart,
        label = "gas_station_price_table",
    ) { isExpanded ->
        if (isExpanded) {
            NeonCard(
                accent = NeonAmber,
                glow = false,
                topBar = false,
                shape = RoundedCornerShape(14.dp),
                onClick = onToggleExpanded,
                modifier = Modifier.widthIn(min = 180.dp, max = 220.dp),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Filled.LocalGasStation,
                            contentDescription = null,
                            tint = NeonAmber,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.gas_station_top_prices_title, targetFuel.canonicalName),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = NeonAmber,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    HorizontalDivider(color = NeonAmber.copy(alpha = 0.3f), thickness = 0.5.dp)

                    if (top5Stations.isEmpty()) {
                        Text(
                            text = stringResource(R.string.gas_station_no_stations_in_view),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    } else {
                        top5Stations.forEachIndexed { index, station ->
                            val formattedPrice = station.formattedPrice(targetFuel) ?: "-"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = onStationClick != null) {
                                        onStationClick?.invoke(station)
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${index + 1}. ${station.shortName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val subtitleText = when {
                                        station.city.isNotBlank() -> station.city
                                        station.isCluster -> when (station.fiability) {
                                            "CONFIDENT" -> "Prix récents"
                                            "FEW_RECENT_PRICES" -> "Peu de prix récents"
                                            "OLD_LAST_UPDATE" -> "Prix anciens"
                                            "OUTDATED_LAST_PRICE_UPDATE" -> "Prix obsolètes"
                                            else -> "Suisse"
                                        }
                                        station.source == com.carlauncher.companion.data.model.GasStationSource.SWITZERLAND ->
                                            station.displayName ?: station.formattedAddress ?: "Suisse"
                                        else -> ""
                                    }
                                    if (subtitleText.isNotBlank()) {
                                        Text(
                                            text = subtitleText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }

                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = formattedPrice,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonAmber,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            SmallFloatingActionButton(
                onClick = onToggleExpanded,
                containerColor = NeonAmber,
            ) {
                Icon(
                    Icons.Filled.LocalGasStation,
                    contentDescription = stringResource(R.string.gas_station_table_content_description),
                    tint = Color.Black,
                )
            }
        }
    }
}
