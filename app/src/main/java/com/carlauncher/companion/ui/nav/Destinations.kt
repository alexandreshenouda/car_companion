package com.carlauncher.companion.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Route
import androidx.compose.ui.graphics.vector.ImageVector
import com.carlauncher.companion.R
import com.carlauncher.companion.data.model.HistoryRange

sealed class Destination(val route: String) {
    data object Map : Destination("map")
    data object History : Destination("history")
    data object Stats : Destination("stats")
    data object Devices : Destination("devices")
    data object BluetoothTrigger : Destination("bluetooth-trigger")
    data object Settings : Destination("settings")
    data object Share : Destination("share/{deviceId}/{range}") {
        fun build(deviceId: String, range: HistoryRange) = "share/$deviceId/${range.name}"
    }
    data object ShareEvent : Destination("share-event/{eventId}") {
        fun build(eventId: String) = "share-event/$eventId"
    }
    data object Profile : Destination("profile")
    data object Garage : Destination("garage")
    data object CarDetail : Destination("garage/{carId}") {
        fun build(carId: String) = "garage/$carId"
    }
    data object Trophies : Destination("trophies")
    data object Events : Destination("events")
    data object EventDetail : Destination("events/{eventId}") {
        const val NEW_ID = "new"
        fun build(eventId: String) = "events/$eventId"
    }

    // --- Supabase cloud. Present in both flavors; inert when the build has no credentials.
    data object Auth : Destination("auth")
    data object PasswordReset : Destination("auth/reset")
    /** Reached from a reset link, not from a button — see `MainActivity.handleAuthDeepLink`. */
    data object SetNewPassword : Destination("auth/reset/new")
    data object RecoveryCode : Destination("auth/recovery/{code}/{replacement}") {
        /** [replacement] true when this code replaces one lost with a forgotten password. */
        fun build(code: String, replacement: Boolean = false) = "auth/recovery/$code/$replacement"
    }
    data object Legal : Destination("legal/{document}") {
        fun build(document: String) = "legal/$document"
    }
    data object CloudSettings : Destination("cloud-settings")
    data object Friends : Destination("friends")
    data object Leaderboard : Destination("leaderboard")
    data object Feed : Destination("feed")
    data object PublicProfile : Destination("u/{userId}") {
        fun build(userId: String) = "u/$userId"
    }
    data object SharedCar : Destination("u/{ownerId}/car/{carId}") {
        fun build(ownerId: String, carId: String) = "u/$ownerId/car/$carId"
    }
    data object SharedEvent : Destination("u/{ownerId}/event/{eventId}") {
        fun build(ownerId: String, eventId: String) = "u/$ownerId/event/$eventId"
    }
}

/** Routes reached from the top bar rather than the bottom tabs: back arrow, own title, no tabs. */
val detailTitles = mapOf(
    Destination.Devices.route to R.string.nav_title_devices,
    Destination.BluetoothTrigger.route to R.string.nav_title_bluetooth_trigger,
    Destination.Settings.route to R.string.nav_title_settings,
    Destination.Share.route to R.string.share_title_trip,
    Destination.ShareEvent.route to R.string.share_title_trip,
    Destination.Garage.route to R.string.profile_garage_section_label,
    Destination.CarDetail.route to R.string.nav_title_car_detail,
    Destination.Events.route to R.string.profile_events_title,
    Destination.Trophies.route to R.string.profile_trophies_title,
    Destination.EventDetail.route to R.string.nav_title_event_detail,
    Destination.Auth.route to R.string.profile_cloud_account_title,
    Destination.PasswordReset.route to R.string.nav_title_password_reset,
    Destination.SetNewPassword.route to R.string.nav_title_set_new_password,
    Destination.RecoveryCode.route to R.string.nav_title_recovery_code,
    Destination.Legal.route to R.string.nav_title_legal,
    Destination.CloudSettings.route to R.string.nav_title_cloud_settings,
    Destination.Friends.route to R.string.profile_friends_title,
    Destination.Leaderboard.route to R.string.profile_leaderboard_title,
    Destination.PublicProfile.route to R.string.profile_title,
    Destination.SharedCar.route to R.string.nav_title_car_detail,
    Destination.SharedEvent.route to R.string.nav_title_event_detail,
)

/**
 * Detail routes that must not offer a way out via the top bar. Currently only the
 * end-to-end-encryption recovery code, which is shown exactly once and is unrecoverable if
 * dismissed — the user has to tick the confirmation and continue.
 */
val noBackRoutes = setOf(Destination.RecoveryCode.route)

data class BottomTab(val destination: Destination, @param:StringRes val labelRes: Int, val icon: ImageVector)

private val coreBottomTabs = listOf(
    BottomTab(Destination.Map, R.string.nav_tab_map, Icons.Filled.Map),
    BottomTab(Destination.History, R.string.nav_tab_history, Icons.Filled.Route),
    BottomTab(Destination.Stats, R.string.nav_tab_stats, Icons.Filled.BarChart),
    BottomTab(Destination.Profile, R.string.profile_title, Icons.Filled.Person),
)

/** The Feed tab only exists once signed in — a signed-out device has nothing to show there,
 * and the bottom bar would just be a dead end pointing at a sign-in screen. */
fun bottomTabs(signedIn: Boolean): List<BottomTab> =
    if (signedIn) {
        coreBottomTabs.toMutableList().apply {
            add(1, BottomTab(Destination.Feed, R.string.nav_tab_feed, Icons.Filled.DynamicFeed))
        }
    } else {
        coreBottomTabs
    }
