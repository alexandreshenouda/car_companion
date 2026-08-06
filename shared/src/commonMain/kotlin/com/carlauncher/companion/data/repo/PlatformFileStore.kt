package com.carlauncher.companion.data.repo

import com.carlauncher.companion.data.cloud.PlatformContext

/**
 * Saves/deletes a car photo in permanent app storage — Android: `context.filesDir`, iOS:
 * the app's Documents directory. Photos are only ever displayed in-app, never shared out
 * (unlike [com.carlauncher.companion.ui.share.ShareImageExporter]'s cache+FileProvider path),
 * so no share-sheet plumbing is needed here.
 *
 * Takes raw bytes rather than a platform picker handle (`android.net.Uri` /
 * `PHPickerViewController` result) — each platform's own UI layer reads the picked photo into
 * bytes before calling [CarRepository.updatePhoto], so this stays a pure storage primitive.
 */
expect class PlatformFileStore(context: PlatformContext) {
    suspend fun saveCarPhoto(carId: String, bytes: ByteArray): String
    suspend fun deleteCarPhoto(path: String?)
}
