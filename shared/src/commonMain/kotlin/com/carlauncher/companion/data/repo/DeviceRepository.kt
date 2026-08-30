package com.carlauncher.companion.data.repo

import com.carlauncher.companion.data.db.AppStateDao
import com.carlauncher.companion.data.db.AppStateEntity
import com.carlauncher.companion.data.db.DeviceDao
import com.carlauncher.companion.data.db.DeviceEntity
import com.carlauncher.companion.data.db.LocationPointDao
import com.carlauncher.companion.data.db.SyncStateDao
import com.carlauncher.companion.data.model.HistoryRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class DeviceRepository(
    private val deviceDao: DeviceDao,
    private val pointDao: LocationPointDao,
    private val syncStateDao: SyncStateDao,
    private val appStateDao: AppStateDao,
) {
    fun observeDevices(): Flow<List<DeviceEntity>> = deviceDao.observeAll()

    suspend fun getDeviceName(deviceId: String): String? = deviceDao.getById(deviceId)?.name

    suspend fun getDevice(deviceId: String): DeviceEntity? = deviceDao.getById(deviceId)

    fun observeSelectedDeviceId(): Flow<String?> = appStateDao.observe().map { it?.selectedDeviceId }

    fun observeSelectedRange(): Flow<HistoryRange> =
        appStateDao.observe().map { state ->
            state?.selectedRange?.let { runCatching { HistoryRange.valueOf(it) }.getOrNull() }
                ?: HistoryRange.LAST_7_DAYS
        }

    suspend fun selectRange(range: HistoryRange) {
        val current = appStateDao.getOnce() ?: AppStateEntity(selectedDeviceId = null)
        appStateDao.upsert(current.copy(selectedRange = range.name))
    }

    fun observeLocalRecordingActive(): Flow<Boolean> =
        appStateDao.observe().map { it?.localRecordingActive ?: false }

    suspend fun setLocalRecordingActive(active: Boolean) {
        val current = appStateDao.getOnce() ?: AppStateEntity(selectedDeviceId = null)
        appStateDao.upsert(current.copy(localRecordingActive = active))
    }

    /**
     * Seeds the synthetic "This phone" device row if it doesn't exist yet, so a fresh install
     * (or one with no cars registered) always has a device to select and record GPS under.
     * `addedAt = 0L` sorts it first in [observeAll] regardless of when real cars were added.
     */
    suspend fun ensureLocalDeviceExists() {
        if (deviceDao.getById(LOCAL_DEVICE_ID) == null) {
            deviceDao.upsert(
                DeviceEntity(deviceId = LOCAL_DEVICE_ID, name = "This phone", addedAt = 0L, isLocal = true),
            )
        }
    }

    suspend fun addDevice(deviceId: String, name: String) {
        deviceDao.upsert(DeviceEntity(deviceId = deviceId, name = name, addedAt = Clock.System.now().toEpochMilliseconds()))
    }

    suspend fun renameDevice(deviceId: String, newName: String) {
        val existing = deviceDao.getById(deviceId) ?: return
        deviceDao.upsert(existing.copy(name = newName))
    }

    suspend fun updateCarDetails(deviceId: String, brand: String?, model: String?, details: String?) {
        val existing = deviceDao.getById(deviceId) ?: return
        deviceDao.upsert(existing.copy(brand = brand, model = model, details = details))
    }

    /**
     * Deletes the device and its cached points and sync watermark locally only — Firestore is
     * never touched. Clearing the watermark too means a later re-add does a full resync from
     * Firestore's current state, so whatever is still there (e.g. the single most-recent push
     * doc that [TrackRepository.syncFullHistory] never deletes) shows up again rather than
     * silently vanishing.
     */
    suspend fun removeDevice(device: DeviceEntity) {
        if (device.isLocal) return
        deviceDao.delete(device)
        pointDao.deleteAllForDevice(device.deviceId)
        syncStateDao.deleteForDevice(device.deviceId)
        if (appStateDao.getOnce()?.selectedDeviceId == device.deviceId) {
            selectDevice(null)
        }
    }

    suspend fun selectDevice(deviceId: String?) {
        val current = appStateDao.getOnce() ?: AppStateEntity(selectedDeviceId = null)
        appStateDao.upsert(current.copy(selectedDeviceId = deviceId))
    }

    companion object {
        /** Deterministic id for the synthetic "This phone" device — never a real Firestore device id. */
        const val LOCAL_DEVICE_ID = "this_phone"
    }
}
