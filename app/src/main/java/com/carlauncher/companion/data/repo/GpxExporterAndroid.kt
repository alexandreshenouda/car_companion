package com.carlauncher.companion.data.repo

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun GpxExporter.writeToUri(context: Context, uri: Uri, gpxContent: String) = withContext(Dispatchers.IO) {
    context.contentResolver.openOutputStream(uri)?.use { out ->
        out.write(gpxContent.toByteArray(Charsets.UTF_8))
    }
}
