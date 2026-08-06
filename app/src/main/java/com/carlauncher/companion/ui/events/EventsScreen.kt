package com.carlauncher.companion.ui.events

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlauncher.companion.R
import com.carlauncher.companion.data.db.CarEntity
import com.carlauncher.companion.data.db.EventEntity
import com.carlauncher.companion.data.model.EventType
import com.carlauncher.companion.data.repo.CarRepository
import com.carlauncher.companion.data.repo.EventRepository
import com.carlauncher.companion.ui.common.DashboardRow
import com.carlauncher.companion.ui.common.IconBadge
import com.carlauncher.companion.ui.nav.Destination
import com.carlauncher.companion.ui.theme.AccentEvents
import com.carlauncher.companion.util.dayLabel

@Composable
fun EventsScreen(
    eventRepository: EventRepository,
    carRepository: CarRepository,
    onEventSelected: (String) -> Unit,
) {
    val events by eventRepository.observeEvents().collectAsStateWithLifecycle(initialValue = emptyList())
    val cars by carRepository.observeCars().collectAsStateWithLifecycle(initialValue = emptyList())
    val carsById = cars.associateBy { it.id }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onEventSelected(Destination.EventDetail.NEW_ID) },
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.events_add_event_content_description))
            }
        },
    ) { padding ->
        if (events.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconBadge(Icons.Filled.Event, AccentEvents, size = 72.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.events_empty_state), style = MaterialTheme.typography.titleLarge)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().padding(padding).padding(horizontal = 20.dp)) {
                items(events, key = { it.id }) { event ->
                    EventRow(event = event, car = event.carId?.let { carsById[it] }, onClick = { onEventSelected(event.id) })
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: EventEntity, car: CarEntity?, onClick: () -> Unit) {
    val type = runCatching { EventType.valueOf(event.type) }.getOrDefault(EventType.OTHER)
    DashboardRow(
        icon = type.icon,
        iconTint = type.color,
        title = event.title,
        subtitle = listOfNotNull(dayLabel(event.startTs), car?.name, event.locationLabel).joinToString(" · "),
        onClick = onClick,
    )
}
