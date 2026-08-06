package com.carlauncher.companion.data.cloud

/** Reads a text file bundled with the app: Android's `assets/` (this module's own
 * `androidMain/assets/`, merged into whichever app depends on it), iOS's app bundle
 * (`NSBundle.mainBundle`). Not under `data.repo` since it's a platform-access primitive rather
 * than business logic — [com.carlauncher.companion.data.repo.DepartmentLocator] is its only
 * caller today. */
expect fun readBundledAsset(context: PlatformContext, name: String): String
