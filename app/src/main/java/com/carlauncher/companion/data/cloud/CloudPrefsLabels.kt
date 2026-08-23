package com.carlauncher.companion.data.cloud

import androidx.annotation.StringRes
import com.carlauncher.companion.R

/**
 * Android string-resource labels for the [Visibility]/[FeedScope]/[SyncCategory]/
 * [ProfileSection] enums, which themselves live in `:shared` (they're referenced from the
 * cloud sync/restore logic that also runs on iOS). Kept as extension properties in the same
 * package rather than on the enums directly, since `androidx.annotation`/`R` aren't reachable
 * from a KMP module — iOS's own UI supplies its own localized strings for these instead.
 */

@get:StringRes
val Visibility.labelRes: Int
    get() = when (this) {
        Visibility.PRIVATE -> R.string.visibility_private_label
        Visibility.FRIENDS -> R.string.visibility_friends_label
        Visibility.PUBLIC -> R.string.visibility_public_label
    }

@get:StringRes
val Visibility.descriptionRes: Int
    get() = when (this) {
        Visibility.PRIVATE -> R.string.visibility_private_desc
        Visibility.FRIENDS -> R.string.visibility_friends_desc
        Visibility.PUBLIC -> R.string.visibility_public_desc
    }

@get:StringRes
val FeedScope.labelRes: Int
    get() = when (this) {
        FeedScope.FRIENDS -> R.string.feed_scope_friends_label
        FeedScope.EVERYONE -> R.string.feed_scope_everyone_label
    }

@get:StringRes
val LeaderboardVisibility.labelRes: Int
    get() = when (this) {
        LeaderboardVisibility.PRIVATE -> R.string.leaderboard_visibility_private_label
        LeaderboardVisibility.FRIENDS -> R.string.leaderboard_visibility_friends_label
        LeaderboardVisibility.PUBLIC -> R.string.leaderboard_visibility_public_label
    }

@get:StringRes
val LeaderboardVisibility.descriptionRes: Int
    get() = when (this) {
        LeaderboardVisibility.PRIVATE -> R.string.leaderboard_visibility_private_desc
        LeaderboardVisibility.FRIENDS -> R.string.leaderboard_visibility_friends_desc
        LeaderboardVisibility.PUBLIC -> R.string.leaderboard_visibility_public_desc
    }

@get:StringRes
val SyncCategory.labelRes: Int
    get() = when (this) {
        SyncCategory.CARS -> R.string.sync_category_cars_label
        SyncCategory.EVENTS -> R.string.sync_category_events_label
        SyncCategory.PROFILE -> R.string.sync_category_profile_label
        SyncCategory.GPS_HISTORY -> R.string.sync_category_gps_history_label
        SyncCategory.STATISTICS -> R.string.sync_category_statistics_label
        SyncCategory.TROPHIES -> R.string.sync_category_trophies_label
    }

@get:StringRes
val SyncCategory.descriptionRes: Int
    get() = when (this) {
        SyncCategory.CARS -> R.string.sync_category_cars_desc
        SyncCategory.EVENTS -> R.string.sync_category_events_desc
        SyncCategory.PROFILE -> R.string.sync_category_profile_desc
        SyncCategory.GPS_HISTORY -> R.string.sync_category_gps_history_desc
        SyncCategory.STATISTICS -> R.string.sync_category_statistics_desc
        SyncCategory.TROPHIES -> R.string.sync_category_trophies_desc
    }

@get:StringRes
val ProfileSection.labelRes: Int
    get() = when (this) {
        ProfileSection.PERSONAL_INFO -> R.string.profile_section_personal_info_label
        ProfileSection.GARAGE -> R.string.profile_section_garage_label
        ProfileSection.TROPHIES -> R.string.profile_section_trophies_label
    }

@get:StringRes
val ProfileSection.descriptionRes: Int
    get() = when (this) {
        ProfileSection.PERSONAL_INFO -> R.string.profile_section_personal_info_desc
        ProfileSection.GARAGE -> R.string.profile_section_garage_desc
        ProfileSection.TROPHIES -> R.string.profile_section_trophies_desc
    }
