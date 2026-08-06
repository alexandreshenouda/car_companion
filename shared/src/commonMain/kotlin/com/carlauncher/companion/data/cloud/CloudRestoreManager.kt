package com.carlauncher.companion.data.cloud

import com.carlauncher.companion.data.cloud.crypto.CryptoBox
import com.carlauncher.companion.data.cloud.crypto.KeyVault
import com.carlauncher.companion.data.cloud.crypto.fromBase64
import com.carlauncher.companion.data.cloud.dto.CarModificationRestoreRow
import com.carlauncher.companion.data.cloud.dto.CarRestoreRow
import com.carlauncher.companion.data.cloud.dto.EventRestoreRow
import com.carlauncher.companion.data.cloud.dto.EventTrackRestoreRow
import com.carlauncher.companion.data.cloud.dto.GpsChunkPayload
import com.carlauncher.companion.data.cloud.dto.PrivateBackupRow
import com.carlauncher.companion.data.cloud.dto.ProfileRestoreRow
import com.carlauncher.companion.data.cloud.dto.StatsBackupPayload
import com.carlauncher.companion.data.cloud.dto.TrophyUnlockRestoreRow
import com.carlauncher.companion.data.db.CarDao
import com.carlauncher.companion.data.db.CarEntity
import com.carlauncher.companion.data.db.CarModificationDao
import com.carlauncher.companion.data.db.CarModificationEntity
import com.carlauncher.companion.data.db.DeviceDao
import com.carlauncher.companion.data.db.DeviceEntity
import com.carlauncher.companion.data.db.EventDao
import com.carlauncher.companion.data.db.EventEntity
import com.carlauncher.companion.data.db.EventPointDao
import com.carlauncher.companion.data.db.EventPointEntity
import com.carlauncher.companion.data.db.LocationPointDao
import com.carlauncher.companion.data.db.LocationPointEntity
import com.carlauncher.companion.data.db.TrophyDao
import com.carlauncher.companion.data.db.TrophyProgressEntity
import com.carlauncher.companion.data.db.TrophyUnlockEntity
import com.carlauncher.companion.data.db.UserProfileDao
import com.carlauncher.companion.data.db.UserProfileEntity
import com.carlauncher.companion.util.Logger
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * What restore actually recovered, category by category — the UI reports this rather than a
 * bare boolean, since a partial restore (say events came back but GPS couldn't, because the
 * vault wasn't unlocked) is a very different outcome from a clean success or a total failure.
 */
data class RestoreResult(
    val attempted: Set<SyncCategory>,
    val failed: Map<SyncCategory, Throwable>,
    val gpsSkippedNoKey: Boolean,
) {
    val succeeded: Set<SyncCategory> get() = attempted - failed.keys
}

/**
 * Pulls cloud data down onto a device — the explicit, user-triggered counterpart to
 * [CloudSyncManager]'s continuous one-way push. Used on a fresh install/reinstall, or after
 * signing in on a phone that has never synced before.
 *
 * This never runs automatically. One-way backup means local Room is the source of truth during
 * normal use; restore is the one deliberate exception, and only the user can invoke it.
 */
@OptIn(ExperimentalTime::class)
class CloudRestoreManager(
    private val provider: SupabaseClientProvider,
    private val authRepository: AuthRepository,
    private val cloudPrefsRepository: CloudPrefsRepository,
    private val keyVault: KeyVault,
    /** Called after a restore actually recovered GPS points, so the recomputed truth can
     * replace the frozen stats snapshot — a plain callback rather than a direct
     * `TrophyRepository` dependency, since that repo still lives one layer up (in each
     * platform's own app module) and this class must not depend back on it. */
    private val onGpsRestored: suspend () -> Unit,
    private val carDao: CarDao,
    private val modificationDao: CarModificationDao,
    private val eventDao: EventDao,
    private val eventPointDao: EventPointDao,
    private val userProfileDao: UserProfileDao,
    private val trophyDao: TrophyDao,
    private val locationPointDao: LocationPointDao,
    private val deviceDao: DeviceDao,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun restoreAll(): RestoreResult? = mutex.withLock {
        val client = provider.client ?: return null
        val userId = authRepository.currentUserId() ?: return null

        val attempted = mutableSetOf<SyncCategory>()
        val failed = mutableMapOf<SyncCategory, Throwable>()

        suspend fun attempt(category: SyncCategory, block: suspend () -> Unit) {
            attempted += category
            try {
                block()
            } catch (e: Exception) {
                Logger.w(TAG, "Restore failed for $category", e)
                failed[category] = e
            }
        }

        attempt(SyncCategory.CARS) { restoreCars(client, userId) }
        attempt(SyncCategory.EVENTS) { restoreEvents(client, userId) }
        attempt(SyncCategory.PROFILE) { restoreProfile(client, userId) }
        attempt(SyncCategory.TROPHIES) { restoreTrophies(client, userId) }

        var gpsSkippedNoKey = false
        var gpsRestoredAnyPoints = false
        attempt(SyncCategory.GPS_HISTORY) {
            val result = restoreGpsHistory(client, userId)
            gpsSkippedNoKey = result == GpsRestoreOutcome.NO_KEY
            gpsRestoredAnyPoints = result == GpsRestoreOutcome.RESTORED
        }

        if (gpsRestoredAnyPoints) {
            // The recomputed truth beats a frozen snapshot, so prefer it over the stats blob
            // whenever there was actually GPS to recompute from.
            runCatching { onGpsRestored() }
                .onFailure { Logger.w(TAG, "Trophy recompute after restore failed", it) }
        } else {
            attempt(SyncCategory.STATISTICS) { restoreStatsBlob(client, userId) }
        }

        RestoreResult(attempted, failed, gpsSkippedNoKey)
    }

    // ------------------------------------------------------------------ cars

    private suspend fun restoreCars(client: SupabaseClient, userId: String) {
        val rows = client.postgrest.from("cars")
            .select { filter { eq("owner_id", userId) } }
            .decodeList<CarRestoreRow>()

        for (row in rows) {
            val createdAt = row.createdAt.fromIso()
            carDao.upsert(
                CarEntity(
                    id = row.id,
                    deviceId = null,
                    name = row.name,
                    brand = row.brand,
                    model = row.model,
                    year = row.year,
                    details = row.details,
                    odometerKm = row.odometerKm,
                    createdAt = createdAt,
                    isFavorite = false, // local favorite pick is a per-device UI preference
                    isShared = row.isShared,
                    updatedAt = createdAt,
                    cloudSyncedAt = Clock.System.now().toEpochMilliseconds(),
                ),
            )

            val mods = client.postgrest.from("car_modifications")
                .select { filter { eq("car_id", row.id) } }
                .decodeList<CarModificationRestoreRow>()
            mods.forEach { mod ->
                modificationDao.upsert(
                    CarModificationEntity(
                        carId = row.id,
                        title = mod.title,
                        category = mod.category,
                        installedAt = mod.installedAt.fromIso(),
                        cost = mod.cost,
                        notes = mod.notes,
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------ events

    private suspend fun restoreEvents(client: SupabaseClient, userId: String) {
        val rows = client.postgrest.from("events")
            .select { filter { eq("owner_id", userId) } }
            .decodeList<EventRestoreRow>()

        for (row in rows) {
            val startTs = row.startTs.fromIso()
            val endTs = row.endTs.fromIso()
            eventDao.upsert(
                EventEntity(
                    id = row.id,
                    title = row.title,
                    type = row.type,
                    carId = row.carId,
                    deviceId = null,
                    startTs = startTs,
                    endTs = endTs,
                    locationLabel = row.locationLabel,
                    notes = row.notes,
                    createdAt = row.createdAt.fromIso(),
                    pointsSource = row.pointsSource,
                    isShared = row.isShared,
                    updatedAt = row.createdAt.fromIso(),
                    cloudSyncedAt = Clock.System.now().toEpochMilliseconds(),
                ),
            )

            val track = client.postgrest.from("event_tracks")
                .select { filter { eq("event_id", row.id) } }
                .decodeSingleOrNull<EventTrackRestoreRow>() ?: continue

            val coords = PolylineCodec.decode(track.encodedPolyline)
            if (coords.isEmpty()) continue
            eventPointDao.deleteForEvent(row.id)
            eventPointDao.insertAll(
                coords.mapIndexed { i, (lat, lng) ->
                    EventPointEntity(
                        eventId = row.id,
                        lat = lat,
                        lng = lng,
                        ts = startTs + (track.timeOffsetsS.getOrElse(i) { 0 } * 1000L),
                        speedKmh = track.speedsKmh.getOrElse(i) { 0 },
                    )
                },
            )
        }
    }

    // ------------------------------------------------------------------ profile

    private suspend fun restoreProfile(client: SupabaseClient, userId: String) {
        val row = client.postgrest.from("profiles")
            .select { filter { eq("id", userId) } }
            .decodeSingleOrNull<ProfileRestoreRow>() ?: return

        userProfileDao.upsert(
            UserProfileEntity(
                age = row.age,
                city = row.city,
                departmentCodes = row.departmentCodes.joinToString(","),
            ),
        )
        // The account's chosen sharing level lives on the server; a fresh device should reflect
        // it rather than defaulting back to "private" as if nothing had ever been configured.
        cloudPrefsRepository.setVisibility(Visibility.from(row.visibility))
        cloudPrefsRepository.setFeedScope(FeedScope.from(row.feedScope))
    }

    // ------------------------------------------------------------------ trophies

    private suspend fun restoreTrophies(client: SupabaseClient, userId: String) {
        val rows = client.postgrest.from("trophy_unlocks")
            .select { filter { eq("owner_id", userId) } }
            .decodeList<TrophyUnlockRestoreRow>()
        if (rows.isEmpty()) return

        // IGNORE: unlocks are permanent and add-only, so this is strictly a union with
        // whatever is already unlocked locally — never a downgrade.
        trophyDao.insertUnlocks(
            rows.map {
                TrophyUnlockEntity(
                    id = it.trophyId,
                    unlockedAt = it.unlockedAt.fromIso(),
                    cloudSyncedAt = Clock.System.now().toEpochMilliseconds(),
                )
            },
        )
    }

    // ------------------------------------------------------------------ GPS history (E2E)

    private enum class GpsRestoreOutcome { RESTORED, EMPTY, NO_KEY }

    private suspend fun restoreGpsHistory(client: SupabaseClient, userId: String): GpsRestoreOutcome {
        val dek = keyVault.dekOrNull() ?: return GpsRestoreOutcome.NO_KEY

        val chunks = client.postgrest.from("private_backups")
            .select { filter { eq("owner_id", userId); eq("kind", "gps") } }
            .decodeList<PrivateBackupRow>()
            .sortedBy { it.chunkIndex }
        if (chunks.isEmpty()) return GpsRestoreOutcome.EMPTY

        val knownDeviceIds = mutableSetOf<String>()
        var restoredAny = false
        for (chunk in chunks) {
            val sealed = CryptoBox.Sealed(chunk.ciphertext.fromBase64(), chunk.nonce.fromBase64())
            val plaintext = try {
                CryptoBox.openCompressed(sealed, dek, CryptoBox.backupAad(userId, "gps", chunk.chunkIndex))
            } catch (e: Exception) {
                // A single unreadable chunk (corrupt, or encrypted under a since-replaced key)
                // shouldn't abort every other chunk's restore.
                Logger.w(TAG, "GPS chunk ${chunk.chunkIndex} could not be decrypted, skipping", e)
                continue
            }
            val payload = json.decodeFromString<GpsChunkPayload>(plaintext.decodeToString())
            if (payload.points.isEmpty()) continue

            if (knownDeviceIds.add(payload.deviceId) && deviceDao.getById(payload.deviceId) == null) {
                // A restore can land on a phone that has never heard of this deviceId (a car
                // paired only on the old phone) — seed a placeholder rather than dropping its
                // points on the floor.
                deviceDao.upsert(DeviceEntity(deviceId = payload.deviceId, name = payload.deviceId, addedAt = 0L))
            }

            locationPointDao.insertAll(
                payload.points.map {
                    LocationPointEntity(deviceId = payload.deviceId, lat = it.lat, lng = it.lng, ts = it.ts, speedKmh = it.speedKmh, pushedAtMillis = 0)
                },
            )
            restoredAny = true
        }
        return if (restoredAny) GpsRestoreOutcome.RESTORED else GpsRestoreOutcome.EMPTY
    }

    // ------------------------------------------------------------------ statistics fallback

    /** Only used when GPS wasn't also restored this run — otherwise the recomputed truth from
     * the trophy recompute callback is strictly better than this frozen snapshot. */
    private suspend fun restoreStatsBlob(client: SupabaseClient, userId: String) {
        val dek = keyVault.dekOrNull() ?: return
        val row = client.postgrest.from("private_backups")
            .select { filter { eq("owner_id", userId); eq("kind", "stats"); eq("chunk_index", 0) } }
            .decodeSingleOrNull<PrivateBackupRow>() ?: return

        val sealed = CryptoBox.Sealed(row.ciphertext.fromBase64(), row.nonce.fromBase64())
        val plaintext = CryptoBox.openCompressed(sealed, dek, CryptoBox.backupAad(userId, "stats", 0))
        val payload = json.decodeFromString<StatsBackupPayload>(plaintext.decodeToString())

        trophyDao.upsertProgress(
            TrophyProgressEntity(
                id = 0,
                totalDistanceKm = payload.totalDistanceKm,
                longestTripKm = payload.longestTripKm,
                maxSpeedKmh = payload.maxSpeedKmh,
                totalMovingSeconds = payload.totalMovingSeconds,
                tripCount = payload.tripCount,
                nightTripCount = payload.nightTripCount,
                earlyTripCount = payload.earlyTripCount,
                distinctDrivingDays = payload.distinctDrivingDays,
                bestStreakDays = payload.bestStreakDays,
                currentStreakDays = payload.currentStreakDays,
                seasonsDriven = payload.seasonsDriven,
                departmentCodes = payload.departmentCodes,
                mapSquaresVisited = payload.mapSquaresVisited,
                maxDistanceFromBaseKm = payload.maxDistanceFromBaseKm,
                // Collection counters (cars/mods/events/profile) are left at zero rather than
                // carried over from the snapshot: they're cheap to get right and stale ones
                // aren't worth the complexity here, because each platform's app-start hook
                // recomputes them from whatever CARS/EVENTS/PROFILE restore already wrote
                // locally regardless. This row is a bridge until that next natural refresh,
                // not the final state.
                carCount = 0,
                modificationCount = 0,
                eventCount = 0,
                gpxImportCount = 0,
                profileFieldsSet = 0,
                computedAt = payload.computedAt,
            ),
        )
    }

    private companion object {
        const val TAG = "CloudRestoreManager"
    }
}

@OptIn(ExperimentalTime::class)
private fun String.fromIso(): Long = Instant.parse(this).toEpochMilliseconds()
