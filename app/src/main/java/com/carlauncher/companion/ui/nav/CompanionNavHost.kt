package com.carlauncher.companion.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.carlauncher.companion.R
import com.carlauncher.companion.data.AppContainer
import com.carlauncher.companion.data.cloud.CloudSessionState
import com.carlauncher.companion.data.db.DeviceEntity
import com.carlauncher.companion.data.model.HistoryRange
import com.carlauncher.companion.data.model.Trophy
import com.carlauncher.companion.ui.auth.CloudEntryScreen
import com.carlauncher.companion.ui.auth.PasswordResetRequestScreen
import com.carlauncher.companion.ui.auth.RecoveryCodeScreen
import com.carlauncher.companion.ui.auth.SetNewPasswordScreen
import com.carlauncher.companion.ui.cloud.CloudSettingsScreen
import com.carlauncher.companion.ui.feed.FeedScreen
import com.carlauncher.companion.ui.feed.PublicProfileScreen
import com.carlauncher.companion.ui.feed.SharedCarDetailScreen
import com.carlauncher.companion.ui.feed.SharedEventDetailScreen
import com.carlauncher.companion.ui.friends.FriendsScreen
import com.carlauncher.companion.ui.leaderboard.LeaderboardScreen
import com.carlauncher.companion.ui.common.IconBadge
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.legal.LegalDocument
import com.carlauncher.companion.ui.legal.LegalDocumentScreen
import com.carlauncher.companion.ui.events.EventDetailScreen
import com.carlauncher.companion.ui.events.EventsScreen
import com.carlauncher.companion.ui.garage.CarDetailScreen
import com.carlauncher.companion.ui.garage.GarageScreen
import com.carlauncher.companion.ui.history.HistoryScreen
import com.carlauncher.companion.ui.map.MapScreen
import com.carlauncher.companion.ui.profile.ProfileScreen
import com.carlauncher.companion.ui.share.EventShareScreen
import com.carlauncher.companion.ui.share.ShareScreen
import com.carlauncher.companion.ui.stats.StatsScreen
import com.carlauncher.companion.ui.trophies.TrophiesScreen
import com.carlauncher.companion.ui.trophies.TrophyCelebrationDialog
import com.carlauncher.companion.ui.theme.neonSweep
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionNavHost(
    container: AppContainer,
    openTrophies: Boolean = false,
    onTrophiesOpened: () -> Unit = {},
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    val devices by container.deviceRepository.observeDevices().collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedDeviceId by container.deviceRepository.observeSelectedDeviceId()
        .collectAsStateWithLifecycle(initialValue = null)

    // If nothing is selected yet (fresh install / device removed) but devices exist, pick one.
    LaunchedEffect(devices, selectedDeviceId) {
        if (selectedDeviceId == null && devices.isNotEmpty()) {
            container.deviceRepository.selectDevice(devices.first().deviceId)
        }
    }

    // Launched from a trophy-unlocked notification.
    LaunchedEffect(openTrophies) {
        if (openTrophies) {
            navController.navigate(Destination.Trophies.route) { launchSingleTop = true }
            onTrophiesOpened()
        }
    }

    // A tapped password-reset email lands in MainActivity, which imports the session and
    // flips this. Jump straight to the set-password screen — otherwise the user is silently
    // signed in and left wondering whether the reset worked.
    val pendingPasswordReset by container.authRepository.pendingPasswordReset
        .collectAsStateWithLifecycle()
    LaunchedEffect(pendingPasswordReset) {
        if (pendingPasswordReset) {
            navController.navigate(Destination.SetNewPassword.route) { launchSingleTop = true }
        }
    }

    val cloudSessionState by container.authRepository.sessionState
        .collectAsStateWithLifecycle(initialValue = CloudSessionState.Loading)
    val signedIn = cloudSessionState is CloudSessionState.SignedIn

    // Trophies earned since the popup last showed — including ones unlocked by a
    // background service while the app was closed. Snapshotted into `celebrating` on
    // arrival so a trophy landing mid-dialog (rare, but a trip can end while this is up)
    // doesn't rewrite the list out from under the user; it just queues for next time.
    val pendingCelebrations by container.trophyRepository.observePendingCelebrations()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var celebrating by remember { mutableStateOf<List<Trophy>>(emptyList()) }
    LaunchedEffect(pendingCelebrations) {
        if (celebrating.isEmpty() && pendingCelebrations.isNotEmpty()) {
            celebrating = pendingCelebrations
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val detailTitle = detailTitles[currentRoute]
    val selectedDevice = devices.firstOrNull { it.deviceId == selectedDeviceId }

    Scaffold(
        topBar = {
            if (detailTitle != null || showMainTopBar) {
                TopAppBar(
                    navigationIcon = {
                        if (detailTitle != null && currentRoute !in noBackRoutes) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_content_description))
                            }
                        }
                    },
                    title = {
                        if (detailTitle != null) {
                            Text(stringResource(detailTitle), style = MaterialTheme.typography.titleLarge)
                        } else {
                            DeviceSwitcher(
                                devices = devices,
                                selected = selectedDevice,
                                onSelect = { id -> scope.launch { container.deviceRepository.selectDevice(id) } },
                            )
                        }
                    },
                    actions = {
                        if (detailTitle == null) BetaTopBarIcons(navController)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.primary,
                        navigationIconContentColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        },
        bottomBar = {
            if (detailTitle == null) {
                Column {
                    // Lit hairline along the top edge — reads as the glow line under a
                    // dashboard binnacle and separates the bar without a hard divider.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(neonSweep(MaterialTheme.colorScheme.primary, startAlpha = 0.5f)),
                    )
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        bottomTabs(signedIn).forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.destination.route,
                                onClick = {
                                    navController.navigate(tab.destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                                label = { Text(stringResource(tab.labelRes), style = MaterialTheme.typography.labelMedium) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Map.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.Map.route) {
                RequireDevice(selectedDeviceId, navController) { deviceId ->
                    MapScreen(
                        deviceId = deviceId,
                        trackRepository = container.trackRepository,
                        deviceRepository = container.deviceRepository,
                        focusRequestHolder = container.mapFocusRequestHolder,
                        beta = container.beta,
                        onShare = { range ->
                            navController.navigate(Destination.Share.build(deviceId, range))
                        },
                    )
                }
            }
            composable(Destination.History.route) {
                RequireDevice(selectedDeviceId, navController) { deviceId ->
                    HistoryScreen(
                        deviceId = deviceId,
                        deviceName = selectedDevice?.name ?: deviceId,
                        trackRepository = container.trackRepository,
                        deviceRepository = container.deviceRepository,
                        onPointSelected = { lat, lng ->
                            container.mapFocusRequestHolder.request(lat, lng)
                            navController.navigate(Destination.Map.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
            composable(Destination.Stats.route) {
                RequireDevice(selectedDeviceId, navController) { deviceId ->
                    StatsScreen(
                        deviceId = deviceId,
                        trackRepository = container.trackRepository,
                        onShare = { range ->
                            navController.navigate(Destination.Share.build(deviceId, range))
                        },
                    )
                }
            }
            composable(
                Destination.Share.route,
                arguments = listOf(
                    navArgument("deviceId") { type = NavType.StringType },
                    navArgument("range") { type = NavType.StringType },
                ),
            ) { backStack ->
                val deviceId = backStack.arguments?.getString("deviceId").orEmpty()
                val range = backStack.arguments?.getString("range")
                    ?.let { HistoryRange.valueOf(it) } ?: HistoryRange.LAST_7_DAYS
                ShareScreen(
                    deviceId = deviceId,
                    range = range,
                    trackRepository = container.trackRepository,
                    deviceRepository = container.deviceRepository,
                )
            }
            composable(
                Destination.ShareEvent.route,
                arguments = listOf(navArgument("eventId") { type = NavType.StringType }),
            ) { backStack ->
                val eventId = backStack.arguments?.getString("eventId").orEmpty()
                EventShareScreen(
                    eventId = eventId,
                    eventRepository = container.eventRepository,
                    carRepository = container.carRepository,
                )
            }
            // Manage cars + Bluetooth radar trigger — registered only in the dev flavor.
            betaDestinations(navController, container)
            composable(Destination.Profile.route) {
                ProfileScreen(
                    profileRepository = container.profileRepository,
                    carRepository = container.carRepository,
                    eventRepository = container.eventRepository,
                    trophyRepository = container.trophyRepository,
                    xpRepository = container.xpRepository,
                    authRepository = container.authRepository,
                    onOpenGarage = { navController.navigate(Destination.Garage.route) { launchSingleTop = true } },
                    onOpenCar = { carId -> navController.navigate(Destination.CarDetail.build(carId)) },
                    onOpenEvents = { navController.navigate(Destination.Events.route) { launchSingleTop = true } },
                    onOpenTrophies = { navController.navigate(Destination.Trophies.route) { launchSingleTop = true } },
                    onOpenCloud = { navController.navigate(Destination.Auth.route) { launchSingleTop = true } },
                    onOpenFriends = { navController.navigate(Destination.Friends.route) { launchSingleTop = true } },
                    onOpenLeaderboard = { navController.navigate(Destination.Leaderboard.route) { launchSingleTop = true } },
                )
            }
            composable(Destination.Garage.route) {
                GarageScreen(
                    carRepository = container.carRepository,
                    deviceRepository = container.deviceRepository,
                    onCarSelected = { carId -> navController.navigate(Destination.CarDetail.build(carId)) },
                )
            }
            composable(
                Destination.CarDetail.route,
                arguments = listOf(navArgument("carId") { type = NavType.StringType }),
            ) { backStack ->
                val carId = backStack.arguments?.getString("carId").orEmpty()
                CarDetailScreen(
                    carId = carId,
                    carRepository = container.carRepository,
                    deviceRepository = container.deviceRepository,
                    trackRepository = container.trackRepository,
                    trophyRepository = container.trophyRepository,
                    authRepository = container.authRepository,
                    cloudPrefsRepository = container.cloudPrefsRepository,
                    cloudSyncManager = container.cloudSyncManager,
                    onDeleted = { navController.popBackStack() },
                )
            }
            composable(Destination.Trophies.route) {
                TrophiesScreen(trophyRepository = container.trophyRepository)
            }
            composable(Destination.Events.route) {
                EventsScreen(
                    eventRepository = container.eventRepository,
                    carRepository = container.carRepository,
                    onEventSelected = { eventId -> navController.navigate(Destination.EventDetail.build(eventId)) },
                )
            }
            composable(Destination.Auth.route) {
                CloudEntryScreen(
                    authRepository = container.authRepository,
                    cloudPrefsRepository = container.cloudPrefsRepository,
                    cloudSyncManager = container.cloudSyncManager,
                    onDone = { navController.popBackStack() },
                    onShowRecoveryCode = { code ->
                        // Replace Auth on the stack: there is nothing sensible to go "back"
                        // to once the account exists.
                        navController.navigate(Destination.RecoveryCode.build(code)) {
                            popUpTo(Destination.Auth.route) { inclusive = true }
                        }
                    },
                    onForgotPassword = { navController.navigate(Destination.PasswordReset.route) },
                    onOpenLegal = { navController.navigate(Destination.Legal.build(it.name)) },
                    onOpenCloudSettings = { navController.navigate(Destination.CloudSettings.route) },
                )
            }
            composable(Destination.CloudSettings.route) {
                CloudSettingsScreen(
                    cloudPrefsRepository = container.cloudPrefsRepository,
                    cloudSyncManager = container.cloudSyncManager,
                    cloudRestoreManager = container.cloudRestoreManager,
                )
            }
            composable(Destination.Friends.route) {
                FriendsScreen(
                    friendsRepository = container.friendsRepository,
                    onOpenProfile = { userId -> navController.navigate(Destination.PublicProfile.build(userId)) },
                )
            }
            composable(Destination.Leaderboard.route) {
                LeaderboardScreen(leaderboardRepository = container.leaderboardRepository)
            }
            composable(Destination.Feed.route) {
                FeedScreen(
                    feedRepository = container.feedRepository,
                    sharedContentRepository = container.sharedContentRepository,
                    cloudPrefsRepository = container.cloudPrefsRepository,
                    onOpenCar = { ownerId, carId -> navController.navigate(Destination.SharedCar.build(ownerId, carId)) },
                    onOpenEvent = { ownerId, eventId -> navController.navigate(Destination.SharedEvent.build(ownerId, eventId)) },
                    onOpenProfile = { userId -> navController.navigate(Destination.PublicProfile.build(userId)) },
                )
            }
            composable(
                Destination.PublicProfile.route,
                arguments = listOf(navArgument("userId") { type = NavType.StringType }),
            ) { backStack ->
                val userId = backStack.arguments?.getString("userId").orEmpty()
                PublicProfileScreen(
                    userId = userId,
                    sharedContentRepository = container.sharedContentRepository,
                    onOpenCar = { carId -> navController.navigate(Destination.SharedCar.build(userId, carId)) },
                    onOpenEvent = { eventId -> navController.navigate(Destination.SharedEvent.build(userId, eventId)) },
                )
            }
            composable(
                Destination.SharedCar.route,
                arguments = listOf(
                    navArgument("ownerId") { type = NavType.StringType },
                    navArgument("carId") { type = NavType.StringType },
                ),
            ) { backStack ->
                val carId = backStack.arguments?.getString("carId").orEmpty()
                SharedCarDetailScreen(carId = carId, sharedContentRepository = container.sharedContentRepository)
            }
            composable(
                Destination.SharedEvent.route,
                arguments = listOf(
                    navArgument("ownerId") { type = NavType.StringType },
                    navArgument("eventId") { type = NavType.StringType },
                ),
            ) { backStack ->
                val eventId = backStack.arguments?.getString("eventId").orEmpty()
                SharedEventDetailScreen(eventId = eventId, sharedContentRepository = container.sharedContentRepository)
            }
            composable(Destination.PasswordReset.route) {
                PasswordResetRequestScreen(
                    authRepository = container.authRepository,
                    onDone = { navController.popBackStack() },
                )
            }
            composable(Destination.SetNewPassword.route) {
                SetNewPasswordScreen(
                    authRepository = container.authRepository,
                    onDone = { navController.popBackStack() },
                    onNewRecoveryCode = { code ->
                        navController.navigate(Destination.RecoveryCode.build(code, replacement = true)) {
                            popUpTo(Destination.SetNewPassword.route) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                Destination.RecoveryCode.route,
                arguments = listOf(
                    navArgument("code") { type = NavType.StringType },
                    navArgument("replacement") { type = NavType.BoolType },
                ),
            ) { backStack ->
                RecoveryCodeScreen(
                    recoveryCode = backStack.arguments?.getString("code").orEmpty(),
                    onConfirmed = { navController.popBackStack() },
                    isReplacement = backStack.arguments?.getBoolean("replacement") ?: false,
                )
            }
            composable(
                Destination.Legal.route,
                arguments = listOf(navArgument("document") { type = NavType.StringType }),
            ) { backStack ->
                val document = backStack.arguments?.getString("document")
                    ?.let { runCatching { LegalDocument.valueOf(it) }.getOrNull() }
                    ?: LegalDocument.TERMS
                LegalDocumentScreen(document)
            }
            composable(
                Destination.EventDetail.route,
                arguments = listOf(navArgument("eventId") { type = NavType.StringType }),
            ) { backStack ->
                val eventId = backStack.arguments?.getString("eventId").orEmpty()
                EventDetailScreen(
                    eventId = eventId,
                    eventRepository = container.eventRepository,
                    carRepository = container.carRepository,
                    authRepository = container.authRepository,
                    cloudPrefsRepository = container.cloudPrefsRepository,
                    cloudSyncManager = container.cloudSyncManager,
                    onDone = { navController.popBackStack() },
                    onShare = { id -> navController.navigate(Destination.ShareEvent.build(id)) },
                )
            }
        }
    }

    TrophyCelebrationDialog(
        trophies = celebrating,
        onDismiss = {
            val shown = celebrating
            celebrating = emptyList()
            scope.launch { container.trophyRepository.acknowledgeCelebration(shown) }
        },
    )
}

@Composable
private fun RequireDevice(deviceId: String?, navController: NavHostController, content: @Composable (String) -> Unit) {
    if (deviceId == null) {
        EmptyDevicesHint(navController)
    } else {
        content(deviceId)
    }
}

@Composable
private fun EmptyDevicesHint(navController: NavHostController) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconBadge(Icons.Filled.DirectionsCar, MaterialTheme.colorScheme.primary, size = 72.dp)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.nav_no_car_selected), style = MaterialTheme.typography.titleLarge)
            // Only the dev flavor can add cars; in prod this phone is seeded at startup.
            BetaAddCarAction(navController)
        }
    }
}

@Composable
private fun DeviceSwitcher(devices: List<DeviceEntity>, selected: DeviceEntity?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        NeonPill(
            text = selected?.name ?: "Car Companion",
            accent = MaterialTheme.colorScheme.primary,
            onClick = { expanded = devices.isNotEmpty() },
            leading = if (devices.size > 1) {
                {
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = stringResource(R.string.nav_switch_car_content_description),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                null
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device.name) },
                    onClick = {
                        onSelect(device.deviceId)
                        expanded = false
                    },
                )
            }
        }
    }
}
