package com.carlauncher.companion.data.cloud

/**
 * Phase 1 CI smoke test only (see iosApp/iosApp/ContentView.swift) — a Kotlin `object`
 * rather than a top-level function specifically because objects are exposed to Swift
 * unambiguously as `IosSmokeTest.shared.foo()`, unlike top-level package functions, which
 * Kotlin/Native's classic ObjC-interop framework export wraps in an unpredictable
 * per-source-file "<Mangled>Kt" class name that isn't worth guessing blind.
 */
object IosSmokeTest {
    fun bundledAssetCharCount(): Int =
        readBundledAsset(PlatformContext(), "departments_centroids.json").length
}
