plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("androidx.room3")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // iosX64 (Intel simulator) intentionally omitted — only Apple Silicon Macs are used here.
    // Each target gets a "Shared" framework so Xcode can `import Shared`; embedding uses the
    // Kotlin Gradle plugin's built-in embedAndSignAppleFrameworkForXcode task (a Run Script
    // build phase — see the Phase 6 setup notes in README.md), not CocoaPods.
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            // Static: one fewer moving part for a single-app-target project (no dynamic
            // framework code-signing/embedding subtleties). Revisit if a second consumer
            // (e.g. a widget extension) needs the same framework later.
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: :app still constructs AppDatabase/DAOs directly (that
            // moves into :shared's own DI root in a later phase), so it needs Flow/RoomDatabase
            // types on its compile classpath too.
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")

            // Room KMP: only the room3 line targets iOS (see version comment in root
            // build.gradle.kts). sqlite-bundled ships SQLite itself, so no OS-provided driver
            // dependency is needed on either platform.
            api("androidx.room3:room3-runtime:3.0.1")
            api("androidx.sqlite:sqlite-bundled:2.7.0")

            // CryptoBox's E2E encryption (AES-GCM + PBKDF2): a pure-Kotlin multiplatform
            // provider rather than separate javax.crypto (Android) / CryptoKit (iOS)
            // implementations, so the security-critical code path — and its test suite —
            // is identical on both platforms instead of two implementations that could
            // silently diverge. "optimal" auto-selects JDK on Android, CryptoKit/OpenSSL3
            // on iOS.
            implementation("dev.whyoleg.cryptography:cryptography-core:0.6.0")
            implementation("dev.whyoleg.cryptography:cryptography-provider-optimal:0.6.0")

            // Gzip for backup payloads (compress-then-encrypt): platform-specific via
            // expect/actual (androidMain: java.util.zip, unchanged; iosMain: platform.zlib).
            // The one KMP gzip library evaluated (no.synth:kmp-zip) ships JVM bytecode
            // requiring JDK 21+ at runtime on every recent release, incompatible with this
            // project's deliberate JVM 17 pin (see root build.gradle.kts).

            // Encrypted key-value storage for session tokens / the E2E data-encryption-key:
            // androidMain wraps the existing EncryptedSharedPreferences (Keystore-backed)
            // logic unchanged; iosMain wraps Keychain Services via this library's
            // (experimental but production-used) KeychainSettings, rather than hand-rolled
            // Security-framework cinterop.
            implementation("com.russhwolf:multiplatform-settings:1.3.0")

            // Supabase — accounts, cloud backup, the social Feed. `api`, not `implementation`:
            // :app still calls supabase-kt extension functions directly (MainActivity's
            // `client.handleDeeplinks(...)` for the password-reset deep link), so it needs
            // these types on its own compile classpath via this module's api surface.
            // Pinned to 3.6.0 to match :app's Kotlin/KSP pin — see the version comment in
            // app/build.gradle.kts, which no longer declares this dependency directly.
            // The BOM itself is applied below, in the plain `dependencies {}` block — the
            // KotlinDependencyHandler used inside a source set's `.dependencies {}` block
            // resolves `platform(...)` to a deprecated overload (KT-58759) that Kotlin 2.3
            // hard-errors on.
            api("io.github.jan-tennert.supabase:auth-kt")
            api("io.github.jan-tennert.supabase:postgrest-kt")
            api("io.github.jan-tennert.supabase:storage-kt")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        androidMain.dependencies {
            // supabase-kt/Ktor auto-select an engine by classpath — no code branching needed.
            implementation("io.ktor:ktor-client-okhttp:3.0.3")
            // EncryptedSharedPreferences — session tokens/DEK are never written in plaintext.
            implementation("androidx.security:security-crypto:1.1.0-alpha06")
        }
        iosMain.dependencies {
            // Ktor's Darwin engine (NSURLSession-backed). Matches the OkHttp pin on Android —
            // same 3.6.0-line supabase-kt BOM this project already forces.
            implementation("io.ktor:ktor-client-darwin:3.0.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
        }
    }
}

android {
    namespace = "com.carlauncher.companion.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", "androidx.room3:room3-compiler:3.0.1")
    add("kspIosArm64", "androidx.room3:room3-compiler:3.0.1")
    add("kspIosSimulatorArm64", "androidx.room3:room3-compiler:3.0.1")

    // See the comment on the auth-kt/postgrest-kt api(...) declarations above for why the
    // BOM is applied here instead of inside `sourceSets { commonMain.dependencies { ... } } }`.
    add("commonMainApi", platform("io.github.jan-tennert.supabase:bom:3.6.0"))
}
