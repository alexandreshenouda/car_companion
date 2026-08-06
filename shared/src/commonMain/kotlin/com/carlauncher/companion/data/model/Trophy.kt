package com.carlauncher.companion.data.model

/**
 * Rarity band. Purely cosmetic — it drives the medal colour and the sort order inside a
 * category, so a screenful of trophies reads as a ladder rather than a flat grid. Label/colour
 * live as extension properties in `:app` (`TrophyUi.kt`) since Android string resources and
 * Compose `Color` aren't reachable from this module — iOS's own UI supplies its own.
 */
enum class TrophyTier { BRONZE, SILVER, GOLD, PLATINUM }

enum class TrophyCategory { DISTANCE, HABIT, EXPLORATION, COLLECTION }

/** What a trophy's progress is measured in — purely an identity for the UI to localize a unit
 * string from, never used in the unlock math itself. */
enum class TrophyUnit {
    TRIP, TRIPS, KM, KMH, DAYS, SEASONS, HOURS, DEPARTEMENTS, SQUARES, CAR, CARS, MODS, EVENTS, IMPORT, FIELDS
}

/** Result of [Trophy.progressLabel] — localization of [unit] happens at the call site. */
data class TrophyProgress(val current: Int, val target: Int, val unit: TrophyUnit)

/**
 * The full trophy catalogue. Definitions live in Kotlin and only the unlock timestamp is
 * persisted (`trophy_unlocks`), so adding a trophy needs no schema migration — the same
 * "enum name stored as TEXT" convention `EventType` (in `:app`, since nothing in the data
 * layer needs it) already uses.
 *
 * [value] pulls the current amount out of a [TrophyStats] snapshot and [target] is what
 * it has to reach; progress is simply the ratio, which is what the locked tiles show.
 *
 * Icon/title/description string resources live in `:app`'s `TrophyUi.kt` — see the doc
 * comment on [TrophyTier] for why they can't live here.
 */
enum class Trophy(
    val category: TrophyCategory,
    val tier: TrophyTier,
    val target: Double,
    val unit: TrophyUnit,
    val value: (TrophyStats) -> Double,
) {
    // ---- Distance & speed ----
    FIRST_LIGHT(TrophyCategory.DISTANCE, TrophyTier.BRONZE, 1.0, TrophyUnit.TRIP, { it.tripCount.toDouble() }),
    CENTURY(TrophyCategory.DISTANCE, TrophyTier.BRONZE, 100.0, TrophyUnit.KM, { it.totalDistanceKm }),
    ROAD_WARRIOR(TrophyCategory.DISTANCE, TrophyTier.SILVER, 1_000.0, TrophyUnit.KM, { it.totalDistanceKm }),
    CONTINENTAL(TrophyCategory.DISTANCE, TrophyTier.GOLD, 10_000.0, TrophyUnit.KM, { it.totalDistanceKm }),
    LONG_HAUL(TrophyCategory.DISTANCE, TrophyTier.GOLD, 200.0, TrophyUnit.KM, { it.longestTripKm }),
    TON_UP(TrophyCategory.DISTANCE, TrophyTier.SILVER, 130.0, TrophyUnit.KMH, { it.maxSpeedKmh.toDouble() }),
    REDLINE(TrophyCategory.DISTANCE, TrophyTier.PLATINUM, 200.0, TrophyUnit.KMH, { it.maxSpeedKmh.toDouble() }),
    AUTOBAHN(TrophyCategory.DISTANCE, TrophyTier.PLATINUM, 300.0, TrophyUnit.KMH, { it.maxSpeedKmh.toDouble() }),
    TERMINAL_VELOCITY(TrophyCategory.DISTANCE, TrophyTier.PLATINUM, 400.0, TrophyUnit.KMH, { it.maxSpeedKmh.toDouble() }),

    // ---- Habits & streaks ----
    COMMUTER(TrophyCategory.HABIT, TrophyTier.BRONZE, 10.0, TrophyUnit.TRIPS, { it.tripCount.toDouble() }),
    REGULAR(TrophyCategory.HABIT, TrophyTier.SILVER, 50.0, TrophyUnit.TRIPS, { it.tripCount.toDouble() }),
    THREE_IN_A_ROW(TrophyCategory.HABIT, TrophyTier.BRONZE, 3.0, TrophyUnit.DAYS, { it.bestStreakDays.toDouble() }),
    WEEK_STREAK(TrophyCategory.HABIT, TrophyTier.SILVER, 7.0, TrophyUnit.DAYS, { it.bestStreakDays.toDouble() }),
    MONTH_STREAK(TrophyCategory.HABIT, TrophyTier.PLATINUM, 30.0, TrophyUnit.DAYS, { it.bestStreakDays.toDouble() }),
    NIGHT_OWL(TrophyCategory.HABIT, TrophyTier.SILVER, 10.0, TrophyUnit.TRIPS, { it.nightTripCount.toDouble() }),
    EARLY_BIRD(TrophyCategory.HABIT, TrophyTier.SILVER, 10.0, TrophyUnit.TRIPS, { it.earlyTripCount.toDouble() }),
    FOUR_SEASONS(TrophyCategory.HABIT, TrophyTier.GOLD, 4.0, TrophyUnit.SEASONS, { it.seasonsDriven.toDouble() }),
    ENDURANCE(TrophyCategory.HABIT, TrophyTier.GOLD, 24.0, TrophyUnit.HOURS, { it.totalMovingSeconds / 3600.0 }),

    // ---- Exploration ----
    TOURIST(TrophyCategory.EXPLORATION, TrophyTier.BRONZE, 5.0, TrophyUnit.DEPARTEMENTS, { it.departmentsVisited.toDouble() }),
    EXPLORER(TrophyCategory.EXPLORATION, TrophyTier.SILVER, 20.0, TrophyUnit.DEPARTEMENTS, { it.departmentsVisited.toDouble() }),
    CARTOGRAPHER(TrophyCategory.EXPLORATION, TrophyTier.PLATINUM, 50.0, TrophyUnit.DEPARTEMENTS, { it.departmentsVisited.toDouble() }),
    FAR_FROM_HOME(TrophyCategory.EXPLORATION, TrophyTier.GOLD, 300.0, TrophyUnit.KM, { it.maxDistanceFromBaseKm }),
    GRID_RUNNER(TrophyCategory.EXPLORATION, TrophyTier.SILVER, 100.0, TrophyUnit.SQUARES, { it.mapSquaresVisited.toDouble() }),

    // ---- Collection ----
    KEYS_IN_HAND(TrophyCategory.COLLECTION, TrophyTier.BRONZE, 1.0, TrophyUnit.CAR, { it.carCount.toDouble() }),
    COLLECTOR(TrophyCategory.COLLECTION, TrophyTier.GOLD, 3.0, TrophyUnit.CARS, { it.carCount.toDouble() }),
    TUNER(TrophyCategory.COLLECTION, TrophyTier.SILVER, 5.0, TrophyUnit.MODS, { it.modificationCount.toDouble() }),
    EVENT_HORIZON(TrophyCategory.COLLECTION, TrophyTier.SILVER, 5.0, TrophyUnit.EVENTS, { it.eventCount.toDouble() }),
    ARCHIVIST(TrophyCategory.COLLECTION, TrophyTier.BRONZE, 1.0, TrophyUnit.IMPORT, { it.gpxImportCount.toDouble() }),
    FULL_PROFILE(TrophyCategory.COLLECTION, TrophyTier.BRONZE, 3.0, TrophyUnit.FIELDS, { it.profileFieldsSet.toDouble() }),
    ;

    /** 0f..1f, clamped — what the locked tiles render as a bar. */
    fun progress(stats: TrophyStats): Float =
        (value(stats) / target).coerceIn(0.0, 1.0).toFloat()

    fun isUnlocked(stats: TrophyStats): Boolean = value(stats) >= target

    /** "340 / 1000 km" — deliberately integer, decimals add noise at this size. Localized at
     * the call site (see `Trophy.unit`'s `labelRes` extension in `:app`'s `TrophyUi.kt`). */
    fun progressLabel(stats: TrophyStats): TrophyProgress {
        val current = value(stats).coerceAtMost(target)
        return TrophyProgress(current.toInt(), target.toInt(), unit)
    }
}
