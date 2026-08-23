package com.carlauncher.companion.data.cloud

import com.carlauncher.companion.data.cloud.crypto.CryptoBox
import com.carlauncher.companion.data.cloud.crypto.KeyVault
import com.carlauncher.companion.data.cloud.crypto.toBase64
import com.carlauncher.companion.data.cloud.dto.CarModificationRow
import com.carlauncher.companion.data.cloud.dto.CarRow
import com.carlauncher.companion.data.cloud.dto.EventRow
import com.carlauncher.companion.data.cloud.dto.EventTrackRow
import com.carlauncher.companion.data.cloud.dto.GpsChunkPayload
import com.carlauncher.companion.data.cloud.dto.GpsPointPayload
import com.carlauncher.companion.data.cloud.dto.PrivateBackupRow
import com.carlauncher.companion.data.cloud.dto.ProfileUpdateRow
import com.carlauncher.companion.data.cloud.dto.StatsBackupPayload
import com.carlauncher.companion.data.cloud.dto.TrophyUnlockRow
import com.carlauncher.companion.data.cloud.dto.VisibilityUpdateRow
import com.carlauncher.companion.data.db.CarDao
import com.carlauncher.companion.data.db.CarEntity
import com.carlauncher.companion.data.db.CarModificationDao
import com.carlauncher.companion.data.db.CarModificationEntity
import com.carlauncher.companion.data.db.DeviceDao
import com.carlauncher.companion.data.db.EventDao
import com.carlauncher.companion.data.db.EventEntity
import com.carlauncher.companion.data.db.EventPointDao
import com.carlauncher.companion.data.db.EventPointEntity
import com.carlauncher.companion.data.db.LocationPointDao
import com.carlauncher.companion.data.db.LocationPointEntity
import com.carlauncher.companion.data.db.TrophyDao
import com.carlauncher.companion.data.db.UserProfileDao
import com.carlauncher.companion.data.repo.PlatformFileStore
import com.carlauncher.companion.data.repo.XpRepository
import com.carlauncher.companion.data.repo.computeStats
import com.carlauncher.companion.util.Logger
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Which of the six categories a sync pass actually attempted, and which failed. */
data class CloudSyncResult(
    val ranAt: Long,
    val attempted: Set<SyncCategory>,
    val failed: Map<SyncCategory, Throwable>,
) {
    val succeeded: Set<SyncCategory> get() = attempted - failed.keys
}

/**
 * One-way push: local Room is the source of truth, the cloud is a mirror. Nothing here ever
 * pulls a category back down except [CloudRestoreManager]'s explicit, user-triggered restore.
 *
 * Every category push is independently try/caught — a network hiccup mid-run on one category
 * (say GPS, the largest) must not abort the small, cheap ones. [syncAll] is safe to call from
 * both a background sync job and a foreground "Sync now" button; the mutex means an overlap
 * between the two collapses into one run rather than racing.
 */
@OptIn(ExperimentalTime::class)
class CloudSyncManager(
    private val provider: SupabaseClientProvider,
    private val authRepository: AuthRepository,
    private val cloudPrefsRepository: CloudPrefsRepository,
    private val keyVault: KeyVault,
    private val carDao: CarDao,
    private val modificationDao: CarModificationDao,
    private val eventDao: EventDao,
    private val eventPointDao: EventPointDao,
    private val userProfileDao: UserProfileDao,
    private val trophyDao: TrophyDao,
    private val locationPointDao: LocationPointDao,
    private val deviceDao: DeviceDao,
    private val photoStore: PlatformFileStore,
    private val xpRepository: XpRepository,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun syncAll(): CloudSyncResult? = mutex.withLock {
        val client = provider.client ?: return null
        val userId = authRepository.currentUserId() ?: return null
        val prefs = cloudPrefsRepository.current()

        val attempted = mutableSetOf<SyncCategory>()
        val failed = mutableMapOf<SyncCategory, Throwable>()

        suspend fun attempt(category: SyncCategory, block: suspend () -> Unit) {
            attempted += category
            try {
                block()
            } catch (e: Exception) {
                Logger.w(TAG, "Cloud sync failed for $category", e)
                failed[category] = e
            }
        }

        // Visibility/feed-scope are core account settings, not one of the six upload
        // categories — they're pushed every run regardless of any toggle.
        runCatching { pushVisibility(client, userId) }
            .onFailure { Logger.w(TAG, "Visibility push failed", it) }

        if (prefs.isEnabled(SyncCategory.CARS)) attempt(SyncCategory.CARS) { pushCars(client, userId) }
        if (prefs.isEnabled(SyncCategory.EVENTS)) attempt(SyncCategory.EVENTS) { pushEvents(client, userId) }
        if (prefs.isEnabled(SyncCategory.PROFILE)) attempt(SyncCategory.PROFILE) { pushProfile(client, userId) }
        if (prefs.isEnabled(SyncCategory.TROPHIES)) attempt(SyncCategory.TROPHIES) { pushTrophies(client, userId) }
        if (prefs.isEnabled(SyncCategory.GPS_HISTORY)) attempt(SyncCategory.GPS_HISTORY) { pushGpsHistory(client, userId) }
        if (prefs.isEnabled(SyncCategory.STATISTICS)) attempt(SyncCategory.STATISTICS) { pushStats(client, userId) }

        val now = Clock.System.now().toEpochMilliseconds()
        cloudPrefsRepository.recordSync(now)
        CloudSyncResult(now, attempted, failed)
    }

    /** Wipes every local "already synced" marker — see the DAO doc comments for why this must
     * run on sign-out: those markers are local flags, not scoped to a particular account. */
    suspend fun resetLocalSyncMarkers() {
        carDao.clearSyncMarkers()
        eventDao.clearSyncMarkers()
        trophyDao.clearSyncMarkers()
    }

    // ------------------------------------------------------------------ cars

    private suspend fun pushCars(client: SupabaseClient, userId: String) {
        val dirty = carDao.getDirty()
        if (dirty.isNotEmpty()) {
            client.postgrest.from("cars").upsert(dirty.map { it.toRow(userId) })
            for (car in dirty) {
                pushModifications(client, userId, car.id)
                if (car.photoUpdatedAt != car.photoSyncedAt) pushPhoto(client, car)
            }
            val now = Clock.System.now().toEpochMilliseconds()
            dirty.forEach { carDao.markSynced(it.id, now) }
        }
        deleteOrphans(client, "cars", userId, carDao.getAllIds())
    }

    /** Modifications carry no dirty-tracking of their own — replaced wholesale on every push
     * of their parent car, which is cheap given how few a car typically has. */
    private suspend fun pushModifications(client: SupabaseClient, userId: String, carId: String) {
        client.postgrest.from("car_modifications").delete { filter { eq("car_id", carId) } }
        val mods = modificationDao.observeForCar(carId).first()
        if (mods.isNotEmpty()) {
            client.postgrest.from("car_modifications").upsert(mods.map { it.toRow(userId) })
        }
    }

    /** Uploads/deletes the `car-photos` bucket object to match [car]'s local photo state — the
     * `cars` row upsert just above already carries the matching `photo_updated_at`, so a friend
     * viewing the shared car sees the two change together. */
    private suspend fun pushPhoto(client: SupabaseClient, car: CarEntity) {
        val bucket = client.storage.from("car-photos")
        val path = car.photoPath
        if (path != null) {
            val bytes = photoStore.readCarPhoto(path) ?: return
            bucket.upload("${car.id}.jpg", bytes) { upsert = true }
        } else {
            bucket.delete(listOf("${car.id}.jpg"))
        }
        carDao.markPhotoSynced(car.id, car.photoUpdatedAt)
    }

    // ------------------------------------------------------------------ events

    private suspend fun pushEvents(client: SupabaseClient, userId: String) {
        val dirty = eventDao.getDirty()
        if (dirty.isNotEmpty()) {
            val eventRows = mutableListOf<EventRow>()
            val trackRows = mutableListOf<EventTrackRow>()
            for (event in dirty) {
                val points = eventPointDao.getForEvent(event.id)
                val stats = computeStats(points.map { it.asLocationPoint() })
                eventRows += event.toRow(
                    userId,
                    // A car_id the remote `cars` row doesn't have yet would fail the
                    // `events_car_id_fkey` constraint outright — most commonly because Cars
                    // backup is off while Events backup is on, so that car is never pushed at
                    // all. Drop the cross-reference in that case rather than lose the whole
                    // event push over one foreign key.
                    remoteCarId = event.carId?.takeIf { carDao.getById(it)?.cloudSyncedAt != null },
                    distanceKm = stats.distanceKm,
                    maxSpeedKmh = stats.maxSpeedKmh,
                    movingSeconds = stats.movingTimeSeconds,
                    pointCount = points.size,
                )
                if (points.isNotEmpty()) {
                    trackRows += EventTrackRow(
                        eventId = event.id,
                        ownerId = userId,
                        encodedPolyline = PolylineCodec.encode(points.map { it.lat to it.lng }),
                        speedsKmh = points.map { it.speedKmh },
                        timeOffsetsS = points.map { ((it.ts - event.startTs) / 1000).toInt() },
                    )
                }
            }
            client.postgrest.from("events").upsert(eventRows)
            if (trackRows.isNotEmpty()) client.postgrest.from("event_tracks").upsert(trackRows)
            val now = Clock.System.now().toEpochMilliseconds()
            dirty.forEach { eventDao.markSynced(it.id, now) }
        }
        deleteOrphans(client, "events", userId, eventDao.getAllIds())
    }

    // ------------------------------------------------------------------ profile / visibility

    private suspend fun pushProfile(client: SupabaseClient, userId: String) {
        val profile = userProfileDao.observe().first()
        client.postgrest.from("profiles").update(
            ProfileUpdateRow(
                age = profile?.age,
                city = profile?.city,
                departmentCodes = profile?.departmentCodes?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            ),
        ) { filter { eq("id", userId) } }
    }

    private suspend fun pushVisibility(client: SupabaseClient, userId: String) {
        val prefs = cloudPrefsRepository.current()
        val xp = xpRepository.currentState()
        client.postgrest.from("profiles").update(
            VisibilityUpdateRow(
                visibility = prefs.visibility,
                feedScope = prefs.feedScope,
                shareProfile = prefs.shareProfileInfo,
                shareGarage = prefs.shareGarageSection,
                shareTrophies = prefs.shareTrophiesSection,
                totalXp = xp.totalXp,
                level = xp.level,
                loginStreakDays = xp.currentStreakDays,
                leaderboardVisibility = prefs.leaderboardVisibility,
            ),
        ) { filter { eq("id", userId) } }
    }

    // ------------------------------------------------------------------ trophies

    private suspend fun pushTrophies(client: SupabaseClient, userId: String) {
        val dirty = trophyDao.getDirtyUnlocks()
        if (dirty.isEmpty()) return
        client.postgrest.from("trophy_unlocks").upsert(
            dirty.map { TrophyUnlockRow(ownerId = userId, trophyId = it.id, unlockedAtIso = it.unlockedAt.toIso()) },
        )
        val now = Clock.System.now().toEpochMilliseconds()
        dirty.forEach { trophyDao.markUnlockSynced(it.id, now) }
    }

    // ------------------------------------------------------------------ GPS history (E2E)

    private suspend fun pushGpsHistory(client: SupabaseClient, userId: String) {
        // No key, no upload: this can happen right after a password reset without the
        // recovery code, before a fresh key exists. Silently skipping (rather than failing
        // loudly every cycle) is correct — the next successful unlock will pick it back up.
        val dek = keyVault.dekOrNull() ?: return

        val prefs = cloudPrefsRepository.current()
        val cursors = runCatching { json.decodeFromString<Map<String, Long>>(prefs.gpsCursorsJson) }
            .getOrDefault(emptyMap()).toMutableMap()
        var nextChunk = prefs.gpsNextChunkIndex

        for (device in deviceDao.observeAll().first()) {
            var afterTs = cursors[device.deviceId] ?: 0L
            while (true) {
                val page = locationPointDao.pageForDevice(device.deviceId, afterTs, GPS_CHUNK_SIZE)
                if (page.isEmpty()) break

                val payload = GpsChunkPayload(
                    deviceId = device.deviceId,
                    points = page.map { GpsPointPayload(it.lat, it.lng, it.ts, it.speedKmh) },
                )
                val sealed = CryptoBox.sealCompressed(
                    json.encodeToString(payload).encodeToByteArray(),
                    dek,
                    CryptoBox.backupAad(userId, "gps", nextChunk),
                )
                client.postgrest.from("private_backups").upsert(
                    PrivateBackupRow(userId, "gps", nextChunk, sealed.ciphertext.toBase64(), sealed.nonce.toBase64()),
                )

                afterTs = page.last().ts
                nextChunk += 1
                cursors[device.deviceId] = afterTs
                // Persisted after every chunk, not just at the end — an interrupted run
                // resumes rather than re-uploading everything already sent.
                cloudPrefsRepository.recordGpsProgress(json.encodeToString(cursors), nextChunk)

                if (page.size < GPS_CHUNK_SIZE) break
            }
        }
    }

    // ------------------------------------------------------------------ statistics (E2E)

    private suspend fun pushStats(client: SupabaseClient, userId: String) {
        val dek = keyVault.dekOrNull() ?: return
        val progress = trophyDao.getProgress() ?: return
        val prefs = cloudPrefsRepository.current()
        val statsLastSyncedComputedAt = prefs.statsLastSyncedComputedAt
        if (statsLastSyncedComputedAt != null && progress.computedAt <= statsLastSyncedComputedAt) return

        val payload = StatsBackupPayload(
            totalDistanceKm = progress.totalDistanceKm,
            longestTripKm = progress.longestTripKm,
            maxSpeedKmh = progress.maxSpeedKmh,
            totalMovingSeconds = progress.totalMovingSeconds,
            tripCount = progress.tripCount,
            nightTripCount = progress.nightTripCount,
            earlyTripCount = progress.earlyTripCount,
            distinctDrivingDays = progress.distinctDrivingDays,
            bestStreakDays = progress.bestStreakDays,
            currentStreakDays = progress.currentStreakDays,
            seasonsDriven = progress.seasonsDriven,
            departmentCodes = progress.departmentCodes,
            mapSquaresVisited = progress.mapSquaresVisited,
            maxDistanceFromBaseKm = progress.maxDistanceFromBaseKm,
            computedAt = progress.computedAt,
        )
        val sealed = CryptoBox.sealCompressed(
            json.encodeToString(payload).encodeToByteArray(),
            dek,
            CryptoBox.backupAad(userId, "stats", 0),
        )
        client.postgrest.from("private_backups").upsert(
            PrivateBackupRow(userId, "stats", 0, sealed.ciphertext.toBase64(), sealed.nonce.toBase64()),
        )
        cloudPrefsRepository.recordStatsSynced(progress.computedAt)
    }

    // ------------------------------------------------------------------ shared helpers

    /** Deletes remote rows whose id is no longer present locally — how a local delete
     * propagates, since Room has no concept of a remote tombstone to push. */
    private suspend fun deleteOrphans(client: SupabaseClient, table: String, userId: String, localIds: List<String>) {
        if (localIds.isEmpty()) {
            client.postgrest.from(table).delete { filter { eq("owner_id", userId) } }
        } else {
            client.postgrest.from(table).delete {
                filter {
                    eq("owner_id", userId)
                    filterNot("id", FilterOperator.IN, localIds)
                }
            }
        }
    }

    private companion object {
        const val TAG = "CloudSyncManager"
        const val GPS_CHUNK_SIZE = 1000
    }
}

// ---------------------------------------------------------------------- entity -> row mapping

private fun CarEntity.toRow(ownerId: String) = CarRow(
    id = id, ownerId = ownerId, name = name, brand = brand, model = model, year = year,
    details = details, odometerKm = odometerKm, isFavorite = isFavorite, isShared = isShared,
    photoUpdatedAt = photoUpdatedAt?.toIso(),
)

@OptIn(ExperimentalUuidApi::class)
private fun CarModificationEntity.toRow(ownerId: String) = CarModificationRow(
    id = Uuid.random().toString(), carId = carId, ownerId = ownerId, title = title,
    category = category, installedAtIso = installedAt.toIso(), cost = cost, notes = notes,
)

private fun EventEntity.toRow(
    ownerId: String,
    remoteCarId: String?,
    distanceKm: Double,
    maxSpeedKmh: Int,
    movingSeconds: Long,
    pointCount: Int,
) = EventRow(
    id = id, ownerId = ownerId, carId = remoteCarId, title = title, type = type,
    startTsIso = startTs.toIso(), endTsIso = endTs.toIso(), locationLabel = locationLabel,
    notes = notes, pointsSource = pointsSource, distanceKm = distanceKm, maxSpeedKmh = maxSpeedKmh,
    movingSeconds = movingSeconds, pointCount = pointCount, isShared = isShared,
)

/** [computeStats] takes `LocationPointEntity`; event points share the same shape minus the
 * fields the calculation never reads, so this is a pure reshape, not a real conversion. */
private fun EventPointEntity.asLocationPoint() =
    LocationPointEntity(deviceId = "", lat = lat, lng = lng, ts = ts, speedKmh = speedKmh, pushedAtMillis = 0)

@OptIn(ExperimentalTime::class)
private fun Long.toIso(): String = Instant.fromEpochMilliseconds(this).toString()
