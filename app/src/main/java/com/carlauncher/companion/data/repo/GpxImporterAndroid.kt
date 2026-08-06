package com.carlauncher.companion.data.repo

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android's half of [GpxImporter]: reads a picked file `Uri` into text, then hands it to the
 * shared, pure [GpxImporter.parse]. iOS's own document-picker equivalent lives in the iOS app
 * target, calling the same [GpxImporter.parse]. */
suspend fun GpxImporter.importFrom(context: Context, uri: Uri): List<GpxPoint> = withContext(Dispatchers.IO) {
    val xml = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        ?: return@withContext emptyList()
    parse(xml)
}
