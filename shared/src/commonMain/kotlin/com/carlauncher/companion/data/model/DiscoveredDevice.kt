package com.carlauncher.companion.data.model

/** A device found by scanning Firestore, not yet (or no longer) saved locally. */
data class DiscoveredDevice(
    val deviceId: String,
    val lastSeenMillis: Long,
)
