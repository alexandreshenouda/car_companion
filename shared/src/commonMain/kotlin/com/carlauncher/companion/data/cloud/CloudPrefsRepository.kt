package com.carlauncher.companion.data.cloud

import com.carlauncher.companion.data.db.CloudPrefsDao
import com.carlauncher.companion.data.db.CloudPrefsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What the account shares with other people. Mirrors `profiles.visibility` in Postgres.
 * UI label/description string resources live alongside each Compose screen in `:app` (see
 * `CloudPrefsLabels.kt`) since Android string resources aren't reachable from this module. */
enum class Visibility(val wire: String) {
    PRIVATE("private"),
    FRIENDS("friends"),
    PUBLIC("public"),
    ;

    companion object {
        fun from(wire: String?) = entries.firstOrNull { it.wire == wire } ?: PRIVATE
    }
}

/** Whose activity appears in this user's own Feed. */
enum class FeedScope(val wire: String) {
    FRIENDS("friends"),
    EVERYONE("everyone"),
    ;

    companion object {
        fun from(wire: String?) = entries.firstOrNull { it.wire == wire } ?: FRIENDS
    }
}

/** Who can see this account on the XP leaderboard. Deliberately its own setting rather than
 * reusing [Visibility] — a user may want GPS/events private but still compete, or vice versa.
 * Mirrors `profiles.leaderboard_visibility` in Postgres. */
enum class LeaderboardVisibility(val wire: String) {
    PRIVATE("private"),
    FRIENDS("friends"),
    PUBLIC("public"),
    ;

    companion object {
        fun from(wire: String?) = entries.firstOrNull { it.wire == wire } ?: PRIVATE
    }
}

/** The six independent upload switches, as a category enum so the UI can render them generically. */
enum class SyncCategory(val encrypted: Boolean) {
    CARS(false),
    EVENTS(false),
    PROFILE(false),
    GPS_HISTORY(true),
    STATISTICS(true),
    TROPHIES(false),
}

/**
 * Which sections of the *public profile page* exist for other people to see at all — a third,
 * independent axis from [SyncCategory] (does it leave the phone?) and [Visibility] (who can
 * see items marked shared?). All off by default: a friend visiting a fresh account's profile
 * sees nothing until its owner turns one of these on, even if the underlying data is already
 * backed up and even at [Visibility.PUBLIC].
 */
enum class ProfileSection(val requires: SyncCategory) {
    PERSONAL_INFO(SyncCategory.PROFILE),
    GARAGE(SyncCategory.CARS),
    TROPHIES(SyncCategory.TROPHIES),
}

/**
 * The local record of what this device uploads and how widely the account shares.
 *
 * Two independent axes, and conflating them is the easiest mistake to make here:
 *
 *  - `upload*` — does this leave the phone at all (backup)?
 *  - [Visibility] — of what leaves, what may other people see (sharing)?
 *
 * Turning on GPS-history upload never makes it visible to anyone. It is end-to-end encrypted
 * and there is deliberately no code path, and no RLS policy, that could expose it.
 */
class CloudPrefsRepository(private val dao: CloudPrefsDao) {

    val prefs: Flow<CloudPrefsEntity> = dao.observe().map { it ?: CloudPrefsEntity() }

    val visibility: Flow<Visibility> = prefs.map { Visibility.from(it.visibility) }

    val leaderboardVisibility: Flow<LeaderboardVisibility> = prefs.map { LeaderboardVisibility.from(it.leaderboardVisibility) }

    suspend fun current(): CloudPrefsEntity = dao.get() ?: CloudPrefsEntity()

    suspend fun isEnabled(category: SyncCategory): Boolean = current().isEnabled(category)

    suspend fun setEnabled(category: SyncCategory, enabled: Boolean) {
        dao.upsert(current().withEnabled(category, enabled))
    }

    suspend fun setVisibility(visibility: Visibility) {
        dao.upsert(current().copy(visibility = visibility.wire))
    }

    suspend fun setFeedScope(scope: FeedScope) {
        dao.upsert(current().copy(feedScope = scope.wire))
    }

    suspend fun setLeaderboardVisibility(visibility: LeaderboardVisibility) {
        dao.upsert(current().copy(leaderboardVisibility = visibility.wire))
    }

    suspend fun isEnabled(section: ProfileSection): Boolean = current().isEnabled(section)

    /**
     * Sharing a section with nothing backed up to share is a state that can never show
     * anything — confusing, and indistinguishable from a bug. So turning a section ON also
     * turns on the backup category it depends on, if it wasn't already; turning it off only
     * hides the section and leaves backup exactly as it was (someone might still want the
     * backup without the public section).
     */
    suspend fun setEnabled(section: ProfileSection, enabled: Boolean) {
        val p = current()
        val withSection = when (section) {
            ProfileSection.PERSONAL_INFO -> p.copy(shareProfileInfo = enabled)
            ProfileSection.GARAGE -> p.copy(shareGarageSection = enabled)
            ProfileSection.TROPHIES -> p.copy(shareTrophiesSection = enabled)
        }
        dao.upsert(if (enabled) withSection.withEnabled(section.requires, true) else withSection)
    }

    suspend fun recordTermsAccepted(version: String) {
        dao.upsert(current().copy(acceptedTermsVersion = version))
    }

    suspend fun recordSync(at: Long) {
        dao.upsert(current().copy(lastSyncAt = at))
    }

    /**
     * Persisted after every single GPS chunk upload, not just at the end of a sync pass — so
     * an interrupted run (network drop, app killed) resumes from where it left off instead of
     * re-uploading chunks already sent.
     */
    suspend fun recordGpsProgress(cursorsJson: String, nextChunkIndex: Int) {
        dao.upsert(current().copy(gpsCursorsJson = cursorsJson, gpsNextChunkIndex = nextChunkIndex))
    }

    suspend fun recordStatsSynced(computedAt: Long) {
        dao.upsert(current().copy(statsLastSyncedComputedAt = computedAt))
    }

    suspend fun resetOnSignOut() {
        // Ensure a row exists first — resetOnSignOut() is an UPDATE and would silently do
        // nothing on a device that signed in before ever writing preferences.
        dao.upsert(current())
        dao.resetOnSignOut()
    }
}

fun CloudPrefsEntity.isEnabled(category: SyncCategory): Boolean = when (category) {
    SyncCategory.CARS -> uploadCars
    SyncCategory.EVENTS -> uploadEvents
    SyncCategory.PROFILE -> uploadProfile
    SyncCategory.GPS_HISTORY -> uploadGpsHistory
    SyncCategory.STATISTICS -> uploadStats
    SyncCategory.TROPHIES -> uploadTrophies
}

fun CloudPrefsEntity.withEnabled(category: SyncCategory, enabled: Boolean): CloudPrefsEntity = when (category) {
    SyncCategory.CARS -> copy(uploadCars = enabled)
    SyncCategory.EVENTS -> copy(uploadEvents = enabled)
    SyncCategory.PROFILE -> copy(uploadProfile = enabled)
    SyncCategory.GPS_HISTORY -> copy(uploadGpsHistory = enabled)
    SyncCategory.STATISTICS -> copy(uploadStats = enabled)
    SyncCategory.TROPHIES -> copy(uploadTrophies = enabled)
}

fun CloudPrefsEntity.isEnabled(section: ProfileSection): Boolean = when (section) {
    ProfileSection.PERSONAL_INFO -> shareProfileInfo
    ProfileSection.GARAGE -> shareGarageSection
    ProfileSection.TROPHIES -> shareTrophiesSection
}
