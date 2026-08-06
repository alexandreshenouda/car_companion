package com.carlauncher.companion.data.cloud

actual fun readBundledAsset(context: PlatformContext, name: String): String =
    context.context.assets.open(name).bufferedReader().use { it.readText() }
