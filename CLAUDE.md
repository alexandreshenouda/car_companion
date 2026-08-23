# Car Companion

Android phone app showing where the user's car is / when it last started. It's the
counterpart to a separate custom car head-unit launcher project
(`porsche_launcher_android`, sibling repo at `/home/alexandre/git/porsche_launcher_android`),
which pushes GPS + lifecycle events to a shared Firebase project (`car-tracking-fc69c`).
This app reads that data and also bundles offline speed-camera/radar data (25
countries) shown on an OSM map, with a Bluetooth/Android-Auto-triggered foreground
alert service. See `README.md` and `FIREBASE_START_EVENT.md` for fuller design docs —
prefer reading those over re-deriving from source.

## Build flavors (`dev` / `prod`) — read this before touching anything
Two APKs from one codebase, split on the `channel` flavor dimension. `dev` =
everything (applicationId `com.carlauncher.companion`, label "Car Companion Dev");
`prod` = stable features only (applicationId `com.shenzou.carcompanion`, label
"Car Companion"). They install side by side.

**Beta = dev-only**: the Bluetooth car trigger, *all* Firebase functionality (device
discovery, Firestore sync, the "car started" FCM push), and radars (map markers /
section lines / proximity alerts). Everything else is in both flavors — prod is a
purely local, offline trip recorder.

Beta code is physically absent from prod, **not** behind a runtime flag (explicit user
requirement — never reintroduce a `BuildConfig` beta boolean). It lives in
`app/src/dev/{java,assets,AndroidManifest.xml}`, so prod links no Firebase, no
`androidx.car.app`, bundles no radar GPX, and declares none of the Bluetooth /
background-location / battery-optimization permissions or the FCM / radar-alert /
BT-receiver components.

`src/main` reaches beta code only through **five seams** — same FQ name and identical
public API, declared once per flavor (real in `src/dev/java`, no-op in `src/prod/java`):

| Seam (path under `com/carlauncher/companion/`) | dev | prod |
|---|---|---|
| `data/BetaContainer.kt` | `radarRepository`/`sectionRepository`/`bluetoothTriggerStore`/`backgroundFeatureSettings`, exposed as `AppContainer.beta` | empty class |
| `data/repo/RemoteTrackSync.kt` | Firestore discover/live-tail/backfill/delete; `TrackRepository` delegates to it and keeps its full public API, so no call sites changed | same signatures, empty results |
| `BetaAppInitializer.kt` | `initialize()` = the Firebase/push/radar-trigger half of `CompanionApp.onCreate()`; `initializeActivity()` = `MainActivity`'s battery-opt prompt | both empty |
| `ui/nav/BetaNavEntries.kt` | `betaDestinations()` (Devices + BluetoothTrigger + Settings routes), `BetaTopBarIcons()`, `BetaAddCarAction()`, `showMainTopBar` (`true`) | all empty, `showMainTopBar` (`false`) |
| `ui/map/RadarControls.kt` | `RadarOverlayState`/`rememberRadarOverlays()`/`RadarControls()` — radar state, viewport loading, overlay drawing, filter pill, background-location request | inert state, draws nothing |

Adding a beta feature = put it in `src/dev` and, if shared code must call it, extend a
seam **in both flavors**. `Destination.Devices`/`Destination.BluetoothTrigger` stay in
`src/main/.../ui/nav/Destinations.kt` — harmless unreachable route strings in prod.

## Functionality overview
(See `README.md` "What it does" for the canonical, user-facing list — kept in sync
with shipped features. Summary for quick orientation; **[dev]** marks the beta features
that don't exist in the prod flavor:)
- **Map** (`ui/map/MapScreen.kt`) — car's live position on osmdroid/dark tiles, plus
  **[dev]** radar markers (25 countries, filterable by type) and average-speed
  "Troncon" section polylines (both via the `ui/map/RadarControls.kt` seam).
- **History** (`ui/history/HistoryScreen.kt`) — past tracks by range (today/7d/30d/
  all), GPX export (`GpxExporter.kt`, pure build in `:shared`'s `data/repo/`, `Uri`
  writing in `:app`'s `data/repo/GpxExporterAndroid.kt`).
- **Stats** (`ui/stats/StatsScreen.kt`) — distance/speed/moving-time over a range
  (`data/repo/StatsCalculator.kt`).
- **Share** (`ui/share/ShareScreen.kt`, `ui/share/ShareImageExporter.kt`) — 4 card
  templates rendered to PNG via `PixelCopy` + `FileProvider`, handed to the system
  share sheet.
- **Devices** **[dev]** (`ui/devices/DevicesScreen.kt`) — auto-discovered or
  manually-added cars by `deviceId`, nickname/brand/model editing. Prod only ever has
  the seeded local "This phone" device, so its switcher is a single-item dropdown.
- **"Car started" push** **[dev]** — data-only FCM (topic `launcher_events`) handled in
  `push/CompanionFcmService.kt`; requires notification permission + battery-optimization
  exemption to arrive reliably when backgrounded/killed. Full design in
  `FIREBASE_START_EVENT.md` (sender side lives in the launcher repo:
  `com/carlauncher/pcm/FcmPusher.kt`, `AppAuth.kt`).
- **Radar proximity alerts** **[dev]** (`car/RadarAlertService.kt`, foreground service,
  `foregroundServiceType="location"`) — runs during an active trip, including on
  Android Auto; math in `car/RadarAlertEngine.kt` / `car/DangerBar.kt`.
- **Bluetooth trigger** **[dev]** (`ui/bluetooth/BluetoothTriggerScreen.kt`,
  `data/bluetooth/BluetoothTriggerStore.kt`, `car/CarBluetoothReceiver.kt`) — once a
  device is configured, it's the sole authority starting/stopping
  `RadarAlertService` (supersedes Android Auto's `CarConnection` signal). Persisted as
  Android shared-prefs XML at
  `/data/data/com.carlauncher.companion/shared_prefs/bluetooth_trigger.xml`.
- **Settings** **[dev]** (`ui/settings/SettingsScreen.kt`, gear icon in the main screen's
  top bar) — two kill switches over `data/settings/BackgroundFeatureSettings.kt` (both
  on by default): "car-started push notifications" (FCM topic subscribe/unsubscribe +
  early-return in `CompanionFcmService.onMessageReceived`) and "background radar checks"
  (gates every `RadarAlertService` start path — `BetaAppInitializer.observeCarConnection`,
  `BluetoothCarDetector`, `CarBluetoothReceiver.startTracking`, the FCM-triggered start —
  plus a belt-and-braces check in `RadarAlertService.onCreate` against a `START_STICKY`
  restart, and stops the service immediately on toggle-off regardless of what started it).
- **Trophies** (`ui/trophies/TrophiesScreen.kt`, reached from Profile) — 29 unlockable
  achievements over the lifetime driving history of *all* devices combined; pure
  definitions in `:shared`'s `data/model/Trophy.kt`, icon/title/description decoration
  in `:app`'s `data/model/TrophyUi.kt`, scoring in `:shared`'s
  `data/repo/TrophyEvaluator.kt` (pure) + `data/repo/TrophyRepository.kt` (paging/IO).
  Re-scored on app start, on trip end (either tracking service's `onDestroy`), and on
  screen open.
- **Profile** (`ui/profile/`, 4th bottom tab) — personal info (age/city/French
  départements), **Garage** (`ui/garage/`: owned cars, optionally linked to a tracked
  device, photo/odometer/modifications, one markable as favorite), and **Events**
  (`ui/events/`: car meets/racetrack days/explorations, GPS points either cropped from
  a linked device or imported from a GPX file via `data/repo/GpxImporter.kt`, shown on
  a compact trace map and exportable back to GPX). See `README.md` "What it does" for
  the full breakdown — this is the source of truth for the feature.

## Live testing (real physical device over wireless ADB)
No emulator has been used in this project's sessions — testing is on a real phone
connected via wireless ADB (mDNS-style serial like
`adb-3B220DLJH0007K-n08sd3._adb-tls-connect._tcp`, changes across sessions/reboots —
run `adb devices -l` first and use `-s <serial>` if more than one device is listed;
omit `-s` entirely if only one is attached). No screenshots/screen-mirroring have been
used to verify UI — verification is via `adb logcat` + `dumpsys`, or by asking the
user to check the physical screen for anything visual.

**Build + install** (all tasks are flavor-qualified now — `Dev` is the day-to-day
default; unqualified `assembleDebug` builds *both* flavors):
```bash
export JAVA_HOME=/home/alexandre/.jdks/jbr-17.0.14   # see gradle-build-jdk-setup memory
./gradlew :app:assembleDevDebug && adb install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
./gradlew :app:assembleProdDebug && adb install -r app/build/outputs/apk/prod/debug/app-prod-debug.apk
```

**Launch / kill** (prod's applicationId is `com.shenzou.carcompanion`; substitute it in
any of these when testing that flavor — `namespace`, and so the `.MainActivity` class
name, is the same for both):
```bash
adb shell am start -n com.carlauncher.companion/.MainActivity
adb shell am force-stop com.carlauncher.companion   # full kill (simulates "app closed")
adb shell am kill com.carlauncher.companion          # background kill (process death, task stays)
adb shell input keyevent KEYCODE_HOME                # send to background without killing
```
Swipe-away from recents (to test "app removed from recents" paths):
```bash
adb shell input keyevent KEYCODE_APP_SWITCH; sleep 2
adb shell input swipe 540 1000 540 100 400
```

**Start the radar alert service directly** (bypasses the BT/Android-Auto trigger,
useful for isolating `RadarAlertEngine`/notification bugs):
```bash
adb shell am start-foreground-service -n com.carlauncher.companion/.car.RadarAlertService \
  --ez triggered_by_bluetooth true
```

**Simulate the Bluetooth trigger** (toggling the adapter is how BT connect/disconnect
to the configured car device was simulated — a real paired device isn't needed):
```bash
adb logcat -c
adb shell cmd bluetooth_manager disable >/dev/null 2>&1; sleep 5
adb shell cmd bluetooth_manager enable  >/dev/null 2>&1; sleep 20   # give ACL reconnect time
```

**Inspect/seed `BluetoothTriggerStore` state directly** (faster than driving the UI to
pair a device; `run-as` can't read from the host filesystem directly, so push to
`/data/local/tmp` first, then `cp` into the app's private dir):
```bash
# read
adb shell run-as com.carlauncher.companion cat \
  /data/data/com.carlauncher.companion/shared_prefs/bluetooth_trigger.xml

# write (edit a local bt.xml under the scratchpad first, matching the real shared-prefs format)
adb push bt.xml /data/local/tmp/bt.xml && adb shell chmod 644 /data/local/tmp/bt.xml
adb shell am force-stop com.carlauncher.companion
adb shell run-as com.carlauncher.companion cp /data/local/tmp/bt.xml \
  /data/data/com.carlauncher.companion/shared_prefs/bluetooth_trigger.xml
adb shell rm /data/local/tmp/bt.xml
```

**Logs / diagnostics:**
```bash
adb logcat -c                                    # clear before reproducing
adb logcat -d -b crash 2>&1 | tail -200           # crash buffer only
adb logcat -d -v time -b all 2>&1 | grep -iE "companion|radar|bluetooth"
adb shell dumpsys package com.carlauncher.companion | grep -iE "permission|version"
adb shell dumpsys appops --package com.carlauncher.companion | grep -A25 -iE "location|notif"
adb shell dumpsys notification | grep -iE "companion"
adb shell dumpsys bluetooth_manager | grep -iE "connected|state"
```

**Known constraints:**
- `app/src/dev/assets/radars/*.gpx` is gitignored (third-party Lufop/OsmAnd data,
  repo is public) — a fresh clone has no radar markers and `generateRadarSections`
  has nothing to pair until the 25 country files are dropped in manually. See
  README's "Radar data" section for the file format/naming and where to source it.
- `assembleDebug` is flaky in a sandboxed/headless dev shell (JVM SIGSEGV in the
  Gradle daemon under D8) — see `gradle-build-jdk-setup` memory. Verify with
  `./gradlew :app:compileDevDebugKotlin :app:compileProdDebugKotlin` first (both
  flavors — a seam that only exists in one of them compiles fine in the other); only
  run a full `assemble*Debug` (with retries + `./gradlew --stop` between them) when an
  installable APK is actually needed.
- `app/google-services.json` must contain a client entry for **both** application ids.
  The `com.google.gms.google-services` plugin validates every variant against it even
  though prod links no Firebase library, so a missing `com.shenzou.carcompanion` entry
  fails `assembleProdDebug` with "No matching client found for package name".
- `JAVA_HOME` has two known-good values on this machine —
  `/home/alexandre/.jdks/jbr-17.0.14` and
  `/home/alexandre/android-studio-quail2-linux/android-studio/jbr` — both have been
  used successfully; either works.
- `:app`'s unit tests are flavor-qualified: `./gradlew :app:testDevDebugUnitTest`.
  `buildSrc`'s suite is unflavored and run in isolation: `./gradlew -p buildSrc test`.
- `adb shell dumpsys package` / `run-as` require the app to be `debuggable` (true for
  the debug build variant used here).

## Module structure
Two Gradle modules: `:app` and `:shared` (see `settings.gradle.kts`), plus `buildSrc`
(build-logic, not an app module) containing `radar.GenerateRadarSectionsTask` and
`radar.SectionPairing` (`buildSrc/src/main/kotlin/radar/*.kt`) — pairs average-speed
camera entry/exit GPX points via OSRM routing **at build time**, writing
`radar_sections.json` into assets (wired in `app/build.gradle.kts`, dev variants only).

**`:shared`** is a Kotlin Multiplatform module (`androidTarget()` + `iosArm64()` +
`iosSimulatorArm64()`) added for the in-progress iOS port — see README.md's "iOS port"
section for the full architecture (module boundary, `expect`/`actual` seams, Mac-side
setup checklist). It holds the Room DB, `data/repo/*` business logic, and the full
`data/cloud/*` Supabase layer; `:app` depends on it (`implementation(project(":shared"))`
in `app/build.gradle.kts`) and owns 100% of the Compose UI, unchanged. Package
structure below still describes `:app`'s own `com/carlauncher/companion/` tree; anything
now living in `:shared` instead (most of `data/repo/`, all of `data/cloud/`, `data/db/`)
is called out where it's referenced rather than duplicated here — check
`shared/src/commonMain/kotlin/com/carlauncher/companion/` if a path below isn't where
you expect it.

The `:app` module has three source sets: `src/main` (shared), `src/dev` and `src/prod`
(flavor halves — see Build flavors above). Paths below are under `src/main` unless
tagged **[dev]** (`app/src/dev/java/...`) or **[prod]**.

## Package structure

### `:shared` (`shared/src/{commonMain,androidMain,iosMain,commonTest}/kotlin/com/carlauncher/companion/`)
Everything here is business/data logic reused by both platforms — no Compose, no
Android-only types except behind an `expect`/`actual` seam. Paths are under `commonMain`
unless tagged.
- `data/cloud/` — Supabase: accounts, opt-in backup, community sharing, all in
  `commonMain` (auth-kt/postgrest-kt are themselves KMP; only the Ktor engine is
  per-platform — `okhttp` **[androidMain]** / `darwin` **[iosMain]**).
  `SupabaseClientProvider.kt` (config strings passed in by each platform's DI root, not
  read from `BuildConfig` directly — `:shared` stays config-source-agnostic; inert when
  blank, so the app stays usable offline/signed-out), `AuthRepository.kt`,
  `CloudPrefsRepository.kt` (the six upload toggles + visibility, pure — see
  `:app`'s `CloudPrefsLabels.kt` for the `@StringRes`/icon decoration, split out because
  Android resources aren't reachable from a KMP module), `EncryptedSessionManager.kt`
  (tokens at rest, via `PlatformContext`/`createSecureSettings.kt` — **[androidMain]**
  wraps the existing Keystore-backed `EncryptedPrefs.kt`, **[iosMain]** wraps Keychain via
  `multiplatform-settings`'s `KeychainSettings`), `crypto/CryptoBox.kt` + `crypto/KeyVault.kt`
  (E2E encryption of GPS/stats, on `dev.whyoleg.cryptography` rather than
  `javax.crypto`/CryptoKit so the code and its test suite are identical on both
  platforms), `crypto/Base64Codec.kt` (`kotlin.io.encoding.Base64` — every blob sent
  through PostgREST travels as base64 text, never raw `bytea`). `CloudSyncManager.kt` is
  the one-way local→cloud push (dirty-tracked via `updatedAt`/`cloudSyncedAt` on
  `cars`/`events`/`trophy_unlocks`; GPS/stats go through `CryptoBox` first) — run by
  `:app`'s `CloudSyncWorker.kt` (WorkManager, Android-only; iOS gets a `BackgroundTasks`
  equivalent in Phase 6) or the "Sync now" button. `CloudRestoreManager.kt` is the one
  deliberate pull — user-triggered only; it takes an `onGpsRestored` callback instead of
  depending on `TrophyRepository` directly, since that would be a dependency in the
  wrong direction if `TrophyRepository` ever needed to move back out. `PolylineCodec.kt`
  (Google-style encoded polyline, precision 5) is how an event's GPS trace is stored as
  one `event_tracks` row instead of one row per point. `FriendsRepository.kt` wraps the
  friend-graph RPCs (`find_user_by_username` — exact match only, rate-limited, the sole
  discovery path in the app; `send_friend_request`, `respond_friend_request`,
  `block_user`, `get_friends()`). `FeedRepository.kt` pages the `get_feed` RPC (activity
  cards — car added/shared, event shared, trophy unlocked). `SharedContentRepository.kt`
  reads *other* people's content — `get_public_profile`, plus plain
  `.select { eq("owner_id", userId) } }` reads of `cars`/`events`/`trophy_unlocks` that
  rely entirely on RLS to resolve to "shared and visible to me". `dto/` holds the
  `@Serializable` wire shapes: push rows (`CloudRows.kt`), restore-read rows
  (`RestoreRows.kt`), E2E payload shapes (`BackupPayloads.kt`), friend-RPC shapes
  (`FriendRows.kt`), feed/profile-RPC shapes (`FeedRows.kt`). Schema, RLS policies and
  RPCs live in `supabase/schema.sql`; dashboard steps in `supabase/SETUP.md`.
  `PlatformContext.kt` (opaque per-platform handle — **[androidMain]** wraps `Context`,
  **[iosMain]** is empty, Keychain needs none) and `BundledAsset.kt`
  (`readBundledAsset` — **[androidMain]** reads this module's own `androidMain/assets/`,
  merged into whichever app depends on it; **[iosMain]** reads `NSBundle.mainBundle`,
  UNVERIFIED, needs a matching resource added to the Xcode target) underlie the above.
- `data/db/` — Room 3 (`androidx.room3`, the KMP-capable line): `AppDatabase.kt`
  (currently version 11, see migrations below), `Entities.kt` (`DeviceEntity`→`devices`,
  `LocationPointEntity`→`location_points`, `SyncStateEntity`→`sync_state`,
  `AppStateEntity`→`app_state`, `UserProfileEntity`→`user_profile`, `CarEntity`→`cars`
  (Garage, incl. `isFavorite`), `CarModificationEntity`→`car_modifications`,
  `EventEntity`→`events` (incl. `pointsSource`: `"DEVICE"` or `"GPX"`),
  `EventPointEntity`→`event_points`, `TrophyUnlockEntity`→`trophy_unlocks`,
  `TrophyProgressEntity`→`trophy_progress` (singleton row, cached `TrophyStats`)),
  `Daos.kt`. `DatabaseBuilder.android.kt`/`.ios.kt` are the `expect`/`actual` builder
  seam (`Room.databaseBuilder(context, path)` vs. a `NSDocumentDirectory` path, no
  `Context` needed) — everything else (entities, DAOs, migrations) is unchanged
  behavior, just common now.
- `data/model/` — `Stats.kt`, `LocationPoint.kt`, `HistoryRange.kt` (pure — label
  `@StringRes` lives in `:app`'s `HistoryRangeUi.kt`), `DiscoveredDevice.kt`,
  `SpeedZone.kt`, `FrenchDepartment.kt` (the 101 INSEE départements, for Profile),
  `Trophy.kt` (the full trophy catalogue + `TrophyTier`/`TrophyCategory`/`TrophyUnit`,
  pure — icon/title/description/label decoration lives in `:app`'s `TrophyUi.kt`, same
  split as `CloudPrefsRepository.kt` above), `TrophyStats.kt`. `EventType.kt` never
  needed this split: nothing in `data/repo`/`data/db` touches it (events store their
  type as a plain `String` column), so it's still `:app`-only with its Compose icon
  baked in, unlike `Trophy`.
- `data/repo/` — `StatsCalculator.kt` (top-level `computeStats`, reused by
  Stats/Garage/Events), `ProfileRepository.kt`, `CarRepository.kt` (Garage cars +
  modifications + favorite; photo I/O via the `PlatformFileStore` seam —
  **[androidMain]** `context.filesDir`, **[iosMain]** the Documents directory,
  UNVERIFIED), `EventRepository.kt` (device-crop and GPX-import event creation, see Key
  components below), `GpxImporter.kt`/`GpxExporter.kt` (pure GPX `<trkpt>` parse/build —
  regex-based rather than a real XML parser, since GPX's shape here is simple and it
  avoided a multiplatform XML dependency; file I/O is each platform's own concern, see
  `:app`'s `GpxImporterAndroid.kt`/`GpxExporterAndroid.kt` extension functions),
  `TrophyEvaluator.kt` (pure streaming `TrophyAccumulator` + top-level streak/grid
  helpers), `TrophyRepository.kt` (full rescan paging every device via
  `LocationPointDao.pageForDevice`, diffs against `trophy_unlocks`),
  `DepartmentLocator.kt` (nearest-centroid département lookup off
  `readBundledAsset`-loaded `departments_centroids.json` — approximate, see its KDoc),
  `DeviceRepository.kt`, `PlatformFileStore.kt` (the seam itself).
  **`TrackRepository.kt`/`RemoteTrackSync.kt` deliberately did NOT move here** — they
  stay in `:app` (see below): `RemoteTrackSync` is dev/prod **flavor**-scoped (real
  Firestore vs. no-op), and `:shared`'s single `androidTarget` has no flavor dimension to
  express that split.
- `util/` — `Haversine.kt`, `Logger.kt` (`expect object` — trivial `android.util.Log` /
  `NSLog` wrapper for the cloud sync layer's warnings).
- `commonTest/` — `data/repo/TrophyEvaluatorTest.kt`, `data/cloud/crypto/CryptoBoxTest.kt`,
  `data/cloud/PolylineCodecTest.kt` — see Tests below.

### `:app` (`app/src/{main,dev,prod}/java/com/carlauncher/companion/`)
Compose UI (always), plus whatever is inherently platform-specific or flavor-specific
and doesn't fit a `:shared` seam. Paths below are under `src/main` unless tagged
**[dev]** (`app/src/dev/java/...`) or **[prod]**.
- `car/` — `LocalTrackingService.kt` (this phone's own GPS recording) and
  `TrophyNotifier.kt` (plain phone notification, deep-links to Trophies) are shared;
  the Android Auto / radar-alert domain is **[dev]**: `RadarAlertService.kt` (foreground
  service, `foregroundServiceType="location"`), `RadarAlertEngine.kt`,
  `RadarAlertNotifier.kt`, `DangerBar.kt`, `CarBluetoothReceiver.kt`,
  `BluetoothCarDetector.kt`
- `data/` — `AppContainer.kt` (manual DI container, built in `CompanionApp.onCreate()`;
  now the thing that constructs `:shared`'s `PlatformContext`/`PlatformFileStore`/repos
  and hands `BuildConfig`-sourced config strings to `SupabaseClientProvider`/
  `AuthRepository`), `MapFocusRequest.kt`, `BetaContainer.kt` (**seam**, one per flavor)
  - `data/cloud/` — only what's left after the Phase 4 move: `CloudSyncWorker.kt`
    (periodic WorkManager job wrapping `:shared`'s `CloudSyncManager`, plus an immediate
    one-shot when a toggle flips on — WorkManager itself has no iOS equivalent, hence
    staying here) and `CloudPrefsLabels.kt` (the `@StringRes` label/description
    extension properties for `Visibility`/`FeedScope`/`SyncCategory`/`ProfileSection`).
  - `data/bluetooth/BluetoothTriggerStore.kt` **[dev]** — persists chosen car BT device(s)
  - `data/firebase/` **[dev]** — `PushDocument.kt`, `TrackRemoteSource.kt` (Firestore
    read/listen)
  - `data/settings/BackgroundFeatureSettings.kt` **[dev]** — the two Settings-screen kill
    switches (SharedPreferences + a `StateFlow` mirror so `CarBluetoothReceiver.onReceive`/
    `BetaAppInitializer`/`CompanionFcmService` can all read synchronously)
  - `data/model/` — `EventType.kt` (car meet/racetrack/exploration/other, Compose icon +
    accent color baked in — see the `:shared` section above for why this one never
    split), `TrophyUi.kt`/`HistoryRangeUi.kt` (icon/`@StringRes` extension properties for
    `:shared`'s `Trophy`/`HistoryRange`), `ShareTemplate.kt`;
    **[dev]** `RadarSection.kt`, `RadarIcons.kt`, `RadarType.kt`, `RadarPoint.kt`
  - `data/repo/` — `TrackRepository.kt` (pure Room + delegation to the
    `RemoteTrackSync` **seam** — see the `:shared` section above for why this stayed),
    `RemoteTrackSync.kt` (**seam**, one per flavor), `RadarRepository.kt` **[dev]**
    (parses bundled per-country GPX lazily), `SectionRepository.kt` **[dev]** (reads
    generated `radar_sections.json`), `GpxImporterAndroid.kt`/`GpxExporterAndroid.kt`
    (the `Uri`/`ContentResolver` file-I/O half of `:shared`'s `GpxImporter`/`GpxExporter`,
    as extension functions so call sites read identically to before the split).
- `push/CompanionFcmService.kt` **[dev]** — FCM data-message handling ("car started" push)
- `ui/` — Compose: `nav/CompanionNavHost.kt` + `Destinations.kt` (bottom nav: Map /
  History / Stats / Profile always, plus Feed inserted as the 2nd tab once signed in —
  `bottomTabs(signedIn: Boolean)`, not a static list; Devices and Bluetooth trigger are
  top-bar-only, not bottom tabs, and dev-only) + `nav/BetaNavEntries.kt` (**seam**),
  `map/MapScreen.kt` + `CartoDarkMatterTileSource.kt` (osmdroid, dark
  tiles) + `MapViewExt.kt` (`awaitFirstLayout()`, shared by any screen that zooms an
  osmdroid `MapView` to a bounding box on load) + `map/RadarControls.kt` (**seam**),
  `history/HistoryScreen.kt`,
  `stats/StatsScreen.kt`, `devices/DevicesScreen.kt` **[dev]**,
  `bluetooth/BluetoothTriggerScreen.kt` **[dev]**, `settings/SettingsScreen.kt` **[dev]**,
  `profile/ProfileScreen.kt` (personal info + Garage/Events hub, favorite-car photo
  hero), `garage/` (`GarageScreen.kt`, `CarDetailScreen.kt`), `events/`
  (`EventsScreen.kt`, `EventDetailScreen.kt` — combined create/edit/view), `trophies/TrophiesScreen.kt`
  (medal grid + detail sheet + the `CarTrophyStrip` reused by Garage car detail), `common/`
  (`RangeSelector.kt`, `NeonSurfaces.kt` (`NeonCard`/`NeonPill`/`NeonProgressBar`/`NeonSegmentedSelector`),
  `StatsDisplay.kt`
  (`StatTile`/`SpeedZoneCard`, shared by Stats/Garage/Events), `DashboardComponents.kt`
  (`DashboardRow`/`IconBadge`/`SectionLabel`/`AccentDivider` — the console-menu look
  used across Profile/Garage/Events), `CarPhoto.kt`, `TraceMap.kt` — the speed-colored-trail
  map, shared by the local `EventDetailScreen` and the read-only `SharedEventDetailScreen`
  so a friend's shared trace renders through the identical code path), `theme/`.
  Cloud-feature UI — **both flavors, `src/main`**, mirroring `data/cloud/` (unlike Firebase,
  none of this is behind a seam): `auth/` (sign-in/up, password reset by emailed deep link,
  recovery-code display, `CloudEntryScreen.kt` — routes between the sign-in form and the
  signed-in panel depending on session state), `legal/` (renders the bundled terms/privacy
  markdown), `cloud/` (`CloudSettingsScreen.kt` — the six upload toggles +
  visibility/feed-scope + sync/restore; `ShareToggle.kt` — the per-item share switch shown
  on car/event detail screens, hidden when signed out), `friends/FriendsScreen.kt`
  (exact-username search, requests, friend list — no browsing/listing anywhere), `feed/`
  (`FeedScreen.kt` — paged activity cards, the 2nd bottom tab once signed in;
  `PublicProfileScreen.kt`; `SharedCarDetailScreen.kt`/`SharedEventDetailScreen.kt` — read-only)
- `util/TimeFormat.kt` — locale-aware/`@Composable` date formatting (`formatRelative`,
  `formatDuration`) plus a few pure `java.time` helpers (`dayKey`, etc.); stayed
  Android-only rather than moving to `:shared` — kotlinx-datetime's format DSL doesn't
  auto-localize month/day names the way `java.time.DateTimeFormatter` +
  `Locale.getDefault()` does, and the `@Composable` half couldn't move regardless. (Its
  one pure, non-localized function, `formatGpxTime`, did move — see `:shared`'s
  `GpxExporter.kt`.)
- Root: `CompanionApp.kt` (Application: container construction, osmdroid config, trophy
  rescan), `MainActivity.kt` (launcher activity, hosts `CompanionNavHost`),
  `BetaAppInitializer.kt` (**seam** — the dev half holds the Firebase auth/push-channel/
  topic-subscription and `observeCarConnection()` startup work, plus the
  battery-optimization prompt)

## Key components
- **DB**: Room 3 (`androidx.room3` 3.0.1 + KSP — the KMP-capable line, needed once
  `data/db/` moved to `:shared` for the iOS port; classic `androidx.room` is Android/JVM
  only), local cache the UI reads from; source of truth for the UI, Firestore is the
  remote store the launcher writes to. Schema is versioned with one additive
  `Migration` object per bump (see `:shared`'s `data/db/AppDatabase.kt`) — no
  destructive migrations have been needed yet, so follow that pattern (raw
  `ALTER TABLE`/`CREATE TABLE`, matching the Kotlin entity, `Migration.migrate()` now
  `suspend fun` taking a `SQLiteConnection` rather than `SupportSQLiteDatabase`) rather
  than a fallback.
- **Events GPS source**: `EventRepository` supports two independent ways of populating
  an event's `event_points`, both landing in the same table/shape so the UI (stats,
  `EventTraceMap`, GPX export) renders them identically: cropped from a linked car's
  `location_points` in a day/time window (`createEvent`/`updateEvent`), or parsed from
  a user-picked GPX file (`createEventFromGpx`/`updateEventGpxPoints`, via
  `GpxImporter`). `EventEntity.pointsSource` records which, so a metadata-only edit
  (`updateEventMetadata`) never blindly re-crops over an imported track.
- **Bluetooth trigger**: `car/CarBluetoothReceiver.kt` is a manifest-registered
  `BroadcastReceiver` (works even app-killed) for ACL connect/disconnect + adapter
  state. Once a device is configured via `BluetoothTriggerStore`, it becomes sole
  authority over starting/stopping `RadarAlertService`, superseding Android Auto's
  `CarConnection` signal.
- **Notifications**: plain Android notifications (not Car App Library screens), built
  in `car/RadarAlertNotifier.kt`; "car started" channel/logic in `BetaAppInitializer.kt`
  (dev) + `push/CompanionFcmService.kt` (topic `launcher_events`). `TrophyNotifier` is
  shared and owns its own separate `"trophy_unlocks"` channel.
- **Map/radar**: the dev `ui/map/RadarControls.kt` seam draws markers from
  `RadarRepository` (25 countries, offline GPX under `app/src/dev/assets/radars/`) and
  average-speed ("Troncon") section polylines from `SectionRepository` reading the
  build-time generated `radar_sections.json`, onto the `MapView` that shared
  `MapScreen.kt` owns. Alert math in `car/RadarAlertEngine.kt` /
  `DangerBar.kt`; GPS math in `:shared`'s `util/Haversine.kt`.
- **Cloud security model** (read before touching `data/cloud/` or `supabase/schema.sql`):
  the anon key ships in the APK and is public, so *all* access control is Postgres RLS —
  every table `ENABLE` **and** `FORCE`s row-level security and denies by default. The
  `service_role` key must never enter this repo. `SECURITY DEFINER` functions must pin
  `SET search_path`. `profiles` is never directly selectable by strangers (username lookup
  goes through a rate-limited exact-match RPC) — otherwise the whole user list is
  scrapeable. Two axes that are easy to conflate: **upload** (backup, six local toggles)
  vs **visibility** (sharing, global level + per-item flag); uploading is never sharing.
  GPS history and global stats are E2E-encrypted and have *no* sharing path — do not add
  one. Only an explicitly shared event's trace is ever visible to another user.
  Verify policy changes by querying PostgREST as a second account, not through the UI
  (the UI hides what it didn't fetch, so it can't distinguish a working policy from a
  broken one) — recipe in `supabase/SETUP.md`.
- **DI**: no Hilt/Dagger/Koin — manual, via `data/AppContainer.kt`.
- **No server / Cloud Functions** — everything on Firebase's free Spark plan; the
  launcher posts to FCM HTTP v1 directly with a send-only service account key (lives
  in the launcher repo, not here).

## Build config
- Gradle wrapper 8.11.1, AGP 8.10.0, Kotlin/KSP 2.3.10, Google Services 4.4.2,
  `androidx.room3` 3.0.1. Bumped together (from 8.9/8.7.3/2.1.20/2.1.20-1.0.32, room3
  added new) for one forcing chain, not a preference: every supabase-kt 3.x release
  needs Kotlin 2.3+ → needs a matching KSP release (2.3.10 is the newest that exists —
  Kotlin 2.4 has none yet) → needs an AGP new enough for KSP's Gradle plugin → needs a
  newer Gradle. `androidx.room3` (not classic `androidx.room`, which is Android/JVM
  only) was added for `:shared`'s Room DB to reach iOS — 3.0.1 reached stable in July
  2026, not an alpha pin. See the version comment in root `build.gradle.kts`.
- No version catalog (`libs.versions.toml` absent) — deps declared directly in each
  module's own `build.gradle.kts` (`app/build.gradle.kts`, `shared/build.gradle.kts`).
- `:app`: compileSdk 35, minSdk 26, targetSdk 35, JVM target 17; `namespace`
  `com.carlauncher.companion` for both flavors, applicationId
  `com.carlauncher.companion` (dev) / `com.shenzou.carcompanion` (prod). One flavor
  dimension, `channel`, with `dev` and `prod` (see Build flavors above).
  `app_name` comes from a per-flavor `resValue`, **not** from `strings.xml` — adding it
  back to `strings.xml` is a duplicate-resource build error. Key deps: Compose BOM
  2025.02.00, Navigation-Compose 2.8.1, Coroutines 1.8.1, osmdroid 6.1.20.
  `devImplementation` only: Firebase BOM 33.7.0 (auth, firestore, messaging),
  `kotlinx-coroutines-play-services`, androidx.car.app 1.7.0. Supabase-kt
  (auth-kt/postgrest-kt), the Ktor engine, `multiplatform-settings`, and
  `kotlinx-serialization-json` all come in transitively via `:shared`'s own `api`
  surface — `:app` no longer declares them directly (MainActivity's
  `client.handleDeeplinks(...)` is its one remaining direct supabase-kt call site).
- `:shared`: `androidTarget()` + `iosArm64()`/`iosSimulatorArm64()` (no `iosX64` —
  Apple Silicon only), namespace `com.carlauncher.companion.shared`, same
  compileSdk/minSdk/JVM-target as `:app`. Key deps: `androidx.room3` 3.0.1 +
  `androidx.sqlite:sqlite-bundled` 2.7.0 (bundles SQLite itself, no OS-provided driver
  needed on either platform), `dev.whyoleg.cryptography` 0.6.0 (`core` +
  `provider-optimal` — auto-selects JDK on Android, CryptoKit/OpenSSL3 on iOS),
  `com.russhwolf:multiplatform-settings` 1.3.0, kotlinx-datetime 0.8.0, Supabase BOM
  3.6.0 (applied via `add("commonMainApi", platform(...))` in the plain
  `dependencies {}` block, not inside `sourceSets { commonMain.dependencies {} }` —
  `platform(...)` resolves to a deprecated, Kotlin-2.3-hard-error overload there,
  KT-58759), `ktor-client-okhttp` 3.0.3 **[androidMain]** / `ktor-client-darwin` 3.0.3
  **[iosMain]**. Each iOS target builds a static `Shared.framework`
  (`embedAndSignAppleFrameworkForXcode`) — see README.md's "iOS port" section.

## Entry points
- Application: `CompanionApp` — `app/src/main/java/com/carlauncher/companion/CompanionApp.kt`
- Activity: `MainActivity` — `app/src/main/java/com/carlauncher/companion/MainActivity.kt`
- Manifests: `app/src/main/AndroidManifest.xml` (shared) and
  `app/src/dev/AndroidManifest.xml` (beta components + their permissions, merged into
  dev variants only)

## Tests
Three suites, run separately:
- `./gradlew :shared:testDebugUnitTest` — the `:shared` module's `commonTest` suite,
  run against its `androidTarget` (Kotlin/Native `iosSimulatorArm64` tests need a Mac —
  see README.md's "iOS port" section for status): `data/repo/TrophyEvaluatorTest.kt`
  (trip segmentation, streaks, map-grid helpers, trophy progress; uses kotlin.test, not
  JUnit), `data/cloud/crypto/CryptoBoxTest.kt` (AEAD round-trips, AAD binding,
  password-change re-wrapping, recovery codes — against `dev.whyoleg.cryptography`, not
  `javax.crypto`), and `data/cloud/PolylineCodecTest.kt` (round-trips including Google's
  own reference example, negative coordinates, empty input). Keep everything under
  `commonTest`/the code it covers free of `android.*`/`java.*` imports so it stays
  testable on both targets — that's why base64 lives in `crypto/Base64Codec.kt`
  (`kotlin.io.encoding.Base64`), shared by `KeyVault` and `CloudSyncManager` rather than
  duplicated in either.
- `./gradlew :app:testDevDebugUnitTest` (and `testProdDebugUnitTest`) — `:app`'s own
  JUnit 4 suite, pure-JVM: currently just `data/model/TrophyTest.kt` (catalogue
  uniqueness + `progress()`/`isUnlocked()` against the `Trophy` enum `:shared` owns —
  what stayed here is Compose-adjacent enough, or thin enough, not to warrant its own
  `commonTest` coverage).
- `./gradlew -p buildSrc test` — `buildSrc/src/test/kotlin/radar/SectionPairingTest.kt`,
  covering `buildSrc/src/main/kotlin/radar/SectionPairing.kt`.
No `app/src/androidTest`.

## Conventions
- Keep `README.md` in sync when a change affects a documented feature, architecture
  piece, or build/setup step. Pure bug fixes or internal refactors don't need it.
- Don't change the app's theming (colour values, `ui/theme/`, or the mechanism by
  which a colour reaches the UI, e.g. runtime `setTint`) unless explicitly requested —
  keep the existing colour mechanism intact even when swapping assets. The current
  identity is the dark "neon arcade" scheme (Ink surfaces + lime/cyan/magenta accents,
  monospace instrument numerals, no bundled font files).
- New coloured UI should import a semantic accent (`AccentGarage`, `AccentProfile`,
  `AccentEvents`, `AccentTrophy`) or a `MaterialTheme.colorScheme` slot — not a raw
  `Neon*`/`Ink*` palette token.
