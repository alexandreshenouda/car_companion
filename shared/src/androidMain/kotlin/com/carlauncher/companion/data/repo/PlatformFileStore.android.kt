package com.carlauncher.companion.data.repo

import com.carlauncher.companion.data.cloud.PlatformContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual class PlatformFileStore actual constructor(private val context: PlatformContext) {

    actual suspend fun saveCarPhoto(carId: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val dir = File(context.context.filesDir, "car_photos").apply { mkdirs() }
        val file = File(dir, "$carId.jpg")
        file.writeBytes(bytes)
        file.absolutePath
    }

    actual suspend fun deleteCarPhoto(path: String?) = withContext(Dispatchers.IO) {
        path?.let { File(it).delete() }
        Unit
    }
}
