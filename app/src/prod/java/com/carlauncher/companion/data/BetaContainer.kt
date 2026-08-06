package com.carlauncher.companion.data

import android.content.Context

/**
 * Prod half of the beta-feature seam — see the dev flavor's `BetaContainer` for what this
 * stands in for. Nothing to hold: radars and the Bluetooth trigger aren't part of this build.
 */
/**
 * Prod half of the beta-singleton seam — see the dev flavor's `BetaContainer`. Radars and the
 * Bluetooth trigger don't exist in this build, so there is nothing to hold.
 */
@Suppress("UNUSED_PARAMETER")
class BetaContainer(context: Context)
