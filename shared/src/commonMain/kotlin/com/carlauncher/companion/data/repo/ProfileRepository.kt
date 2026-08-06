package com.carlauncher.companion.data.repo

import com.carlauncher.companion.data.db.UserProfileDao
import com.carlauncher.companion.data.db.UserProfileEntity
import kotlinx.coroutines.flow.Flow

class ProfileRepository(private val profileDao: UserProfileDao) {
    fun observe(): Flow<UserProfileEntity?> = profileDao.observe()

    suspend fun update(age: Int?, city: String?, departmentCodes: String?) {
        profileDao.upsert(UserProfileEntity(age = age, city = city, departmentCodes = departmentCodes))
    }
}
