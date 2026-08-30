package com.carlauncher.companion.data.db

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    val addedAt: Long,
    val brand: String? = null,
    val model: String? = null,
    val details: String? = null,
    /** True only for the synthetic "This phone" row seeded by [com.carlauncher.companion.data.repo.DeviceRepository.ensureLocalDeviceExists] — not deletable, not from Firestore. */
    val isLocal: Boolean = false,
)

@Entity(
    tableName = "location_points",
    indices = [Index(value = ["deviceId", "ts"], unique = true)],
)
data class LocationPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val lat: Double,
    val lng: Double,
    val ts: Long,
    val speedKmh: Int,
    val pushedAtMillis: Long,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val deviceId: String,
    val lastSyncedPushedAtMillis: Long,
)

@Entity(tableName = "app_state")
data class AppStateEntity(
    @PrimaryKey val id: Int = 0,
    val selectedDeviceId: String?,
    val localRecordingActive: Boolean = false,
    val selectedRange: String = "LAST_7_DAYS",
)

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int = 0,
    val age: Int? = null,
    val city: String? = null,
    /** Comma-separated INSEE department codes, e.g. "75,92,78". */
    val departmentCodes: String? = null,
)

/** A car the user owns, independent of the GPS-tracked [DeviceEntity] list — optionally linked to one. */
@Entity(tableName = "cars")
data class CarEntity(
    @PrimaryKey val id: String,
    val deviceId: String? = null,
    val name: String,
    val brand: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val details: String? = null,
    val photoPath: String? = null,
    val odometerKm: Double? = null,
    val createdAt: Long,
    /** At most one car is favorite at a time — enforced by [CarDao.setFavorite], not a DB constraint. */
    val isFavorite: Boolean = false,
    /** Opted into the community Feed. Combines with the account's global visibility level. */
    val isShared: Boolean = false,
    val updatedAt: Long = 0,
    /** [updatedAt] at the last successful cloud push; `null` means never uploaded. */
    val cloudSyncedAt: Long? = null,
    /** Bumped whenever [photoPath] changes locally (set or cleared). Drives cloud photo sync
     * independently of [cloudSyncedAt] — the row can be dirty for unrelated reasons without
     * forcing a re-upload of unchanged photo bytes. */
    val photoUpdatedAt: Long? = null,
    /** [photoUpdatedAt] at the last successful photo push to the `car-photos` bucket; `null`
     * means either never uploaded or currently photo-less on the server. In sync when equal
     * to [photoUpdatedAt] (including both null). */
    val photoSyncedAt: Long? = null,
)

@Entity(
    tableName = "car_modifications",
    indices = [Index(value = ["carId"])],
)
data class CarModificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val carId: String,
    val title: String,
    val category: String? = null,
    val installedAt: Long,
    val cost: Double? = null,
    val notes: String? = null,
)

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** [com.carlauncher.companion.data.model.EventType] name. */
    val type: String,
    val carId: String? = null,
    /** Snapshotted from the car at creation time, so re-linking the car later doesn't retroactively change what the event's points came from. */
    val deviceId: String? = null,
    val startTs: Long,
    val endTs: Long,
    val locationLabel: String? = null,
    val notes: String? = null,
    val createdAt: Long,
    /** "DEVICE" or "GPX" — which crop path [com.carlauncher.companion.data.repo.EventRepository]
     *  used, so editing metadata alone never blindly re-crops from [deviceId] over an imported track. */
    val pointsSource: String = "DEVICE",
    /**
     * Opted into the community Feed. This is the *only* switch that lets any GPS data leave
     * the device for other people to see — [EventPointEntity] rows for a shared event become
     * a cloud trace. Global history and statistics are never shareable by any path.
     */
    val isShared: Boolean = false,
    val updatedAt: Long = 0,
    /** [updatedAt] at the last successful cloud push; `null` means never uploaded. */
    val cloudSyncedAt: Long? = null,
)

/**
 * A permanent, independent copy of the [LocationPointEntity] rows covered by an event's time
 * window — cropped at event-creation time so it survives later History-screen deletions of the
 * original points.
 */
@Entity(
    tableName = "event_points",
    indices = [Index(value = ["eventId"])],
)
data class EventPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val lat: Double,
    val lng: Double,
    val ts: Long,
    val speedKmh: Int,
)

/**
 * One row per earned [com.carlauncher.companion.data.model.Trophy], keyed by the enum name.
 * Only the unlock moment is stored — the definitions and thresholds live in Kotlin, so
 * adding a trophy needs no migration.
 */
@Entity(tableName = "trophy_unlocks")
data class TrophyUnlockEntity(
    @PrimaryKey val id: String,
    val unlockedAt: Long,
    /** Null until the in-app celebration popup has been shown and dismissed for this trophy. */
    val seenAt: Long? = null,
    /** Null means never pushed to the cloud. Unlocks are permanent, so this is set-once. */
    val cloudSyncedAt: Long? = null,
)

/**
 * Cached [com.carlauncher.companion.data.model.TrophyStats] snapshot (singleton row, id = 0)
 * so the Trophies screen paints immediately instead of rescanning every point on open.
 */
@Entity(tableName = "trophy_progress")
data class TrophyProgressEntity(
    @PrimaryKey val id: Int = 0,
    val totalDistanceKm: Double = 0.0,
    val longestTripKm: Double = 0.0,
    val maxSpeedKmh: Int = 0,
    val totalMovingSeconds: Long = 0,
    val tripCount: Int = 0,
    val nightTripCount: Int = 0,
    val earlyTripCount: Int = 0,
    val distinctDrivingDays: Int = 0,
    val bestStreakDays: Int = 0,
    val currentStreakDays: Int = 0,
    val seasonsDriven: Int = 0,
    /** Comma-separated INSEE codes, same encoding as [UserProfileEntity.departmentCodes]. */
    val departmentCodes: String = "",
    val mapSquaresVisited: Int = 0,
    val maxDistanceFromBaseKm: Double = 0.0,
    val carCount: Int = 0,
    val modificationCount: Int = 0,
    val eventCount: Int = 0,
    val gpxImportCount: Int = 0,
    val profileFieldsSet: Int = 0,
    val computedAt: Long = 0,
)

/**
 * Singleton row (id = 0) tracking the login-XP streak. Only the streak needs persisting —
 * every other XP source ([com.carlauncher.companion.data.repo.computeBaseXp]) is a pure
 * function of [TrophyProgressEntity]/[TrophyUnlockEntity], recomputed rather than stored.
 * [accumulatedLoginXp] can't be recomputed the same way: today's bonus depends on whether
 * yesterday was played, not on any stat that survives independently of this row.
 */
@Entity(tableName = "xp_state")
data class XpStateEntity(
    @PrimaryKey val id: Int = 0,
    val currentStreakDays: Int = 0,
    val bestStreakDays: Int = 0,
    /** `LocalDate.toEpochDays()` of the last day a login bonus was credited; null before the
     * first ever app open. */
    val lastLoginEpochDay: Long? = null,
    val accumulatedLoginXp: Long = 0,
)

/**
 * Singleton row holding what this device is willing to send to the cloud, and how widely the
 * account shares.
 *
 * These preferences stay **local on purpose**. The server has no business knowing which
 * categories a user declined to upload — the absence of the data says everything it needs to
 * know, and storing the choice remotely would only create a record of what someone chose to
 * withhold.
 *
 * Note the two axes are independent: `upload*` decides what is *backed up*, `visibility`
 * decides what is *shared*. Uploading GPS history never makes it visible to anyone — that
 * data is end-to-end encrypted and has no sharing path at all.
 */
@Entity(tableName = "cloud_prefs")
data class CloudPrefsEntity(
    @PrimaryKey val id: Int = 0,

    val uploadCars: Boolean = false,
    val uploadEvents: Boolean = false,
    val uploadProfile: Boolean = false,
    /** End-to-end encrypted when uploaded. Never shareable. */
    val uploadGpsHistory: Boolean = false,
    /** End-to-end encrypted when uploaded. Never shareable. */
    val uploadStats: Boolean = false,
    val uploadTrophies: Boolean = false,

    /** "private" | "friends" | "public" — mirrors `profiles.visibility` in Postgres. */
    val visibility: String = "private",
    /** "friends" | "everyone" — whose activity this user wants in their own Feed. */
    val feedScope: String = "friends",
    /** "private" | "friends" | "public" — mirrors `profiles.leaderboard_visibility`. Independent
     * of [visibility]: a user can keep GPS/events private while still competing on the
     * leaderboard, or vice versa. */
    val leaderboardVisibility: String = "private",

    // Independent of `visibility`/per-item `isShared`: these three gate which *sections* of
    // the public profile page exist at all, mirroring `profiles.share_profile`/`share_garage`/
    // `share_trophies`. All off by default — a friend seeing "nothing shared" is what an
    // account looks like until its owner deliberately turns one of these on.
    val shareProfileInfo: Boolean = false,
    val shareGarageSection: Boolean = false,
    val shareTrophiesSection: Boolean = false,

    /** `BuildConfig.TERMS_VERSION` the user last accepted; null means never. */
    val acceptedTermsVersion: String? = null,
    val lastSyncAt: Long? = null,

    /**
     * Per-device GPS backup watermark: `{"deviceId": lastPushedTs}`, JSON-encoded rather than
     * a separate table since it's a handful of entries at most. GPS points are append-only, so
     * a single per-device high-water mark is enough to resume — nothing is ever re-pushed.
     */
    val gpsCursorsJson: String = "{}",
    /** Global counter for `private_backups.chunk_index`, shared across every device's chunks. */
    val gpsNextChunkIndex: Int = 0,
    /** `trophy_progress.computedAt` at the last successful stats push; null means never. */
    val statsLastSyncedComputedAt: Long? = null,
)
