import radar.GenerateRadarSectionsTask
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
}

// Supabase project URL + anon ("publishable") key.
//
// The anon key is public by design — it ships inside the APK and anyone can extract it.
// All access control therefore lives in Postgres RLS (see supabase/schema.sql), never
// here. It is kept out of git anyway, on the principle that credentials don't belong in
// version control even when they aren't secret. The service_role key must NEVER appear
// in this file or anywhere else in the repo: it bypasses RLS entirely.
//
// Set both in local.properties (gitignored):
//   supabase.url=https://<project>.supabase.co
//   supabase.anonKey=<anon key>
//   carto.apiKey=<your carto API key>
val supabaseProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun supabaseProp(key: String): String =
    (supabaseProps.getProperty(key) ?: providers.gradleProperty(key).orNull).orEmpty()

android {
    namespace = "com.carlauncher.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.carlauncher.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Empty fallbacks keep a fresh clone compiling; the app treats a blank URL/key as
        // "cloud disabled" and stays a fully working offline recorder.
        buildConfigField("String", "SUPABASE_URL", "\"${supabaseProp("supabase.url")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${supabaseProp("supabase.anonKey")}\"")
        buildConfigField("String", "CARTO_API_KEY", "\"${supabaseProp("carto.apiKey")}\"")
        // Bumping this string forces every user to re-accept the terms on next launch.
        buildConfigField("String", "TERMS_VERSION", "\"2026-08-03\"")

        // Host half of the auth deep link (scheme is per-flavor, below). Password-reset
        // emails link back to `<scheme>://<host>`; both must be allow-listed in the
        // Supabase dashboard under Authentication -> URL Configuration.
        buildConfigField("String", "AUTH_REDIRECT_HOST", "\"auth-callback\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Beta features (Bluetooth trigger, all Firebase, radars) live in `src/dev` — physically
    // absent from the prod APK rather than hidden behind a runtime flag. Shared `src/main` code
    // compiles against same-named seam classes declared once per flavor (real in `src/dev`,
    // no-op in `src/prod`), so there is no BuildConfig check anywhere.
    flavorDimensions += "channel"
    productFlavors {
        // Auth deep-link schemes are per-flavor: both APKs install side by side, and two
        // apps registering the same scheme would make Android show a disambiguation dialog
        // every time a password-reset link is tapped.
        create("dev") {
            dimension = "channel"
            resValue("string", "app_name", "Car Companion Dev")
            manifestPlaceholders["authRedirectScheme"] = "carcompaniondev"
            buildConfigField("String", "AUTH_REDIRECT_SCHEME", "\"carcompaniondev\"")
        }
        create("prod") {
            dimension = "channel"
            // Distinct id so both flavors install side-by-side on the same phone.
            applicationId = "com.shenzou.carcompanion"
            resValue("string", "app_name", "Car Companion")
            manifestPlaceholders["authRedirectScheme"] = "carcompanion"
            buildConfigField("String", "AUTH_REDIRECT_SCHEME", "\"carcompanion\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// The Kotlin Gradle plugin hard-errors on the old `android.kotlinOptions { jvmTarget = ... }`
// as of the Kotlin 2.3 bump (see the version comment in the root build.gradle.kts) — this is
// its replacement, the top-level `compilerOptions` DSL.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Average-speed cameras come out of the GPX assets as two unrelated points, so the line
// between an entry and its exit has to be derived. Doing it here rather than at runtime
// keeps the app free of a routing dependency and works offline. The GPX files are task
// inputs, so the asset is regenerated exactly when the radar data changes.
val generateRadarSections = tasks.register<GenerateRadarSectionsTask>("generateRadarSections") {
    group = "build"
    description = "Routes each average-speed camera entry/exit pair into assets/radar_sections.json"

    gpxFiles.from(
        layout.projectDirectory.dir("src/dev/assets/radars").asFileTree.matching {
            include("**/*.gpx")
        },
    )

    osrmBaseUrl.set(
        providers.gradleProperty("radarSections.osrmUrl").orElse("https://router.project-osrm.org"),
    )
    requestDelayMillis.set(
        providers.gradleProperty("radarSections.delayMs").map(String::toLong).orElse(1100L),
    )
    minPairMeters.set(
        providers.gradleProperty("radarSections.minMeters").map(String::toDouble).orElse(300.0),
    )
    maxPairMeters.set(
        providers.gradleProperty("radarSections.maxMeters").map(String::toDouble).orElse(30_000.0),
    )
    maxDetourRatio.set(
        providers.gradleProperty("radarSections.maxDetour").map(String::toDouble).orElse(2.0),
    )
    strict.set(
        providers.gradleProperty("radarSections.strict").map(String::toBoolean).orElse(false),
    )

    // Outside build/, so `clean` does not force a fresh round of routing requests.
    routeCacheDir.set(rootProject.layout.projectDirectory.dir(".gradle/osrm-route-cache"))
}

androidComponents {
    // Radars are a dev-only feature: the source GPX files live in src/dev/assets, so the derived
    // radar_sections.json is neither generated nor bundled for prod.
    onVariants(selector().withFlavor("channel" to "dev")) { variant ->
        // Carries the task dependency through to mergeAssets on its own; srcDir() would not.
        variant.sources.assets?.addGeneratedSourceDirectory(
            generateRadarSections,
            GenerateRadarSectionsTask::outputDir,
        )
    }
}

// supabase-kt's auth-kt-android (for CustomTabs OAuth, which this app never triggers — it
// only does email/password) and firebase-auth both pull in androidx.browser, which Google
// resolves to the newest version on the classpath. 1.9.0+ requires compileSdk 36 and AGP
// 8.9.1+, which this project deliberately isn't on yet (see the AGP pin in the root
// build.gradle.kts). Pinned to the last version below that floor.
configurations.all {
    resolutionStrategy {
        force("androidx.browser:browser:1.8.0")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    // Pulls in the Theme.Material3.* manifest theme used before Compose takes over
    // (splash/status-bar chrome); the androidx.compose.material3 artifact only ships
    // Compose APIs, not these XML theme resources.
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.navigation:navigation-compose:2.8.1")

    // Firebase — dev-only: prod is a purely local-GPS app with no remote/paired-car integration,
    // so none of this is linked into the prod APK at all. Quoted configuration names on purpose:
    // Kotlin-DSL typed accessors like `devImplementation(...)` are generated from the *previous*
    // build's model, so they don't resolve in the very script that declares the flavor.
    "devImplementation"(platform("com.google.firebase:firebase-bom:33.7.0"))
    "devImplementation"("com.google.firebase:firebase-auth")
    "devImplementation"("com.google.firebase:firebase-firestore")
    "devImplementation"("com.google.firebase:firebase-messaging")

    // Business/data layer (models, Room DB, stats/trophy calculators, ...) — shared with the
    // iOS app via Kotlin Multiplatform. Room3 runtime/KSP live in :shared's own build script.
    implementation(project(":shared"))

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Only for Task.await() on Firestore calls — dev-only alongside Firebase itself.
    "devImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // osmdroid (OpenStreetMap renderer)
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Android Auto (phone-projected) — only used to trigger the dev-only radar alert service.
    "devImplementation"("androidx.car.app:app:1.7.0")

    // Supabase (auth-kt/postgrest-kt), the Ktor engine, EncryptedSharedPreferences and
    // kotlinx-serialization-json all now live in :shared's own build script (its cloud layer
    // moved there for the iOS port) and reach here transitively via that module's `api`
    // surface — MainActivity is the only :app call site left that touches supabase-kt
    // directly (`client.handleDeeplinks(...)` for the password-reset deep link).

    // Deferred, constraint-aware cloud upload (needs network, survives process death).
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
