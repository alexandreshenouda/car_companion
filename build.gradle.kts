plugins {
    // AGP is deliberately not on the bleeding-edge 9.x line: it targets the last
    // well-documented, widely battle-tested 8.x release rather than guessing at very recent
    // major-version migration requirements.
    //
    // Kotlin/KSP/AGP were bumped from 2.1.20/8.7.3 for a forcing reason, not a preference:
    // every current supabase-kt 3.x release (data/cloud/, added for Supabase accounts/sync) is
    // compiled with Kotlin 2.3+, so 2.1.20 cannot load its metadata at all. Kotlin/KSP 2.3.10 is
    // a ceiling rather than a choice too — it's the newest version KSP (which Room's annotation
    // processing needs) has a release for; Kotlin 2.4 has no matching KSP yet. AGP 8.10.0 is
    // KSP's own stated floor (it errors explicitly below that) — still inside Kotlin 2.3.0's
    // documented AGP support range (8.2.2–8.13.0), and compileSdk 35 remains untouched.
    id("com.android.application") version "8.10.0" apply false
    id("com.android.library") version "8.10.0" apply false
    id("org.jetbrains.kotlin.android") version "2.3.10" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.3.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.10" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
    // :shared's Room DB — needs the KMP-capable Room 3.0 line (androidx.room3), not the
    // classic androidx.room used by nothing anymore now that data/db moved to :shared. Room 3.0
    // reached stable (3.0.1) in July 2026, so this isn't an alpha pin.
    id("androidx.room3") version "3.0.1" apply false
}
