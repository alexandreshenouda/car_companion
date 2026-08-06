package com.carlauncher.companion.data.repo

import com.carlauncher.companion.data.db.CarDao
import com.carlauncher.companion.data.db.CarEntity
import com.carlauncher.companion.data.db.CarModificationDao
import com.carlauncher.companion.data.db.CarModificationEntity
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
class CarRepository(
    private val carDao: CarDao,
    private val modificationDao: CarModificationDao,
    private val photoStore: PlatformFileStore,
) {
    fun observeCars(): Flow<List<CarEntity>> = carDao.observeAll()

    fun observeCar(carId: String): Flow<CarEntity?> = carDao.observe(carId)

    fun observeFavoriteCar(): Flow<CarEntity?> = carDao.observeFavorite()

    suspend fun getCar(carId: String): CarEntity? = carDao.getById(carId)

    /** At most one car is favorite at a time — setting one clears any previous favorite. */
    suspend fun setFavorite(carId: String) = carDao.setFavorite(carId)

    suspend fun clearFavorite() = carDao.clearFavorite()

    suspend fun addCar(
        name: String,
        deviceId: String?,
        brand: String?,
        model: String?,
        year: Int?,
        details: String?,
        odometerKm: Double?,
    ): String {
        val id = Uuid.random().toString()
        val now = Clock.System.now().toEpochMilliseconds()
        carDao.upsert(
            CarEntity(
                id = id,
                deviceId = deviceId,
                name = name,
                brand = brand,
                model = model,
                year = year,
                details = details,
                odometerKm = odometerKm,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    suspend fun updateCar(car: CarEntity) {
        carDao.upsert(car.copy(updatedAt = Clock.System.now().toEpochMilliseconds()))
    }

    /** Opts a car into the community Feed. Actual visibility still follows the account's
     * global sharing level (private/friends/everyone) — this only says "shareable at all". */
    suspend fun setShared(carId: String, shared: Boolean) {
        val car = carDao.getById(carId) ?: return
        carDao.upsert(car.copy(isShared = shared, updatedAt = Clock.System.now().toEpochMilliseconds()))
    }

    /** [bytes] is already-read photo data — each platform's UI layer reads its own picker
     * result (Android `Uri`, iOS `PHPickerViewController`) into bytes before calling this. */
    suspend fun updatePhoto(carId: String, bytes: ByteArray) {
        val car = carDao.getById(carId) ?: return
        val path = photoStore.saveCarPhoto(carId, bytes)
        // Not `updatedAt`-bumping: photos are never uploaded, so a photo-only change has
        // nothing for the cloud sync to push — bumping it would just waste an upload cycle
        // re-sending unchanged fields.
        carDao.upsert(car.copy(photoPath = path))
    }

    suspend fun removeCar(car: CarEntity) {
        photoStore.deleteCarPhoto(car.photoPath)
        modificationDao.deleteAllForCar(car.id)
        carDao.delete(car)
    }

    fun observeModifications(carId: String): Flow<List<CarModificationEntity>> =
        modificationDao.observeForCar(carId)

    suspend fun addModification(
        carId: String,
        title: String,
        category: String?,
        installedAt: Long,
        cost: Double?,
        notes: String?,
    ) {
        modificationDao.upsert(
            CarModificationEntity(
                carId = carId,
                title = title,
                category = category,
                installedAt = installedAt,
                cost = cost,
                notes = notes,
            ),
        )
        touchForModification(carId)
    }

    suspend fun removeModification(modification: CarModificationEntity) {
        modificationDao.delete(modification)
        touchForModification(modification.carId)
    }

    /**
     * Modifications carry no `updatedAt` of their own — the cloud sync re-pushes a car's whole
     * modification list whenever the *car* is dirty, so a mod-only edit has to bump the parent
     * car's `updatedAt` or it would never be noticed.
     */
    private suspend fun touchForModification(carId: String) {
        carDao.getById(carId)?.let { carDao.upsert(it.copy(updatedAt = Clock.System.now().toEpochMilliseconds())) }
    }
}
