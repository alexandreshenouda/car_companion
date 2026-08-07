package com.carlauncher.companion.data.repo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.carlauncher.companion.data.cloud.PlatformContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual class PlatformFileStore actual constructor(private val context: PlatformContext) {

    actual suspend fun saveCarPhoto(carId: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val dir = File(context.context.filesDir, "car_photos").apply { mkdirs() }
        val file = File(dir, "$carId.jpg")
        file.writeBytes(downscaleAndCompress(bytes))
        file.absolutePath
    }

    actual suspend fun deleteCarPhoto(path: String?) = withContext(Dispatchers.IO) {
        path?.let { File(it).delete() }
        Unit
    }

    actual suspend fun readCarPhoto(path: String): ByteArray? = withContext(Dispatchers.IO) {
        File(path).takeIf { it.exists() }?.readBytes()
    }

    /** Caps the long edge at [MAX_DIMENSION_PX] and steps `JPEG` quality down until the
     * result fits [MAX_BYTES] (or quality bottoms out) — keeps a garage's worth of photos a
     * small fraction of the 1GB free-tier Storage quota once they're pushed to the cloud. */
    private fun downscaleAndCompress(bytes: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_DIMENSION_PX) sampleSize *= 2

        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
            ?: return bytes
        val scale = MAX_DIMENSION_PX.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else {
            decoded
        }

        var quality = 90
        var out = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, this) }
        while (out.size() > MAX_BYTES && quality > MIN_QUALITY) {
            quality -= 10
            out = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, this) }
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_DIMENSION_PX = 960
        const val MAX_BYTES = 300 * 1024
        const val MIN_QUALITY = 40
    }
}
