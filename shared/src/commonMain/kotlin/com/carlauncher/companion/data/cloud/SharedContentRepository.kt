package com.carlauncher.companion.data.cloud

import com.carlauncher.companion.data.cloud.dto.CarModificationRestoreRow
import com.carlauncher.companion.data.cloud.dto.CarRestoreRow
import com.carlauncher.companion.data.cloud.dto.EventRestoreRow
import com.carlauncher.companion.data.cloud.dto.EventTrackPolylineRow
import com.carlauncher.companion.data.cloud.dto.EventTrackRestoreRow
import com.carlauncher.companion.data.cloud.dto.GetPublicProfileParams
import com.carlauncher.companion.data.cloud.dto.PublicProfileRow
import com.carlauncher.companion.data.cloud.dto.TrophyUnlockRestoreRow
import com.carlauncher.companion.data.db.LocationPointEntity
import com.carlauncher.companion.util.Logger
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Everything about *other* people's content, read entirely through RLS/RPCs rather than
 * Room — this is remote-only data with no local mirror, unlike everything else in `data/cloud/`.
 *
 * Listing another user's cars/events uses a plain `.select { eq("owner_id", userId) } }` rather
 * than a dedicated RPC: the `cars_select`/`events_select` RLS policies already resolve exactly
 * to "shared AND visible to me", so an unprivileged row simply never comes back — there's
 * nothing left for the client to filter. The single-item detail fetches below rely on that
 * same guarantee: if the row comes back at all, it was already cleared to be shown.
 */
class SharedContentRepository(private val provider: SupabaseClientProvider) {

    private val photoCacheMutex = Mutex()
    private val photoCache = mutableMapOf<String, ByteArray?>()

    suspend fun getProfile(userId: String): PublicProfileRow? {
        val client = provider.client ?: return null
        return runCatching {
            client.postgrest.rpc("get_public_profile", GetPublicProfileParams(userId)).decodeSingleOrNull<PublicProfileRow>()
        }.onFailure { Logger.w(TAG, "getProfile($userId) failed", it) }
            .onSuccess {
                Logger.d(TAG, "getProfile($userId) -> $it")
                if (it == null) {
                    // Null with no exception means the RPC's own WHERE clause excluded the
                    // row — not visible, not "friends" per are_friends(), or the row doesn't
                    // exist. Distinct from a real error, and worth telling apart in the logs.
                    Logger.d(TAG, "getProfile($userId): RPC returned no row (not visible to caller)")
                }
            }.getOrNull()
    }

    suspend fun listSharedCars(userId: String): List<CarRestoreRow> {
        val client = provider.client ?: return emptyList()
        return runCatching {
            client.postgrest.from("cars").select { filter { eq("owner_id", userId) } }.decodeList<CarRestoreRow>()
        }.onFailure { Logger.w(TAG, "listSharedCars($userId) failed", it) }.getOrDefault(emptyList())
    }

    suspend fun listSharedEvents(userId: String): List<EventRestoreRow> {
        val client = provider.client ?: return emptyList()
        return runCatching {
            client.postgrest.from("events").select { filter { eq("owner_id", userId) } }.decodeList<EventRestoreRow>()
        }.onFailure { Logger.w(TAG, "listSharedEvents($userId) failed", it) }.getOrDefault(emptyList())
    }

    suspend fun listTrophies(userId: String): List<TrophyUnlockRestoreRow> {
        val client = provider.client ?: return emptyList()
        return runCatching {
            client.postgrest.from("trophy_unlocks").select { filter { eq("owner_id", userId) } }.decodeList<TrophyUnlockRestoreRow>()
        }.onFailure { Logger.w(TAG, "listTrophies($userId) failed", it) }
            .onSuccess { Logger.d(TAG, "listTrophies($userId) -> ${it.size} row(s): ${it.map(TrophyUnlockRestoreRow::trophyId)}") }
            .getOrDefault(emptyList())
    }

    suspend fun getSharedCar(carId: String): Pair<CarRestoreRow, List<CarModificationRestoreRow>>? {
        val client = provider.client ?: return null
        return runCatching {
            val car = client.postgrest.from("cars").select { filter { eq("id", carId) } }
                .decodeSingleOrNull<CarRestoreRow>() ?: return@runCatching null
            val mods = client.postgrest.from("car_modifications").select { filter { eq("car_id", carId) } }
                .decodeList<CarModificationRestoreRow>()
            car to mods
        }.onFailure { Logger.w(TAG, "getSharedCar($carId) failed", it) }.getOrNull()
    }

    /** The trace comes back as [LocationPointEntity] — the exact shape [buildSpeedSegments]
     * and the shared `EventTraceMap` composable already consume, so a shared event's remote
     * trace renders through the identical code path as a local one, not a parallel copy of it. */
    suspend fun getSharedEvent(eventId: String): Pair<EventRestoreRow, List<LocationPointEntity>>? {
        val client = provider.client ?: return null
        return runCatching {
            val event = client.postgrest.from("events").select { filter { eq("id", eventId) } }
                .decodeSingleOrNull<EventRestoreRow>() ?: return@runCatching null
            val track = client.postgrest.from("event_tracks").select { filter { eq("event_id", eventId) } }
                .decodeSingleOrNull<EventTrackRestoreRow>()
                ?.let { row ->
                    val startTs = event.startTs.parseIsoToEpochMilli()
                    PolylineCodec.decode(row.encodedPolyline).mapIndexed { i, (lat, lng) ->
                        LocationPointEntity(
                            deviceId = "",
                            lat = lat,
                            lng = lng,
                            ts = startTs + (row.timeOffsetsS.getOrElse(i) { 0 } * 1000L),
                            speedKmh = row.speedsKmh.getOrElse(i) { 0 },
                            pushedAtMillis = 0,
                        )
                    }
                }.orEmpty()
            event to track
        }.onFailure { Logger.w(TAG, "getSharedEvent($eventId) failed", it) }.getOrNull()
    }

    /** Just the decoded lat/lng path, for a feed card's route sketch — deliberately skips the
     * per-point speeds/timestamps [getSharedEvent] pulls in, since a card only draws a static
     * line, not a speed-colored trail. */
    suspend fun getEventTrackPreview(eventId: String): List<Pair<Double, Double>>? {
        val client = provider.client ?: return null
        return runCatching {
            client.postgrest.from("event_tracks")
                .select(Columns.list("encoded_polyline")) { filter { eq("event_id", eventId) } }
                .decodeSingleOrNull<EventTrackPolylineRow>()
                ?.let { PolylineCodec.decode(it.encodedPolyline) }
        }.onFailure { Logger.w(TAG, "getEventTrackPreview($eventId) failed", it) }.getOrNull()
    }

    /** Downloads a shared car's photo bytes via the `car-photos` bucket's RLS-gated
     * `authenticated` endpoint — no public URL exists, so visibility is enforced the same way
     * as everything else here, by `storage.objects`' own policies. `null` covers both "this car
     * has no photo" and "not visible to the caller", same as every other method in this class.
     *
     * Successful downloads are cached in-memory, keyed by [photoUpdatedAt] rather than just
     * [carId] — the object at `{carId}.jpg` is overwritten in place on every re-upload, so
     * without the version in the key a car whose owner changed their photo this session would
     * keep showing the stale bytes fetched earlier. Shared across every caller (Feed cards,
     * [getSharedCar]'s detail screen) since this repository is a single long-lived instance for
     * the signed-in session.
     *
     * A failed download is deliberately NOT cached: by the time this runs, the row already says
     * a photo exists, so `null` here only ever means a transient failure, never a legitimate
     * "no photo" — caching it would turn one flaky network blip into a permanently blank photo
     * for the rest of the session. */
    suspend fun getCarPhoto(carId: String, photoUpdatedAt: String?): ByteArray? {
        if (photoUpdatedAt == null) return null
        val key = "$carId:$photoUpdatedAt"
        photoCacheMutex.withLock { photoCache[key] }?.let { return it }

        val client = provider.client ?: return null
        val bytes = runCatching { client.storage.from("car-photos").downloadAuthenticated("$carId.jpg") }
            .onFailure { Logger.w(TAG, "getCarPhoto($carId) failed", it) }
            .getOrNull()
        if (bytes != null) photoCacheMutex.withLock { photoCache[key] = bytes }
        return bytes
    }

    private companion object {
        const val TAG = "SharedContentRepo"
    }
}

@OptIn(ExperimentalTime::class)
fun String.parseIsoToEpochMilli(): Long = Instant.parse(this).toEpochMilliseconds()
