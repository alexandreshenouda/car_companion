package com.carlauncher.companion.data.cloud

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.NSUTF8StringEncoding

/**
 * [name] must be added to the iOS app target's bundle resources (see iosApp/project.yml's
 * `resources:` entry) for `pathForResource` to find it; the Android actual's asset merge
 * from `androidMain/assets/` has no iOS equivalent, so this is a manual step per file.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun readBundledAsset(context: PlatformContext, name: String): String {
    val dotIndex = name.lastIndexOf('.')
    val baseName = if (dotIndex >= 0) name.substring(0, dotIndex) else name
    val extension = if (dotIndex >= 0) name.substring(dotIndex + 1) else null
    val path = requireNotNull(NSBundle.mainBundle.pathForResource(baseName, extension)) {
        "Bundled asset '$name' not found — was it added to the iOS app target's bundle resources?"
    }
    return NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) as String
}
