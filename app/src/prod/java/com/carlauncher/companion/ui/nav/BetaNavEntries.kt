package com.carlauncher.companion.ui.nav

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import com.carlauncher.companion.data.AppContainer

/**
 * Prod half of the navigation seam — see the dev flavor's `BetaNavEntries`. Managing remote cars
 * and the Bluetooth radar trigger aren't part of this build, so there are no routes to register
 * and no top-bar icons to show. The only car is this phone, seeded at startup and auto-selected.
 */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.betaDestinations(navController: NavHostController, container: AppContainer) = Unit

@Composable
@Suppress("UNUSED_PARAMETER")
fun RowScope.BetaTopBarIcons(navController: NavHostController) = Unit

@Composable
@Suppress("UNUSED_PARAMETER")
fun BetaAddCarAction(navController: NavHostController) = Unit
