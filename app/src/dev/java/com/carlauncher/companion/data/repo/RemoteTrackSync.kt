package com.carlauncher.companion.data.repo

import com.carlauncher.companion.data.db.LocationPointDao
import com.carlauncher.companion.data.db.LocationPointEntity
import com.carlauncher.companion.data.db.SyncStateDao
import com.carlauncher.companion.data.db.SyncStateEntity
import com.carlauncher.companion.data.firebase.PushDocument
import com.carlauncher.companion.data.firebase.TrackRemoteSource
import com.carlauncher.companion.data.model.DiscoveredDevice
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val PAGE_SIZE = 100L

/**
 * Dev half of the Firestore seam: everything [TrackRepository] used to do that touched the
 * network. The prod flavor declares the same class with the same signatures and empty bodies,
 * so `src/main` (and [TrackRepository]'s delegating methods) compile unchanged while no Firebase
 * code or dependency reaches the prod APK.
 */
class RemoteTrackSync(
    private val pointDao: LocationPointDao,
    private val syncStateDao: SyncStateDao,
) {
    private val remote = TrackRemoteSource(FirebaseFirestore.getInstance())

    /** Finds every device that has ever pushed a location, for the Devices screen's auto-discovery. */
    suspend fun discoverDevices(): List<DiscoveredDevice> = remote.discoverDeviceIds()

    /**
     * Tails the live Firestore listener and mirrors new points into Room. Callers collect this
     * for as long as a device's Map screen is visible; it never deletes remotely — only
     * [syncFullHistory] does that, so the live-most-recent doc is never pulled out from under it.
     */
    fun liveUpdates(deviceId: String): Flow<Unit> =
        remote.observeLatestPushes(deviceId).map { pushes -> insertPushes(deviceId, pushes) }

    /** Deletes every push doc for a device from Firestore, e.g. when the user removes the car. */
    suspend fun deleteAllRemoteData(deviceId: String) {
        remote.deleteAllPushes(deviceId)
    }

    /**
     * Paginated catch-up sync: fetches everything since the last watermark, commits it to Room,
     * advances the watermark, then deletes the now-redundant remote docs — always leaving the
     * single most-recent push doc in place for [liveUpdates]/other clients to still see.
     * Safe to call repeatedly (e.g. pull-to-refresh); safe to interrupt (Room commit always
     * precedes the matching remote delete, and re-inserting an already-cached page is a no-op
     * thanks to the unique (deviceId, ts) index).
     */
    suspend fun syncFullHistory(deviceId: String) {
        var watermark = syncStateDao.get(deviceId)?.lastSyncedPushedAtMillis ?: 0L
        var previousBatch: List<PushDocument> = emptyList()

        while (true) {
            val batch = remote.fetchPushesAfter(deviceId, watermark, PAGE_SIZE)
            if (batch.isEmpty()) break

            insertPushes(deviceId, batch)
            watermark = batch.maxOf { it.pushedAtMillis }
            syncStateDao.upsert(SyncStateEntity(deviceId, watermark))

            if (previousBatch.isNotEmpty()) {
                remote.deletePushes(deviceId, previousBatch.map { it.id })
            }
            previousBatch = batch

            if (batch.size < PAGE_SIZE) break // exhausted: this was the final page
        }

        // previousBatch now holds the final page (ascending by pushedAt) — delete all of it
        // except its last element, which is the device's single most-recent push doc.
        if (previousBatch.isNotEmpty()) {
            val idsToDelete = previousBatch.dropLast(1).map { it.id }
            remote.deletePushes(deviceId, idsToDelete)
        }
    }

    private suspend fun insertPushes(deviceId: String, pushes: List<PushDocument>) {
        val entities = pushes.flatMap { push ->
            push.points.map { p ->
                LocationPointEntity(
                    deviceId = deviceId,
                    lat = p.lat,
                    lng = p.lng,
                    ts = p.ts,
                    speedKmh = p.speedKmh.toInt(),
                    pushedAtMillis = push.pushedAtMillis,
                )
            }
        }
        if (entities.isNotEmpty()) pointDao.insertAll(entities)
    }
}
