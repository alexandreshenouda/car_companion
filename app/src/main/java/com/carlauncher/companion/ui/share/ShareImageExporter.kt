package com.carlauncher.companion.ui.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.core.content.FileProvider
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Mirrors [com.carlauncher.companion.data.repo.GpxExporter]'s pure-build + suspend-I/O style. */
object ShareImageExporter {

    /** Captures exactly the rendered pixels within [bounds] of [window], PixelCopy is robust to embedded AndroidViews. */
    suspend fun capture(window: Window, bounds: Rect): Bitmap =
        suspendCancellableCoroutine { continuation ->
            val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
            PixelCopy.request(
                window,
                bounds,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        continuation.resume(bitmap)
                    } else {
                        continuation.resumeWithException(IllegalStateException("PixelCopy failed: $result"))
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }

    suspend fun saveToCache(context: Context, bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared_images").apply { mkdirs() }
        val file = File(dir, "trip_share_${System.currentTimeMillis()}.png")
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun buildShareIntent(uri: Uri): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, "Share trip")
    }
}
