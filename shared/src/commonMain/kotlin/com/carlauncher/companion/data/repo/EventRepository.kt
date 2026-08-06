package com.carlauncher.companion.data.repo

import com.carlauncher.companion.data.db.EventDao
import com.carlauncher.companion.data.db.EventEntity
import com.carlauncher.companion.data.db.EventPointDao
import com.carlauncher.companion.data.db.EventPointEntity
import com.carlauncher.companion.data.db.LocationPointDao
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
class EventRepository(
    private val eventDao: EventDao,
    private val eventPointDao: EventPointDao,
    private val locationPointDao: LocationPointDao,
) {
    fun observeEvents(): Flow<List<EventEntity>> = eventDao.observeAll()

    fun observeEvent(eventId: String): Flow<EventEntity?> = eventDao.observe(eventId)

    suspend fun getEvent(eventId: String): EventEntity? = eventDao.getById(eventId)

    fun observePoints(eventId: String): Flow<List<EventPointEntity>> = eventPointDao.observeForEvent(eventId)

    suspend fun createEvent(
        title: String,
        type: String,
        carId: String?,
        deviceId: String?,
        startTs: Long,
        endTs: Long,
        locationLabel: String?,
        notes: String?,
    ): String {
        val id = Uuid.random().toString()
        val now = Clock.System.now().toEpochMilliseconds()
        eventDao.upsert(
            EventEntity(
                id = id,
                title = title,
                type = type,
                carId = carId,
                deviceId = deviceId,
                startTs = startTs,
                endTs = endTs,
                locationLabel = locationLabel,
                notes = notes,
                createdAt = now,
                updatedAt = now,
                pointsSource = "DEVICE",
            ),
        )
        cropPoints(id, deviceId, startTs, endTs)
        return id
    }

    /**
     * Creates an event whose points come from an imported GPX file rather than a tracked
     * device — stored in the same [EventPointEntity] table via the same shape, so it renders
     * identically to a device-cropped event. The window is taken from the file's own points.
     */
    suspend fun createEventFromGpx(
        title: String,
        type: String,
        carId: String?,
        locationLabel: String?,
        notes: String?,
        points: List<GpxPoint>,
    ): String {
        val id = Uuid.random().toString()
        val now = Clock.System.now().toEpochMilliseconds()
        eventDao.upsert(
            EventEntity(
                id = id,
                title = title,
                type = type,
                carId = carId,
                deviceId = null,
                startTs = points.minOf { it.ts },
                endTs = points.maxOf { it.ts },
                locationLabel = locationLabel,
                notes = notes,
                createdAt = now,
                updatedAt = now,
                pointsSource = "GPX",
            ),
        )
        insertGpxPoints(id, points)
        return id
    }

    /** Re-crops the event's points from scratch, since the device/window may have changed. */
    suspend fun updateEvent(
        event: EventEntity,
        title: String,
        type: String,
        carId: String?,
        deviceId: String?,
        startTs: Long,
        endTs: Long,
        locationLabel: String?,
        notes: String?,
    ) {
        eventDao.upsert(
            event.copy(
                title = title,
                type = type,
                carId = carId,
                deviceId = deviceId,
                startTs = startTs,
                endTs = endTs,
                locationLabel = locationLabel,
                notes = notes,
                pointsSource = "DEVICE",
                updatedAt = Clock.System.now().toEpochMilliseconds(),
            ),
        )
        eventPointDao.deleteForEvent(event.id)
        cropPoints(event.id, deviceId, startTs, endTs)
    }

    /** Replaces an event's points with a freshly imported GPX file (create or re-import on edit). */
    suspend fun updateEventGpxPoints(
        event: EventEntity,
        title: String,
        type: String,
        carId: String?,
        locationLabel: String?,
        notes: String?,
        points: List<GpxPoint>,
    ) {
        eventDao.upsert(
            event.copy(
                title = title,
                type = type,
                carId = carId,
                deviceId = null,
                startTs = points.minOf { it.ts },
                endTs = points.maxOf { it.ts },
                locationLabel = locationLabel,
                notes = notes,
                pointsSource = "GPX",
                updatedAt = Clock.System.now().toEpochMilliseconds(),
            ),
        )
        eventPointDao.deleteForEvent(event.id)
        insertGpxPoints(event.id, points)
    }

    /** Metadata-only edit for a GPX-sourced event that isn't re-importing a file — leaves points/window untouched. */
    suspend fun updateEventMetadata(
        event: EventEntity,
        title: String,
        type: String,
        carId: String?,
        locationLabel: String?,
        notes: String?,
    ) {
        eventDao.upsert(
            event.copy(
                title = title,
                type = type,
                carId = carId,
                locationLabel = locationLabel,
                notes = notes,
                updatedAt = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    suspend fun removeEvent(event: EventEntity) {
        eventPointDao.deleteForEvent(event.id)
        eventDao.delete(event)
    }

    /** Opts an event (and its GPS trace) into the community Feed. Actual visibility still
     * follows the account's global sharing level — this only says "shareable at all". */
    suspend fun setShared(eventId: String, shared: Boolean) {
        val event = eventDao.getById(eventId) ?: return
        eventDao.upsert(event.copy(isShared = shared, updatedAt = Clock.System.now().toEpochMilliseconds()))
    }

    private suspend fun insertGpxPoints(eventId: String, points: List<GpxPoint>) {
        if (points.isEmpty()) return
        eventPointDao.insertAll(
            points.map { p -> EventPointEntity(eventId = eventId, lat = p.lat, lng = p.lng, ts = p.ts, speedKmh = p.speedKmh) },
        )
    }

    private suspend fun cropPoints(eventId: String, deviceId: String?, startTs: Long, endTs: Long) {
        if (deviceId == null) return
        val points = locationPointDao.getInRange(deviceId, startTs, endTs)
        if (points.isEmpty()) return
        eventPointDao.insertAll(
            points.map { p ->
                EventPointEntity(eventId = eventId, lat = p.lat, lng = p.lng, ts = p.ts, speedKmh = p.speedKmh)
            },
        )
    }
}
