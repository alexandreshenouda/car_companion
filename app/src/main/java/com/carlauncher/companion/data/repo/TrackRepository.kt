package com.carlauncher.companion.data.repo

import com.carlauncher.companion.data.db.LocationPointDao
import com.carlauncher.companion.data.db.LocationPointEntity
import com.carlauncher.companion.data.model.DiscoveredDevice
import com.carlauncher.companion.data.model.HistoryRange
import com.carlauncher.companion.data.model.TrackStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The local Room cache every screen reads from, plus thin pass-throughs to [RemoteTrackSync] for
 * the remote half. [RemoteTrackSync] is a flavor seam: real Firestore work in dev, no-ops in prod,
 * so these four methods stay callable from shared UI code in both builds.
 *
 * Stays in `:app` rather than moving to `:shared` alongside its sibling repos: [RemoteTrackSync]
 * is itself dev/prod *flavor*-scoped (not KMP-target-scoped — `:shared`'s single androidMain
 * source set has no flavor dimension to express that split), so pulling this repo out would
 * either strand it without its remote half or require adding Android product flavors to
 * `:shared` for one class. iOS's own tracking repo is genuinely new platform-shell work for
 * Phase 6, not a port of this one.
 */
class TrackRepository(
    private val remoteSync: RemoteTrackSync,
    private val pointDao: LocationPointDao,
) {
    /** Finds every device that has ever pushed a location, for the Devices screen's auto-discovery. */
    suspend fun discoverDevices(): List<DiscoveredDevice> = remoteSync.discoverDevices()

    /** Tails the remote listener for as long as it's collected, mirroring new points into Room. */
    fun liveUpdates(deviceId: String): Flow<Unit> = remoteSync.liveUpdates(deviceId)

    /** Deletes every push doc for a device remotely, e.g. when the user removes the car. */
    suspend fun deleteAllRemoteData(deviceId: String) = remoteSync.deleteAllRemoteData(deviceId)

    /** Paginated catch-up sync of everything pushed since the last watermark. */
    suspend fun syncFullHistory(deviceId: String) = remoteSync.syncFullHistory(deviceId)

    fun observeLatestPoint(deviceId: String): Flow<LocationPointEntity?> =
        pointDao.observeLatest(deviceId)

    /** Inserts a single phone-GPS fix recorded by [com.carlauncher.companion.car.LocalTrackingService]. */
    suspend fun recordLocalPoint(point: LocationPointEntity) {
        pointDao.insertAll(listOf(point))
    }

    suspend fun pointsInRange(deviceId: String, range: HistoryRange): List<LocationPointEntity> {
        val (from, to) = rangeBounds(range)
        return pointDao.getInRange(deviceId, from, to)
    }

    suspend fun statsInRange(deviceId: String, range: HistoryRange): TrackStats {
        val points = pointsInRange(deviceId, range)
        return withContext(Dispatchers.Default) { computeStats(points) }
    }

    /** Clears locally cached points between [fromTs] and [toTs] (inclusive), e.g. a single day. */
    suspend fun deletePointsInRange(deviceId: String, fromTs: Long, toTs: Long) {
        pointDao.deleteInRange(deviceId, fromTs, toTs)
    }

    /** Clears a single locally cached point, e.g. dropping one bad GPS fix from a day. */
    suspend fun deletePoint(id: Long) {
        pointDao.deleteById(id)
    }

    /**
     * Moves every point currently in [range] from [sourceDeviceId] to [targetDeviceId] — e.g.
     * attributing a "This phone" recording to a real, remotely-synced car after the fact. A
     * plain column update (see [com.carlauncher.companion.data.db.LocationPointDao.reassignInRange]),
     * not a delete+reinsert, so it can be repeated or reversed at any time without losing data.
     */
    suspend fun reassignPointsInRange(sourceDeviceId: String, targetDeviceId: String, range: HistoryRange) {
        val (from, to) = rangeBounds(range)
        pointDao.reassignInRange(sourceDeviceId, targetDeviceId, from, to)
    }

    private fun rangeBounds(range: HistoryRange): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val from = when (range) {
            HistoryRange.TODAY -> now - 24 * 60 * 60 * 1000L
            HistoryRange.LAST_7_DAYS -> now - 7 * 24 * 60 * 60 * 1000L
            HistoryRange.LAST_30_DAYS -> now - 30 * 24 * 60 * 60 * 1000L
            HistoryRange.ALL -> 0L
        }
        return from to now
    }
}
