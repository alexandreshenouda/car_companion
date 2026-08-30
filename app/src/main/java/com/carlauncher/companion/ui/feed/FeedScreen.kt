package com.carlauncher.companion.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlauncher.companion.R
import com.carlauncher.companion.data.cloud.CloudPrefsRepository
import com.carlauncher.companion.data.cloud.FeedActivity
import com.carlauncher.companion.data.cloud.FeedRepository
import com.carlauncher.companion.data.cloud.FeedScope
import com.carlauncher.companion.data.cloud.SharedContentRepository
import com.carlauncher.companion.data.cloud.labelRes
import com.carlauncher.companion.data.model.Trophy
import com.carlauncher.companion.data.model.color
import com.carlauncher.companion.data.model.descriptionRes
import com.carlauncher.companion.data.model.icon
import com.carlauncher.companion.data.model.labelRes
import com.carlauncher.companion.data.model.titleRes
import com.carlauncher.companion.ui.common.CarPhotoBadge
import com.carlauncher.companion.ui.common.IconBadge
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.common.NeonSegmentedSelector
import com.carlauncher.companion.ui.common.RouteSketch
import com.carlauncher.companion.ui.theme.AccentEvents
import com.carlauncher.companion.ui.theme.AccentGarage
import com.carlauncher.companion.ui.theme.AccentTrophy
import com.carlauncher.companion.util.formatAbsolute
import kotlinx.coroutines.launch

/**
 * A Strava-like history of shared activity: cars added to a garage, events shared, trophies
 * unlocked — by the account itself and by friends (or everyone, per [FeedScope]). Purely a
 * client-side rendering of `get_feed`'s RPC output; every visibility decision already happened
 * server-side, so there is nothing here to double-check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    feedRepository: FeedRepository,
    sharedContentRepository: SharedContentRepository,
    cloudPrefsRepository: CloudPrefsRepository,
    onOpenCar: (ownerId: String, carId: String) -> Unit,
    onOpenEvent: (ownerId: String, eventId: String) -> Unit,
    onOpenProfile: (userId: String) -> Unit,
    onOpenFriends: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val prefs by cloudPrefsRepository.prefs.collectAsStateWithLifecycle(initialValue = null)
    val feedScope = FeedScope.from(prefs?.feedScope)

    var activities by remember { mutableStateOf<List<FeedActivity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    // Keyed by event id rather than scroll position, so a card scrolled out and back into view
    // reuses the fetched route instead of re-fetching it. A missing key means "not fetched yet";
    // a present key mapped to null means "fetched, no track" (or the fetch failed) — either way,
    // nothing left to try.
    val trackCache = remember { mutableStateMapOf<String, List<Pair<Double, Double>>?>() }
    suspend fun ensureTrackLoaded(eventId: String) {
        if (trackCache.containsKey(eventId)) return
        trackCache[eventId] = sharedContentRepository.getEventTrackPreview(eventId)
    }

    // Keyed by activity id rather than car id: two activities can reference the same car (e.g.
    // "added" then later "shared") with different photoUpdatedAt snapshots, and keying this way
    // sidesteps ever needing to reconcile that — SharedContentRepository's own cache (keyed by
    // car id + photoUpdatedAt) still dedupes the actual network fetch either way.
    val photoCache = remember { mutableStateMapOf<String, ByteArray?>() }
    // Deliberately doesn't remember a failed fetch the way trackCache remembers a car with no
    // photo (getCarPhoto only ever returns null here on a transient failure, never as a
    // legitimate "no photo" answer — see its own doc comment) — leaving the key unset means the
    // next retry (see photoRefreshToken below) has something to actually attempt.
    suspend fun ensurePhotoLoaded(activityId: String, carId: String, photoUpdatedAt: String?) {
        if (photoCache.containsKey(activityId)) return
        val bytes = sharedContentRepository.getCarPhoto(carId, photoUpdatedAt)
        if (bytes != null) photoCache[activityId] = bytes
    }

    // Card composables survive a refresh unchanged (LazyColumn keys them by the now-stable
    // activityId), so LaunchedEffect(activity.activityId) alone would never re-fire for a photo
    // that failed earlier — pull-to-refresh would refresh everything except the one thing a user
    // pulling to refresh a blank photo actually wants retried. Bumping this on every refresh and
    // folding it into that LaunchedEffect's key forces exactly that retry; a photo that already
    // succeeded just re-reads instantly from SharedContentRepository's own cache.
    var photoRefreshToken by remember { mutableStateOf(0) }

    LaunchedEffect(feedScope) {
        loading = true
        endReached = false
        activities = feedRepository.page(feedScope.wire, before = null)
        loading = false
    }

    fun refresh() {
        scope.launch {
            isRefreshing = true
            endReached = false
            photoRefreshToken++
            activities = feedRepository.page(feedScope.wire, before = null)
            isRefreshing = false
        }
    }

    fun loadMore() {
        val oldest = activities.lastOrNull()?.createdAt ?: return
        if (loadingMore || endReached) return
        loadingMore = true
        scope.launch {
            val more = feedRepository.page(feedScope.wire, before = oldest)
            if (more.isEmpty()) endReached = true
            activities = activities + more
            loadingMore = false
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeonSegmentedSelector(
                options = FeedScope.entries,
                selected = feedScope,
                label = { stringResource(it.labelRes) },
                onSelect = { scope.launch { cloudPrefsRepository.setFeedScope(it) } },
                accent = AccentEvents,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onOpenFriends) {
                Icon(
                    Icons.Filled.People,
                    contentDescription = stringResource(R.string.profile_friends_title),
                    tint = AccentEvents,
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentEvents)
                }

                activities.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    ) {
                        IconBadge(Icons.Filled.DynamicFeed, MaterialTheme.colorScheme.onSurfaceVariant, size = 64.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (feedScope == FeedScope.FRIENDS) stringResource(R.string.feed_empty_friends) else stringResource(R.string.feed_empty_everyone),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.feed_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (feedScope == FeedScope.FRIENDS) {
                            Spacer(Modifier.height(16.dp))
                            NeonPill(
                                text = stringResource(R.string.profile_friends_title),
                                accent = AccentEvents,
                                onClick = onOpenFriends,
                            )
                        }
                    }
                }

                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    items(activities, key = { it.activityId }) { activity ->
                        ActivityCard(
                            activity = activity,
                            trackCache = trackCache,
                            ensureTrackLoaded = ::ensureTrackLoaded,
                            photoCache = photoCache,
                            ensurePhotoLoaded = ::ensurePhotoLoaded,
                            photoRefreshToken = photoRefreshToken,
                            onClick = {
                                when (activity.kind) {
                                    "car_added", "car_shared" -> activity.subjectId?.let { onOpenCar(activity.actorId, it) }
                                    "event_shared" -> activity.subjectId?.let { onOpenEvent(activity.actorId, it) }
                                    else -> onOpenProfile(activity.actorId)
                                }
                            },
                        )
                        if (activity == activities.last()) {
                            LaunchedEffect(activity.activityId) { loadMore() }
                        }
                    }
                    if (loadingMore) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                                CircularProgressIndicator(color = AccentEvents, modifier = Modifier.height(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(
    activity: FeedActivity,
    trackCache: Map<String, List<Pair<Double, Double>>?>,
    ensureTrackLoaded: suspend (String) -> Unit,
    photoCache: Map<String, ByteArray?>,
    ensurePhotoLoaded: suspend (activityId: String, carId: String, photoUpdatedAt: String?) -> Unit,
    photoRefreshToken: Int,
    onClick: () -> Unit,
) {
    when (activity.kind) {
        "car_added", "car_shared" -> CarActivityCard(activity, photoCache, ensurePhotoLoaded, photoRefreshToken, onClick)
        "event_shared" -> EventActivityCard(activity, trackCache, ensureTrackLoaded, onClick)
        "trophy_unlocked" -> TrophyActivityCard(activity, onClick)
        else -> DefaultActivityCard(activity, onClick)
    }
}

@Composable
private fun CarActivityCard(
    activity: FeedActivity,
    photoCache: Map<String, ByteArray?>,
    ensurePhotoLoaded: suspend (activityId: String, carId: String, photoUpdatedAt: String?) -> Unit,
    photoRefreshToken: Int,
    onClick: () -> Unit,
) {
    val carId = activity.subjectId
    LaunchedEffect(activity.activityId, photoRefreshToken) {
        carId?.let { ensurePhotoLoaded(activity.activityId, it, activity.photoUpdatedAt) }
    }

    NeonCard(accent = AccentGarage, modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CarPhotoBadge(bytes = photoCache[activity.activityId], tint = AccentGarage, size = 64.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(activity.headline(), style = MaterialTheme.typography.titleMedium)
                activity.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (activity.modCount > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        pluralStringResource(R.plurals.feed_mod_count, activity.modCount, activity.modCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentGarage,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(formatAbsolute(activity.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EventActivityCard(
    activity: FeedActivity,
    trackCache: Map<String, List<Pair<Double, Double>>?>,
    ensureTrackLoaded: suspend (String) -> Unit,
    onClick: () -> Unit,
) {
    val eventId = activity.subjectId
    LaunchedEffect(eventId) { eventId?.let { ensureTrackLoaded(it) } }
    val track = eventId?.let { trackCache[it] }

    NeonCard(accent = AccentEvents, modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        if (!track.isNullOrEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 7f)
                    .background(AccentEvents.copy(alpha = 0.08f)),
            ) {
                RouteSketch(points = track, accent = AccentEvents, modifier = Modifier.fillMaxSize())
            }
        }
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            IconBadge(Icons.Filled.Route, AccentEvents)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(activity.headline(), style = MaterialTheme.typography.titleMedium)
                activity.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (activity.distanceKm > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.feed_distance_speed_format, activity.distanceKm, activity.maxSpeedKmh),
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentEvents,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(formatAbsolute(activity.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TrophyActivityCard(activity: FeedActivity, onClick: () -> Unit) {
    val trophy = activity.subjectKey?.let { runCatching { Trophy.valueOf(it) }.getOrNull() }
    val accent = trophy?.tier?.color ?: AccentTrophy
    NeonCard(accent = accent, modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            IconBadge(trophy?.icon ?: Icons.Filled.EmojiEvents, accent, size = 72.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(activity.headline(), style = MaterialTheme.typography.titleMedium)
                trophy?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(it.descriptionRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    NeonPill(stringResource(it.tier.labelRes), accent)
                }
                Spacer(Modifier.height(6.dp))
                Text(formatAbsolute(activity.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DefaultActivityCard(activity: FeedActivity, onClick: () -> Unit) {
    NeonCard(accent = AccentEvents, modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.DynamicFeed, AccentEvents)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(activity.headline(), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(formatAbsolute(activity.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FeedActivity.headline(): String {
    val name = actorDisplayName?.takeIf { it.isNotBlank() } ?: actorUsername
    return when (kind) {
        "car_added" -> stringResource(R.string.feed_headline_car_added, name, title ?: stringResource(R.string.feed_fallback_car))
        "car_shared" -> stringResource(R.string.feed_headline_shared, name, title ?: stringResource(R.string.feed_fallback_car))
        "event_shared" -> stringResource(R.string.feed_headline_shared, name, title ?: stringResource(R.string.feed_fallback_trip))
        "trophy_unlocked" -> {
            val trophyTitle = subjectKey?.let { runCatching { Trophy.valueOf(it) }.getOrNull()?.titleRes }
                ?.let { stringResource(it) }
            stringResource(R.string.feed_headline_trophy_unlocked, name, trophyTitle ?: stringResource(R.string.feed_fallback_trophy))
        }
        else -> name
    }
}
