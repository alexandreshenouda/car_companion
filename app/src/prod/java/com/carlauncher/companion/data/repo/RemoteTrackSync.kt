package com.carlauncher.companion.data.repo

import com.carlauncher.companion.data.db.LocationPointDao
import com.carlauncher.companion.data.db.SyncStateDao
import com.carlauncher.companion.data.model.DiscoveredDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Prod half of the Firestore seam — see the dev flavor's `RemoteTrackSync` for the real thing.
 * Prod tracks only this phone's own GPS, so there is nothing remote to discover, tail, sync or
 * delete, and no Firebase dependency is linked into the build at all.
 */
@Suppress("UNUSED_PARAMETER")
class RemoteTrackSync(pointDao: LocationPointDao, syncStateDao: SyncStateDao) {

    suspend fun discoverDevices(): List<DiscoveredDevice> = emptyList()

    fun liveUpdates(deviceId: String): Flow<Unit> = emptyFlow()

    suspend fun deleteAllRemoteData(deviceId: String) = Unit

    suspend fun syncFullHistory(deviceId: String) = Unit
}
