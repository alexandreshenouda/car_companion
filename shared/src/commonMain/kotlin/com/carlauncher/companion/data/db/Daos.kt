package com.carlauncher.companion.data.db

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE deviceId = :deviceId")
    suspend fun getById(deviceId: String): DeviceEntity?

    @Upsert
    suspend fun upsert(device: DeviceEntity)

    @Delete
    suspend fun delete(device: DeviceEntity)
}

@Dao
interface LocationPointDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(points: List<LocationPointEntity>): List<Long>

    @Query("SELECT * FROM location_points WHERE deviceId = :deviceId ORDER BY ts DESC LIMIT 1")
    fun observeLatest(deviceId: String): Flow<LocationPointEntity?>

    @Query(
        "SELECT * FROM location_points WHERE deviceId = :deviceId AND ts BETWEEN :fromTs AND :toTs ORDER BY ts ASC",
    )
    suspend fun getInRange(deviceId: String, fromTs: Long, toTs: Long): List<LocationPointEntity>

    @Query("SELECT COUNT(*) FROM location_points WHERE deviceId = :deviceId")
    suspend fun countForDevice(deviceId: String): Int

    /**
     * Chronological page for the trophy rescan. Ordered by `ts` rather than `id` because
     * synced history arrives out of chronological order, which would break trip
     * segmentation; rides the existing unique (deviceId, ts) index.
     */
    @Query(
        "SELECT * FROM location_points WHERE deviceId = :deviceId AND ts > :afterTs ORDER BY ts ASC LIMIT :limit",
    )
    suspend fun pageForDevice(deviceId: String, afterTs: Long, limit: Int): List<LocationPointEntity>

    @Query("DELETE FROM location_points WHERE deviceId = :deviceId")
    suspend fun deleteAllForDevice(deviceId: String)

    @Query(
        "DELETE FROM location_points WHERE deviceId = :deviceId AND ts BETWEEN :fromTs AND :toTs",
    )
    suspend fun deleteInRange(deviceId: String, fromTs: Long, toTs: Long)

    @Query("DELETE FROM location_points WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Re-labels points from one device to another — a plain column update, not a delete+reinsert,
     * so nothing is lost. `OR IGNORE` skips any row that would collide with an existing
     * (targetDeviceId, ts) pair in the unique index, leaving that single row under its original
     * device rather than failing the whole batch.
     */
    @Query(
        "UPDATE OR IGNORE location_points SET deviceId = :targetDeviceId WHERE deviceId = :sourceDeviceId AND ts BETWEEN :fromTs AND :toTs",
    )
    suspend fun reassignInRange(sourceDeviceId: String, targetDeviceId: String, fromTs: Long, toTs: Long)
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE deviceId = :deviceId")
    suspend fun get(deviceId: String): SyncStateEntity?

    @Upsert
    suspend fun upsert(state: SyncStateEntity)

    @Query("DELETE FROM sync_state WHERE deviceId = :deviceId")
    suspend fun deleteForDevice(deviceId: String)
}

@Dao
interface AppStateDao {
    @Query("SELECT * FROM app_state WHERE id = 0")
    fun observe(): Flow<AppStateEntity?>

    @Query("SELECT * FROM app_state WHERE id = 0")
    suspend fun getOnce(): AppStateEntity?

    @Upsert
    suspend fun upsert(state: AppStateEntity)
}

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profile WHERE id = 0")
    fun observe(): Flow<UserProfileEntity?>

    @Upsert
    suspend fun upsert(profile: UserProfileEntity)
}

@OptIn(ExperimentalTime::class)
@Dao
interface CarDao {
    @Query("SELECT * FROM cars ORDER BY isFavorite DESC, createdAt ASC")
    fun observeAll(): Flow<List<CarEntity>>

    @Query("SELECT * FROM cars WHERE id = :carId")
    fun observe(carId: String): Flow<CarEntity?>

    @Query("SELECT * FROM cars WHERE id = :carId")
    suspend fun getById(carId: String): CarEntity?

    @Query("SELECT * FROM cars WHERE isFavorite = 1 LIMIT 1")
    fun observeFavorite(): Flow<CarEntity?>

    @Upsert
    suspend fun upsert(car: CarEntity)

    @Delete
    suspend fun delete(car: CarEntity)

    @Query("SELECT id FROM cars")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM cars WHERE cloudSyncedAt IS NULL OR updatedAt > cloudSyncedAt")
    suspend fun getDirty(): List<CarEntity>

    @Query("UPDATE cars SET cloudSyncedAt = :at WHERE id = :carId")
    suspend fun markSynced(carId: String, at: Long)

    @Query("UPDATE cars SET photoSyncedAt = :at WHERE id = :carId")
    suspend fun markPhotoSynced(carId: String, at: Long?)

    /** Sync markers are local flags, not account-scoped — a second account signing in on this
     * device must not inherit "already synced" from the first, or its own backup silently
     * omits every row the first account happened to have already pushed. */
    @Query("UPDATE cars SET cloudSyncedAt = NULL, photoSyncedAt = NULL")
    suspend fun clearSyncMarkers()

    @Query("UPDATE cars SET isFavorite = 0, updatedAt = :at WHERE isFavorite = 1")
    suspend fun clearFavorite(at: Long = Clock.System.now().toEpochMilliseconds())

    @Query("UPDATE cars SET isFavorite = 1, updatedAt = :at WHERE id = :carId")
    suspend fun markFavorite(carId: String, at: Long = Clock.System.now().toEpochMilliseconds())

    /** Clears any existing favorite before marking [carId], so at most one car is ever favorite. */
    @Transaction
    suspend fun setFavorite(carId: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        clearFavorite(now)
        markFavorite(carId, now)
    }
}

@Dao
interface CarModificationDao {
    @Query("SELECT * FROM car_modifications WHERE carId = :carId ORDER BY installedAt DESC")
    fun observeForCar(carId: String): Flow<List<CarModificationEntity>>

    /** Total modification count across every car, for [com.carlauncher.companion.data.repo.TrophyRepository]. */
    @Query("SELECT COUNT(*) FROM car_modifications")
    suspend fun countAll(): Int

    @Upsert
    suspend fun upsert(modification: CarModificationEntity)

    @Delete
    suspend fun delete(modification: CarModificationEntity)

    @Query("DELETE FROM car_modifications WHERE carId = :carId")
    suspend fun deleteAllForCar(carId: String)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY startTs DESC")
    fun observeAll(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE id = :eventId")
    fun observe(eventId: String): Flow<EventEntity?>

    @Query("SELECT * FROM events WHERE id = :eventId")
    suspend fun getById(eventId: String): EventEntity?

    @Upsert
    suspend fun upsert(event: EventEntity)

    @Delete
    suspend fun delete(event: EventEntity)

    @Query("SELECT id FROM events")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM events WHERE cloudSyncedAt IS NULL OR updatedAt > cloudSyncedAt")
    suspend fun getDirty(): List<EventEntity>

    @Query("UPDATE events SET cloudSyncedAt = :at WHERE id = :eventId")
    suspend fun markSynced(eventId: String, at: Long)

    @Query("UPDATE events SET cloudSyncedAt = NULL")
    suspend fun clearSyncMarkers()
}

@Dao
interface EventPointDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(points: List<EventPointEntity>)

    @Query("SELECT * FROM event_points WHERE eventId = :eventId ORDER BY ts ASC")
    fun observeForEvent(eventId: String): Flow<List<EventPointEntity>>

    @Query("SELECT * FROM event_points WHERE eventId = :eventId ORDER BY ts ASC")
    suspend fun getForEvent(eventId: String): List<EventPointEntity>

    @Query("DELETE FROM event_points WHERE eventId = :eventId")
    suspend fun deleteForEvent(eventId: String)
}

@Dao
interface TrophyDao {
    @Query("SELECT * FROM trophy_unlocks")
    fun observeUnlocks(): Flow<List<TrophyUnlockEntity>>

    @Query("SELECT * FROM trophy_unlocks")
    suspend fun getUnlocks(): List<TrophyUnlockEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUnlocks(unlocks: List<TrophyUnlockEntity>)

    /** Unlocks the in-app celebration popup hasn't shown yet — drives that popup's content. */
    @Query("SELECT * FROM trophy_unlocks WHERE seenAt IS NULL")
    fun observeUnseen(): Flow<List<TrophyUnlockEntity>>

    @Query("UPDATE trophy_unlocks SET seenAt = :seenAt WHERE id IN (:ids)")
    suspend fun markSeen(ids: List<String>, seenAt: Long)

    @Query("SELECT * FROM trophy_unlocks WHERE cloudSyncedAt IS NULL")
    suspend fun getDirtyUnlocks(): List<TrophyUnlockEntity>

    @Query("UPDATE trophy_unlocks SET cloudSyncedAt = :at WHERE id = :trophyId")
    suspend fun markUnlockSynced(trophyId: String, at: Long)

    @Query("UPDATE trophy_unlocks SET cloudSyncedAt = NULL")
    suspend fun clearSyncMarkers()

    @Query("SELECT * FROM trophy_progress WHERE id = 0")
    fun observeProgress(): Flow<TrophyProgressEntity?>

    @Query("SELECT * FROM trophy_progress WHERE id = 0")
    suspend fun getProgress(): TrophyProgressEntity?

    @Upsert
    suspend fun upsertProgress(progress: TrophyProgressEntity)
}

@Dao
interface XpStateDao {
    @Query("SELECT * FROM xp_state WHERE id = 0")
    fun observe(): Flow<XpStateEntity?>

    @Query("SELECT * FROM xp_state WHERE id = 0")
    suspend fun get(): XpStateEntity?

    @Upsert
    suspend fun upsert(state: XpStateEntity)
}

@Dao
interface CloudPrefsDao {
    @Query("SELECT * FROM cloud_prefs WHERE id = 0")
    fun observe(): Flow<CloudPrefsEntity?>

    @Query("SELECT * FROM cloud_prefs WHERE id = 0")
    suspend fun get(): CloudPrefsEntity?

    @Upsert
    suspend fun upsert(prefs: CloudPrefsEntity)

    /**
     * Forgets every trace of the cloud on sign-out: all upload switches off, sharing back to
     * private, and — critically — the GPS/stats backup cursors reset too. Those cursors are
     * keyed by local deviceId, not by account; left in place, a second account signing in on
     * this same phone would inherit them and silently skip everything before that watermark
     * in its own backup.
     */
    @Query(
        "UPDATE cloud_prefs SET uploadCars = 0, uploadEvents = 0, uploadProfile = 0, " +
            "uploadGpsHistory = 0, uploadStats = 0, uploadTrophies = 0, " +
            "visibility = 'private', lastSyncAt = NULL, " +
            "gpsCursorsJson = '{}', gpsNextChunkIndex = 0, statsLastSyncedComputedAt = NULL " +
            "WHERE id = 0",
    )
    suspend fun resetOnSignOut()
}
