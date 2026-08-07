package com.carlauncher.companion.data.repo

import com.carlauncher.companion.data.cloud.PlatformContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToFile

/** UNVERIFIED on this machine (no Xcode/iOS SDK here) — mirrors `DatabaseBuilder.ios.kt`'s
 * Documents-directory pattern; needs a real compile on the Mac side. */
actual class PlatformFileStore actual constructor(private val context: PlatformContext) {

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun saveCarPhoto(carId: String, bytes: ByteArray): String {
        val dir = documentDirectory() + "/car_photos"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val path = "$dir/$carId.jpg"
        val data = bytes.usePinned {
            NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong())
        }
        data.writeToFile(path, atomically = true)
        return path
    }

    actual suspend fun deleteCarPhoto(path: String?) {
        path?.let { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun documentDirectory(): String {
        val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = false,
            error = null,
        )
        return requireNotNull(documentDirectory?.path)
    }
}
