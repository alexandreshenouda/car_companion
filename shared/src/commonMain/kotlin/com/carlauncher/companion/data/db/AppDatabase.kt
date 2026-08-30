package com.carlauncher.companion.data.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [
        DeviceEntity::class,
        LocationPointEntity::class,
        SyncStateEntity::class,
        AppStateEntity::class,
        UserProfileEntity::class,
        CarEntity::class,
        CarModificationEntity::class,
        EventEntity::class,
        EventPointEntity::class,
        TrophyUnlockEntity::class,
        TrophyProgressEntity::class,
        CloudPrefsEntity::class,
        XpStateEntity::class,
    ],
    version = 14,
    exportSchema = false,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun locationPointDao(): LocationPointDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun appStateDao(): AppStateDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun carDao(): CarDao
    abstract fun carModificationDao(): CarModificationDao
    abstract fun eventDao(): EventDao
    abstract fun eventPointDao(): EventPointDao
    abstract fun trophyDao(): TrophyDao
    abstract fun cloudPrefsDao(): CloudPrefsDao
    abstract fun xpStateDao(): XpStateDao
}

/** Adds free-text brand/model/details for a car, set from the Devices screen. */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE devices ADD COLUMN brand TEXT")
        connection.execSQL("ALTER TABLE devices ADD COLUMN model TEXT")
        connection.execSQL("ALTER TABLE devices ADD COLUMN details TEXT")
    }
}

/** Adds the Profile tab's tables: user profile, garage cars + modifications, events + their cropped GPS points. */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_profile` (`id` INTEGER NOT NULL, `age` INTEGER, `city` TEXT, `departmentCodes` TEXT, PRIMARY KEY(`id`))",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `cars` (`id` TEXT NOT NULL, `deviceId` TEXT, `name` TEXT NOT NULL, `brand` TEXT, `model` TEXT, `year` INTEGER, `details` TEXT, `photoPath` TEXT, `odometerKm` REAL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `car_modifications` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `carId` TEXT NOT NULL, `title` TEXT NOT NULL, `category` TEXT, `installedAt` INTEGER NOT NULL, `cost` REAL, `notes` TEXT)",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_car_modifications_carId` ON `car_modifications` (`carId`)")
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `events` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `type` TEXT NOT NULL, `carId` TEXT, `deviceId` TEXT, `startTs` INTEGER NOT NULL, `endTs` INTEGER NOT NULL, `locationLabel` TEXT, `notes` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `event_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `eventId` TEXT NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, `ts` INTEGER NOT NULL, `speedKmh` INTEGER NOT NULL)",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_event_points_eventId` ON `event_points` (`eventId`)")
    }
}

/** Adds the Garage "favorite car" toggle shown on the Profile screen. */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE cars ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
    }
}

/** Adds GPX-imported events: which crop path an event used, so metadata-only edits don't re-crop over an imported track. */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE events ADD COLUMN pointsSource TEXT NOT NULL DEFAULT 'DEVICE'")
    }
}

/** Adds the synthetic "This phone" device flag and its persisted recording-active state. */
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE devices ADD COLUMN isLocal INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE app_state ADD COLUMN localRecordingActive INTEGER NOT NULL DEFAULT 0")
    }
}

/** Adds the Trophies system: earned-trophy log plus the cached lifetime snapshot. */
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `trophy_unlocks` (`id` TEXT NOT NULL, `unlockedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `trophy_progress` (`id` INTEGER NOT NULL, `totalDistanceKm` REAL NOT NULL, `longestTripKm` REAL NOT NULL, `maxSpeedKmh` INTEGER NOT NULL, `totalMovingSeconds` INTEGER NOT NULL, `tripCount` INTEGER NOT NULL, `nightTripCount` INTEGER NOT NULL, `earlyTripCount` INTEGER NOT NULL, `distinctDrivingDays` INTEGER NOT NULL, `bestStreakDays` INTEGER NOT NULL, `currentStreakDays` INTEGER NOT NULL, `seasonsDriven` INTEGER NOT NULL, `departmentCodes` TEXT NOT NULL, `mapSquaresVisited` INTEGER NOT NULL, `maxDistanceFromBaseKm` REAL NOT NULL, `carCount` INTEGER NOT NULL, `modificationCount` INTEGER NOT NULL, `eventCount` INTEGER NOT NULL, `gpxImportCount` INTEGER NOT NULL, `profileFieldsSet` INTEGER NOT NULL, `computedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
    }
}

/**
 * Adds the "seen" watermark that drives the in-app trophy-unlocked popup. Existing
 * unlocks are backfilled as already seen — they were already delivered through the
 * system notification in a prior version, so treating them as new here would dump
 * the user's whole trophy history into one popup on next launch.
 */
private val MIGRATION_7_8 = object : Migration(7, 8) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE trophy_unlocks ADD COLUMN seenAt INTEGER")
        connection.execSQL("UPDATE trophy_unlocks SET seenAt = unlockedAt")
    }
}

/**
 * Adds Supabase cloud sync: the local-only preference row, and per-item share/dirty
 * tracking on the two entities that can be published to the Feed.
 *
 * Everything defaults to off. An existing install must stay exactly as private after
 * this migration as it was before it — opting in is always an explicit user action.
 */
private val MIGRATION_8_9 = object : Migration(8, 9) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `cloud_prefs` (`id` INTEGER NOT NULL, " +
                "`uploadCars` INTEGER NOT NULL DEFAULT 0, `uploadEvents` INTEGER NOT NULL DEFAULT 0, " +
                "`uploadProfile` INTEGER NOT NULL DEFAULT 0, `uploadGpsHistory` INTEGER NOT NULL DEFAULT 0, " +
                "`uploadStats` INTEGER NOT NULL DEFAULT 0, `uploadTrophies` INTEGER NOT NULL DEFAULT 0, " +
                "`visibility` TEXT NOT NULL DEFAULT 'private', `feedScope` TEXT NOT NULL DEFAULT 'friends', " +
                "`acceptedTermsVersion` TEXT, `lastSyncAt` INTEGER, PRIMARY KEY(`id`))",
        )
        connection.execSQL("ALTER TABLE cars ADD COLUMN isShared INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE cars ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE cars ADD COLUMN cloudSyncedAt INTEGER")
        connection.execSQL("ALTER TABLE events ADD COLUMN isShared INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE events ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE events ADD COLUMN cloudSyncedAt INTEGER")
        // Seed updatedAt from creation time so the first sync has a sane ordering
        // rather than treating every pre-existing row as timestamp zero.
        connection.execSQL("UPDATE cars SET updatedAt = createdAt")
        connection.execSQL("UPDATE events SET updatedAt = createdAt")
    }
}

/**
 * Adds the bookkeeping the cloud backup/sync engine needs: a set-once sync marker on
 * trophy unlocks (they're permanent once written, so "synced" is a one-way flip), and
 * the GPS/stats backup watermarks on the singleton prefs row.
 */
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE trophy_unlocks ADD COLUMN cloudSyncedAt INTEGER")
        connection.execSQL("ALTER TABLE cloud_prefs ADD COLUMN gpsCursorsJson TEXT NOT NULL DEFAULT '{}'")
        connection.execSQL("ALTER TABLE cloud_prefs ADD COLUMN gpsNextChunkIndex INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE cloud_prefs ADD COLUMN statsLastSyncedComputedAt INTEGER")
    }
}

/** Adds the three public-profile section toggles (`share_profile`/`share_garage`/
 * `share_trophies` in Postgres) that were missing local storage entirely — there was
 * no way to ever turn them on. */
private val MIGRATION_10_11 = object : Migration(10, 11) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE cloud_prefs ADD COLUMN shareProfileInfo INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE cloud_prefs ADD COLUMN shareGarageSection INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE cloud_prefs ADD COLUMN shareTrophiesSection INTEGER NOT NULL DEFAULT 0")
    }
}

/** Cars whose photo predates this migration get `photoUpdatedAt` stamped with the migration's
 * own run time (rather than left null) so they're immediately "dirty" against a still-null
 * `photoSyncedAt` — otherwise a photo set before this update shipped would never sync to the
 * `car-photos` bucket until the user touched it again. */
private val MIGRATION_11_12 = object : Migration(11, 12) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE cars ADD COLUMN photoUpdatedAt INTEGER")
        connection.execSQL("ALTER TABLE cars ADD COLUMN photoSyncedAt INTEGER")
        connection.execSQL("UPDATE cars SET photoUpdatedAt = 1786087282699 WHERE photoPath IS NOT NULL")
    }
}

/** Adds the XP mechanism: a singleton login-streak state table, and the leaderboard's own
 * independent visibility toggle alongside the existing `visibility`/`feedScope` settings. */
private val MIGRATION_12_13 = object : Migration(12, 13) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `xp_state` (`id` INTEGER NOT NULL, `currentStreakDays` INTEGER NOT NULL, " +
                "`bestStreakDays` INTEGER NOT NULL, `lastLoginEpochDay` INTEGER, `accumulatedLoginXp` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        connection.execSQL("ALTER TABLE cloud_prefs ADD COLUMN leaderboardVisibility TEXT NOT NULL DEFAULT 'private'")
    }
}

/** Adds the persisted time range selection shared across map/history/stats. */
private val MIGRATION_13_14 = object : Migration(13, 14) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE app_state ADD COLUMN selectedRange TEXT NOT NULL DEFAULT 'LAST_7_DAYS'")
    }
}

internal val APP_DATABASE_MIGRATIONS = arrayOf(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
    MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
)

/** Room's KSP compiler generates the `actual` for each target (Android, iOS) — required by
 * Room3/KMP for any non-JVM-reflection platform, hence needed even though this project has
 * no other constructor-injection use for it. */
@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/** Finishes a platform-supplied [RoomDatabase.Builder] (see `getDatabaseBuilder` in
 * androidMain/iosMain) with the driver, migrations, and coroutine dispatcher common to both.
 * [Dispatchers.Default], not `.IO`: kotlinx-coroutines-core 1.8.1's `Dispatchers.IO` isn't a
 * public symbol on the Native target (only on JVM) — confirmed by a real iOS compile, see
 * README's "iOS port" section. */
fun getRoomDatabase(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase =
    builder
        .addMigrations(*APP_DATABASE_MIGRATIONS)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
