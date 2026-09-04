# Car Companion

Android companion app that shows where your car(s) are and when they were
last started. It's the counterpart to a separate custom car launcher project
(`porsche_launcher_android`) that tracks GPS and pushes lifecycle events to
the same Firebase project — this app is the one you keep on your own phone,
not installed in the car.

## What it does

Entries marked **(dev only)** are the beta features, present in the `dev`
flavor and absent from `prod` — see [Build
flavors](#build-flavors-dev-and-prod).

- **Live map** — the car's most recent position, rendered with osmdroid
  (OpenStreetMap tiles, dark "Carto Dark Matter" style).
- **History** — past tracks over a selectable range (today / last 7 days /
  last 30 days / all time), with GPX export for use in other tools.
- **Stats** — distance, max/average/median speed, and moving time computed
  over the selected range.
- **Share** — a Strava/Spotify-style share card, reached from the Map or
  Stats screen, or from an Event's detail screen (`ui/share/ShareScreen.kt`).
  It always covers the exact same range/points already selected on that
  screen (an event's own track, whether device-cropped or GPX-imported, when
  shared from Events). Four visual templates — Minimal, Speed zones,
  Detailed, Stats only — and a "Share" button that captures the card to a PNG
  (`ui/share/ShareImageExporter.kt`, via `PixelCopy` + a `FileProvider`) and
  hands it to the system share sheet. Every card also shows the start/end
  time of the shared track — taken from its first and last point, not the
  selected range or event window. Sharing from an Event shows richer car info
  (name, brand/model/year) on every template, and if the linked Garage car
  has a photo, the map-less "Stats only" template uses it as a dimmed
  background instead of a flat fill.
- **Multiple cars** *(dev only)* — devices are either discovered automatically (any
  `deviceId` that has pushed data to Firestore) or added manually by ID, each
  with an editable local nickname plus optional brand/model/details (edit via
  the pencil icon on the Devices screen). Brand + model show up on the Share
  card when set.
- **"This phone"** — a built-in device that's always present and pre-selected,
  even with no cars registered, so the app is useful from first launch. While
  it's selected, the Map screen shows a record button that starts a foreground
  service (`car/LocalTrackingService.kt`) logging the phone's own GPS instead
  of relying on a car — History, Stats, Share and the Garage/Events linking
  all treat it exactly like any other device. A "Reassign" button on the
  History screen re-labels every point in the selected range to a different
  device — e.g. attributing a phone recording to a real car after the fact —
  with a plain column update rather than a delete/reinsert, so it can be
  repeated or reversed at any time without losing data.
- **"Car started" push notification** *(dev only)* — a real system notification the
  moment the car's launcher cold-starts, showing the car's nickname if it's
  one you've already added. See [FIREBASE_START_EVENT.md](FIREBASE_START_EVENT.md)
  for the full design (why it's a data-only FCM message, the `deviceId`
  lookup, high-priority delivery, and the battery-optimization exemption
  needed to receive it reliably while backgrounded/killed).
- **Speed camera radars** *(dev only)* — fixed, mobile, red-light, tunnel, roadworks and
  average-speed cameras across 25 countries, bundled offline (no network
  needed at runtime) and shown as filterable markers on the map. The
  third-party GPX data isn't committed to this (public) repo — see [Radar
  data](#radar-data-optional-dev-flavor-only) below for how to supply it
  locally; a dev-flavor build without it just has no markers. Average-speed
  ("Troncon") sections are also drawn as a line following the road between
  the entry and exit camera — see
  [Average-speed radar sections](#average-speed-radar-sections) below. A
  proximity alert (`car/RadarAlertService.kt`) also runs while a trip is
  active, including on Android Auto.
- **Bluetooth radar trigger** *(dev only)* — optionally pick one or more paired
  Bluetooth devices (e.g. the car's head unit) as the signal to start/stop
  radar tracking, from a screen reached via the top bar. Once configured,
  `CarBluetoothReceiver` (`car/CarBluetoothReceiver.kt`) becomes the sole
  authority over the radar-alert foreground service — Android Auto's
  `CarConnection` projection state is ignored from then on. Tracking starts
  when any configured device connects and stops once none remain connected;
  turning Bluetooth off counts as "all disconnected" too. Addresses and
  connection state are persisted by `BluetoothTriggerStore`
  (`data/bluetooth/BluetoothTriggerStore.kt`).
- **Gas stations & fuel prices** (`ui/map/GasStationControls.kt`,
  `ui/map/GasStationPriceTable.kt`, `data/repo/GasStationRepository.kt`,
  `data/repo/SwissGasStationRepository.kt`, `data/db/GasStationDatabase.kt`, both flavors)
  — two complementary live data sources that activate automatically depending on the
  map viewport:
  - **French stations** (~10,000) — downloaded from the official DGCCRF / data.gouv.fr
    feed (`prix-des-carburants-en-france-flux-instantane-v2`), stored in a dedicated SQLite
    database (`gas_stations.db`) with spatial indices on `(lat, lon)` for fast bounding-box
    queries. Under zoom 11.5, stations are automatically clusterized using dynamic grid cells
    via SQLite `clustersForViewport` down to zoom 5.5. At zoom ≥ 11.5, individual stations are
    displayed. Daily background auto-sync on first app launch of the day + Toast notification.
    Settings section with manual update button and download/indexing progress.
  - **Swiss stations** — live per-viewport HTTP POST to the TCS `benzinGetStationByBbox` API
    (`europe-west6-tcs-digitalbackend.cloudfunctions.net`). No local storage — results are
    purely in-memory. The API returns clusters at low zoom (circular count badge marker) and
    individual stations at high zoom. An automatic bounding-box guard against Swiss territory
    (lat 45.8–47.9, lon 5.9–10.5) skips the HTTP call entirely when the map is in France.
    Prices displayed in CHF with fiability and cheapest-station flag.
  - **Map markers & fuel filter** — markers render dynamically on zoom (individual stations
    at zoom ≥ 11.5; clusters from zoom ≥ 5.5). A dropdown pill selects the fuel (French fuels:
    Gazole, SP95, SP98, E10, E85, GPLc; TCS-only: Diesel Premium, AdBlue, CNG, HVO100, H₂) or hides
    all markers. French and Swiss fetches run concurrently and results are merged. Swiss and French
    individual stations share the exact same neon amber gas station icon; clusters show as an
    amber circular badge with the station count.
  - **Info window & navigation** — tapping a station opens a neon dark bubble with prices
    (CHF for Swiss, € for French), fiability / 24h status, highway/road badge, and a "Y aller"
    navigation button (individual stations only — clusters omit it since they have no single
    address). Launches external GPS app via `geo:` URI.
  - **Top-5 cheapest prices table** — on-map HUD ranking the 5 cheapest visible stations
    for the active fuel. Each price is underlined with a color bar indicating freshness/confidence:
    green (< 2 days / CONFIDENT), lime (2-3 days / FEW_RECENT_PRICES), yellow (4-7 days),
    orange (8-14 days / OLD_LAST_UPDATE), red (> 14 days / OUTDATED). Tapping an individual station
    flies the camera to it at zoom 16.0. Tapping a cluster line (French or Swiss) centers on it at
    the current zoom to display the cluster and its info bubble; tapping the centered cluster a
    second time zooms in to 15.0 to expand and display its contained stations (the same expansion
    also occurs when tapping a centered cluster map marker).



  - **Single open bubble & HUD tile mutual exclusion** — one info bubble open at a time;
    Speed zones legend and Gas stations table mutually exclude each other.
  - **Settings management & empty state** — no-data dialog if the French button is pressed
    before any data has ever been downloaded (Swiss always works, no download needed).

- **Profile** — a bottom tab for the user's own data, separate from the
  GPS-tracked Devices list. It leads with a level & XP progress badge (including
  the active daily login streak) and the favorite car's photo hero, followed by
  the Cloud Account entry tile, quick-access stat buttons for Garage, Events, and
  Trophies, navigation tiles for Friends and Leaderboard, and personal info.
  - **Personal information** — age, city, and the French départements the
    user wants to drive in (picked from the official list).
  - **Garage** (`ui/garage/`) — cars the user owns, each optionally linked to
    a tracked device to inherit its GPS stats (linkable on creation or editable
    later from detail), with a photo, manually-entered odometer, and a modifications
    log (title/category/cost/notes). A car without a tracker can still be registered
    with no GPS stats shown. One car can be marked **favorite** (star toggle on its
    detail screen); the favorite gets a full-width photo hero at the top of the Profile
    screen and a star badge in the Garage list, so it's visible without drilling in.
    The photo is downsized/compressed on-device (long edge capped at 960px, JPEG under ~300KB)
    before it's written locally, so the same small file also travels to Supabase Storage
    — see [Cloud accounts](#cloud-accounts-supabase) — when the car is shared, and shows
    up on `SharedCarDetailScreen` for friends/the public.
  - **Events** (`ui/events/`) — car meets, racetrack days and explorations,
    each with a track sourced one of two ways: **from a car**, with a day +
    time window whose GPS points are cropped from the linked device and
    copied into permanent, event-owned storage
    (`EventRepository.createEvent`) so later History-screen deletions of the
    originals don't affect the event; or **imported from a GPX file**
    (`data/repo/GpxImporter.kt`), which derives the event's window and
    per-point speed straight from the file instead. Both sources land in the
    same storage and render identically — stats, and a compact trace map
    (`EventTraceMap` in `ui/events/EventDetailScreen.kt`, same dark-tile/
    speed-colored-trail style as the main Map screen) shown once there are
    points. Events with points can also be exported back out as GPX
    (reusing `ui/export/GpxExporter.kt`).
  - **Trophies** (`ui/trophies/`) — 29 unlockable achievements scored against
    the whole driving history across every tracked device, in four
    categories: distance & speed (total km, longest trip, top speed), habits
    & streaks (trip counts, consecutive driving days, night/early starts, all
    four seasons), exploration (French départements entered, distinct ~10 km
    map squares, farthest point from where you usually drive) and collection
    (garage cars, modifications, events, GPX imports, a completed profile).
    Locked trophies show a progress bar and how far off they are, so there is
    always something visibly in reach. Earning one posts a notification that
    deep-links back to the screen. The car detail screen also shows a strip of
    the trophies that car's own history would have earned, derived on the fly.

    Trophies are re-scored on app start, when a trip ends (either tracking
    service stopping) and whenever the screen is opened. Only the unlock
    timestamps are stored (`trophy_unlocks`) plus a cached snapshot of the
    lifetime aggregate (`trophy_progress`) so the screen paints instantly;
    the trophy definitions themselves live in Kotlin
    (`data/model/Trophy.kt`), so adding one needs no schema migration.
    A rescan unlocks new trophies and relocks/revokes trophies if history
    data was deleted and criteria are no longer satisfied, cleaning up
    cloud unlock rows and feed activity cards accordingly.
  - **XP, levels and leaderboard** (`data/repo/XpCalculator.kt`,
    `data/repo/XpRepository.kt`) — a second, complementary gamification layer
    on top of Trophies. XP comes from distance driven, trophy tier bonuses,
    events logged, garage setup (cars/modifications), and a daily login
    streak that grows the longer it stays unbroken and resets on a missed
    day. Distance/trophy/event/garage XP is a pure function of the same
    cached `trophy_progress`/`trophy_unlocks` state Trophies already
    maintains — only the login streak needs its own persisted state
    (`xp_state`), since it's inherently sequential. Level and XP total show
    on the Profile screen; the ranked leaderboard (friends or everyone, per
    its own dedicated `leaderboard_visibility` setting — independent of the
    Cloud account visibility below, so a user can compete while keeping
    everything else private) is reached from Profile and served by the
    `get_leaderboard` RPC (`supabase/schema.sql`). Known limitation: the
    login-streak XP accumulated so far lives only in local Room, not in
    `CloudRestoreManager`'s restore path — a reinstall resets the streak and
    its earned XP to zero (every other XP source is unaffected, since it's
    recomputed from data that does restore).
- **Cloud account** *(both flavors, optional)* — reached via "Cloud account"
  on the Profile screen. Create an account or sign in, then choose what backs
  up to the cloud (cars, events, personal information, GPS history,
  statistics, trophies — each off by default) and who can see anything
  individually marked shared. GPS history and statistics are end-to-end
  encrypted and never shareable at any setting. Full detail in
  [Cloud accounts](#cloud-accounts-supabase) below.

## Build flavors: `dev` and `prod`

The app builds as two side-by-side-installable APKs from one codebase, split
by a Gradle product flavor dimension (`channel`):

| | `dev` | `prod` |
|---|---|---|
| applicationId | `com.carlauncher.companion` | `com.shenzou.carcompanion` |
| App name | Car Companion Dev | Car Companion |
| Feature set | everything below | everything except the beta features |

Three subsystems are **beta** and ship in `dev` only: the **Bluetooth car
trigger**, **all Firebase functionality** (remote device discovery, Firestore
history sync, and the "car started" FCM push), and **radars** (the map's radar
markers / average-speed section lines and the proximity-alert service).
Everything else — local GPS tracking, History, Stats, Share, Devices' local
row, Profile, Garage, Events, Trophies, Gas stations & fuel prices — is in both. `prod` is, in effect, a
purely local, offline trip recorder.

Beta code is **not compiled into `prod` at all** — it isn't hidden behind a
runtime flag. It physically lives in `app/src/dev/` (`java/`, `assets/`,
`AndroidManifest.xml`), which AGP never includes when assembling a `prod`
variant. So `prod` contains no Firebase libraries, no `androidx.car.app`, no
12 MB of radar GPX, and declares none of the Bluetooth /
background-location / battery-optimization permissions or the FCM, radar-alert
and Bluetooth-receiver components.

Shared code in `src/main` still has to compile either way, so every place it
would touch a beta type crosses a **seam**: a class or object with the same
fully-qualified name and identical public API, declared once in
`app/src/dev/java/...` (the real thing) and once in `app/src/prod/java/...`
(a no-op stub). There are five:

| Seam | dev | prod |
|---|---|---|
| `data/BetaContainer.kt` | holds `RadarRepository` / `SectionRepository` / `BluetoothTriggerStore`, reached via `AppContainer.beta` | empty class |
| `data/repo/RemoteTrackSync.kt` | Firestore discovery / live tail / backfill / delete, wrapped by `TrackRepository` | same signatures returning empty |
| `BetaAppInitializer.kt` | the Firebase-auth / push-topic / radar-trigger half of `CompanionApp.onCreate()`, plus `MainActivity`'s battery-optimization prompt | empty |
| `ui/nav/BetaNavEntries.kt` | the Bluetooth + Devices top-bar icons, their nav routes, and Map's "Add a car" button | empty |
| `ui/nav/BetaNavEntries.kt`'s `showMainTopBar` | `true` | `false` — with no beta icons and always exactly one device, the top bar on Map/History/Stats/Profile/Feed has nothing to show, so it's hidden entirely there (detail screens still get their back-button bar in both flavors) |
| `ui/map/RadarControls.kt` | radar state, viewport loading, marker/section overlay drawing, the "Radars" filter pill, and the background-location request | inert state object, draws nothing |

Adding a beta feature therefore means putting it in `src/dev` and, if shared
code must call it, extending one of these seams in both flavors — not adding
a conditional.

## Architecture

- **Jetpack Compose** UI, single `MainActivity`, bottom navigation between
  Map / History / Stats / Profile (`ui/nav/CompanionNavHost.kt`); Devices and
  the Bluetooth trigger screen are reached from the top bar instead of the
  bottom tabs (dev flavor only), as are Garage / Events / Trophies from within
  Profile.
- **Theme** (`ui/theme/`) — dark-only "neon arcade" identity: near-black Ink
  surfaces with lime / cyan / magenta accents, chunky pill shapes, and
  monospace instrument numerals for anything that reads as a gauge. No font
  files are bundled — the display face is `FontFamily.Monospace` at heavy
  weights. `ui/theme/Neon.kt` holds the three primitives everything is built
  from (`neonBorder`, `neonGlow`, `neonSweep`), and `ui/common/NeonSurfaces.kt`
  the shared `NeonCard` / `NeonPill` / `NeonProgressBar` /
  `NeonSegmentedSelector`. Screens import semantic accents (`AccentGarage`,
  `AccentProfile`, `AccentEvents`, `AccentTrophy`) rather than raw palette
  tokens, so the palette can be retuned in one file.
- **Room** (`data/db/`) is the source of truth the UI reads from — a local
  cache of location points, known devices, sync watermarks, the last
  selected device, and the Profile tab's own data (personal info, garage
  cars + modifications, events + their cropped/imported GPS points, and the
  trophy unlock log + cached progress snapshot).
- **Firestore** (`data/firebase/`, dev flavor only) is the remote store the
  car's launcher writes to (`tracks/{deviceId}/pushes` for GPS points,
  `tracks/{deviceId}/events` for lifecycle events). This app listens for new
  pushes and backfills history into Room; anonymous Firebase Auth is
  required to read/write, same as the launcher. Shared code reaches all of
  this only through the `RemoteTrackSync` seam (wrapped by
  `TrackRepository`), which does nothing in the prod flavor.
- **FCM** (`push/CompanionFcmService.kt`, dev flavor only) delivers the
  "launcher started" push independently of the Firestore listener — it works
  even if the app isn't actively open, unlike the Firestore signal.
- **No server / Cloud Functions anywhere in this design** — everything runs
  on Firebase's free Spark plan; the launcher talks to the FCM HTTP v1 API
  directly using a messaging-only-scoped service account (that key lives in
  the launcher repo, not here — this app only *receives* pushes, so it
  doesn't need `firebase-messaging`'s send-side credentials at all).
- **Supabase** (`data/cloud/`, **both flavors**) backs the optional user
  account, opt-in cloud backup and the community sharing features. Unlike
  Firebase above this is not a beta feature and crosses no seam — it lives in
  `src/main` and ships in `prod` too. It is entirely inert when the build
  carries no credentials (see [Cloud accounts](#cloud-accounts-supabase)), so
  the app remains a working offline recorder either way. There is still no
  server process: access control is Postgres row-level security plus a handful
  of `SECURITY DEFINER` functions, all defined in
  [`supabase/schema.sql`](supabase/schema.sql).
- **Radars** (`data/repo/RadarRepository.kt`, `data/repo/SectionRepository.kt`,
  dev flavor only) are bundled data, not a live source: 25 GPX files under
  `app/src/dev/assets/radars/` (one per country) and a generated
  `assets/radar_sections.json` (see below), both read straight off disk with
  no network call. `RadarRepository` parses country files lazily, on demand,
  as the map viewport reaches them. Because the source GPX lives in the dev
  source set, neither the raw data nor the generated JSON is produced or
  bundled for prod. The GPX files themselves are **gitignored, not
  committed** — this is public Lufop/OsmAnd export data with its own
  license, so it's supplied locally rather than shipped in the repo. A fresh
  clone must have someone manually drop the 25 `COUNTRY.gpx` files into
  `app/src/dev/assets/radars/` before a dev-flavor build will show any radar
  markers or generate `radar_sections.json` — see [Radar
  data](#radar-data-optional-dev-flavor-only) below for the file format and
  naming.
- **Gas station database** (`data/db/GasStationDatabase.kt`,
  `data/repo/GasStationRepository.kt`, both flavors) — a dedicated SQLite database
  (`gas_stations.db`, kept separate from Room so that bulk replacing ~10k stations
  does not invalidate Room query caches or trigger unnecessary migration complexity).
  Ingestion streams gzipped GeoJSON from data.gouv.fr directly using `android.util.JsonReader`
  into a single SQLite transaction with an index on `(lat, lon)` and `pop` for
  sub-millisecond bounding box lookups.

## iOS port (in progress)

An iOS app is being built from this same repo via Kotlin Multiplatform, so
Android and iOS update together. **Scope mirrors the `prod` flavor**: no
Bluetooth car trigger, no Android Auto, no Firebase, no radars — iOS gets
Map / History / Stats / Garage / Events / Trophies / Profile, plus the
Supabase-backed accounts/backup/Feed features (both flavors already). The UI
stays two independent native layers — Jetpack Compose on Android (unchanged),
SwiftUI on iOS (new) — sharing only the business/data layer.

**Module layout**: a new `:shared` Gradle module (`androidTarget()` +
`iosArm64()` + `iosSimulatorArm64()`, Apple Silicon only — no `iosX64`) holds
everything that isn't UI: Room DB (`androidx.room3`, the KMP-capable Room
line — classic `androidx.room` is Android/JVM-only), all `data/repo/*`
business logic, the full `data/cloud/*` Supabase layer, and E2E crypto
(`dev.whyoleg.cryptography`, chosen specifically so the security-critical
code and its test suite are byte-identical on both platforms rather than
separate `javax.crypto`/CryptoKit implementations that could silently
diverge). `:app` depends on `:shared` and keeps 100% of its Compose UI.

Five `expect`/`actual` seams cover what's inherently platform-specific:
`PlatformContext` (Android `Context` wrapper / iOS no-op) underlies the other
four — `createSecureSettings` (Keystore-backed `EncryptedSharedPreferences` /
Keychain via `multiplatform-settings`'s `KeychainSettings`), `PlatformFileStore`
(car photos), `readBundledAsset` (the départements-centroid JSON), and the
zlib compress/decompress pair backing encrypted-backup payloads
(`java.util.zip` / Apple's Compression framework). `TrackRepository` stays in
`:app` rather than moving to `:shared`: its `RemoteTrackSync` dependency is
itself dev/prod **flavor**-scoped (real Firestore vs. no-op), and `:shared`'s
single Android target has no flavor dimension to express that split.

**What's still Compose/Android-only and stays that way**: `Trophy`/
`TrophyTier`/`TrophyCategory`/`TrophyUnit` and `HistoryRange` are pure enums
in `:shared`; their icon/colour/`@StringRes` decoration lives as extension
properties in `:app` only (`data/model/TrophyUi.kt`,
`data/model/HistoryRangeUi.kt`, mirroring `data/cloud/CloudPrefsLabels.kt`
for `Visibility`/`SyncCategory`/`ProfileSection`/`FeedScope`) — iOS supplies
its own localized strings and SF Symbols instead of porting these.
`EventType` never needed a split: nothing in the data layer touches it
(events store their type as a plain `String` column), so it's still entirely
`:app`-only, Compose icons included.

**Verification status**: everything above builds and is exercised by CI —
both `:app` flavors compile and their full unit test suites pass against
`:shared`; `:shared`'s own `androidTarget` compiles/tests clean; and, on
`.github/workflows/ios.yml`'s `macos-latest` runner, `:shared`'s
`iosSimulatorArm64` target compiles and its tests pass, `iosApp/` builds
against the real iOS SDK, and an unsigned `.ipa` comes out the other end.
No development machine here has ever had Xcode installed — this is CI-only
verification, which is also why it's a *build*, not a UI smoke test:
nothing has driven the app on an actual simulator/device screen yet (Phase 4
below).

Getting the `iosMain` actuals (Keychain settings, `NSBundle` asset reads,
`NSFileManager` file storage, Room's iOS database builder, zlib compression)
to a real, passing compile surfaced several genuine Kotlin/Native gaps that
no amount of reading could have caught: Apple's `Compression` framework
turns out not to be usable from Kotlin/Native at all (its bindings aren't
part of Kotlin/Native's default platform libraries — swapped for
`platform.zlib`, see `Gzip.ios.kt`), `Math.toRadians`/`Dispatchers.IO` don't
exist on Native, `@Volatile` needs the multiplatform
`kotlin.concurrent.Volatile` import rather than the JVM-default one, and
Room's KMP path needs an explicit `@ConstructedBy` declaration that the
Android/JVM-reflection path never required. All fixed; see the git history
on `shared/` and `iosApp/` for the specifics.

### The pipeline: iOS builds and ships without ever owning a Mac

Compiling Swift/Xcode targets is only possible on macOS — there's no way
around needing a macOS *build* environment somewhere. The pipeline splits
that requirement (ephemeral, nobody owns or maintains it) from *signing +
install* (genuinely Mac-free, self-hosted on Linux):

```
push to GitHub
      │
      ▼
GitHub Actions (macos-latest runner, ephemeral — .github/workflows/ios.yml)
  1. ./gradlew :shared:iosSimulatorArm64Test
  2. xcodegen generate                     (iosApp/project.yml → iosApp.xcodeproj)
  3. xcodebuild ... -sdk iphoneos CODE_SIGNING_ALLOWED=NO build
  4. zip into an UNSIGNED .ipa, publish to the rolling "ios-latest" GitHub Release
      │
      ▼
altserver-docker (self-hosted, Ubuntu — see ios-signing/)
  - free Apple ID signing via a from-scratch reimplementation of Apple's
    private auth protocol (anisette), not fastlane — fastlane's cert/sigh
    only work against the paid-Program Developer Portal API, not the
    free/personal-team one
  - re-signs before the 7-day free-tier cert expiry, pushes to the iPhone
```

`iosApp/project.yml` is an [XcodeGen](https://github.com/yonaskolb/XcodeGen)
spec, not a hand-committed `.pbxproj` — CI regenerates the actual Xcode
project from it on every run, so the YAML is the real source of truth. Its
`preBuildScripts` entry is the Run Script build phase from the original plan
here (`./gradlew :shared:embedAndSignAppleFrameworkForXcode`, reading
Xcode's `SDK_NAME`/`CONFIGURATION`/`ARCHS` env vars automatically — no
CocoaPods). `iosApp/iosApp/Resources/departments_centroids.json` is a
physical copy of the Android asset (`readBundledAsset`'s iOS `actual` looks
it up via `NSBundle.mainBundle.pathForResource`) — small, static, and
duplicating it was simpler than coupling the iOS target's resources to
`:shared`'s internal Android asset layout.

One Swift/Kotlin interop gotcha worth knowing before writing more iOS UI:
Kotlin/Native's classic ObjC-interop framework export wraps **top-level**
Kotlin functions in an unpredictable per-source-file `<Mangled>Kt` class —
never call them as bare globals from Swift. Kotlin `object`s don't have
this problem (exposed unambiguously as `MyObject.shared.foo()`), so prefer
a small iOS-facing `object` wrapper over a top-level function when adding a
new `:shared` entry point Swift needs to call — see
`shared/src/iosMain/.../IosSmokeTest.kt` for the pattern the placeholder
screen (`iosApp/iosApp/ContentView.swift`) currently uses.

**Signing (free Apple ID, no $99/yr Program)**: see `ios-signing/` —
`fetch-latest-ipa.sh` pulls the latest unsigned build from the `ios-latest`
release into a local [altserver-docker](https://github.com/FacuM/altserver-docker)
checkout for signing. `altserver-docker` bundles AltServer, an anisette-v3
server, netmuxd, and usbmuxd-based USB pairing into one Docker Compose
setup — free-tier Apple signing without fastlane (which only supports the
paid-Program Developer Portal API) and without ever needing Xcode. One-time
setup, done on the Ubuntu machine that will keep the phone's install fresh:

```bash
git clone https://github.com/FacuM/altserver-docker
cd altserver-docker && docker compose up -d --build
# connect the iPhone over USB, then:
docker exec -it altserver pair
# first install (also bootstraps AltStore itself onto the phone):
../car_companion/ios-signing/fetch-latest-ipa.sh .
docker exec -it altserver install iosApp-unsigned.ipa <apple-id> <app-specific-password>
```

Free-tier limits apply regardless of host OS: 3 sideloaded apps per device,
apps expire after 7 days (AltServer/AltStore auto-refresh over WiFi once the
device has been paired and is on the same network as the Docker host — USB
refresh always works as a fallback if WiFi refresh is flaky).

### Phase 4 — build out real screens

With the pipeline proven, screens still need to be built in the order the
shared repos were verified in, so each one proves the stack before the next
depends on it: sign-in (`AuthRepository`) → Map (MapKit + `MKTileOverlay`
against the same dark Carto tile URL `CartoDarkMatterTileSource.kt` uses,
`CLLocationManager` writing through `TrackRepository`'s Room half — iOS's
own thin repo, see above) → History → Stats → Garage → Events → Trophies
(+ `UNUserNotificationCenter` for celebrations) → Feed/Friends/Cloud
settings → `BackgroundTasks`-driven sync (iOS's equivalent of
`CloudSyncWorker`, calling the same shared `CloudSyncManager`) → share-card
export (`ImageRenderer`, mirroring `ShareImageExporter`'s PNG-card
approach). Once the app has real screens and has actually been driven on a
device, update this section and the "What it does" list above to describe
the shipped iOS feature set.

## Radar data (optional, dev flavor only)

`app/src/dev/assets/radars/` is gitignored and ships **empty** in this repo —
the bundled radar positions are third-party licensed data (Lufop/OsmAnd
exports), not something this public repo redistributes. Without anything in
that folder, a dev-flavor build still succeeds; the map just has no radar
markers or average-speed lines, and there's nothing for the proximity-alert
service to alert on. To build with your own radar data:

1. Get one export per country from [Lufop](https://lufop.net) (its "OsmAnd"
   download format) or any other source producing the same shape (see
   below). Lufop's own files are named `COUNTRY.osm`, but the *content* is
   already plain GPX — just rename each to `COUNTRY.gpx` when copying it in,
   no conversion needed.
2. Drop each file into `app/src/dev/assets/radars/`, named to match exactly
   what `RadarRepository.kt`'s `COUNTRY_FILES` list expects (e.g.
   `FRANCE.gpx`, `ALLEMAGNE.gpx`, `GRANDE-BRETAGNE.gpx`) — that list is the
   source of truth for the full set of 25 country names and their rough
   bounding boxes; a file whose name isn't in that list is silently never
   loaded, and adding a genuinely new country means adding it there too.
3. Build the dev flavor as usual
   (`./gradlew :app:assembleDevDebug`) — `RadarRepository` parses whichever
   country files the map viewport reaches, and the average-speed section
   lines are computed automatically from the same data (see [Average-speed
   radar sections](#average-speed-radar-sections) below).

**Expected file shape** — standard GPX 1.1, one `<wpt>` per camera, with an
OsmAnd-style `<extensions>` block:

```xml
<wpt lat="48.8566" lon="2.3522">
  <name>Radar Fixe FR 50</name>
  <type>Radars : FRANCE</type>
  <extensions>
    <osmand:icon>highway_speed_camera</osmand:icon>
  </extensions>
</wpt>
```

`RadarType.fromLabel()` (`data/model/RadarType.kt`) sorts each point into a
category purely from keywords in its `<name>`: `Feu Rouge` → red light,
`Troncon` → average-speed section, `Tunnel`, `Chantier` → roadworks, `Poid
lourd` → truck, `Covoiturage` → carpool, `Passage Niveau` → level crossing;
anything else is treated as a plain fixed radar. Average-speed entry/exit
pairs need no id or link in the source data — they're matched geometrically
at build time (next section).

## Average-speed radar sections

Average-speed ("Troncon") cameras work as a pair — an entry and an exit, with
your average speed measured between them — but the bundled GPX data has no
field linking the two: no id, no pair reference, nothing but the free-text
label (`Radar Troncon Debut FR` vs `...Fin FR`; Portugal and Norway don't
even distinguish entry from exit). So the entry/exit pairing and the road
geometry between them are both **computed at build time**, not at runtime,
and shipped as a bundled asset — the app itself makes no routing call and
needs no network to draw the lines.

**How pairing works.** Naive nearest-neighbour is wrong for about a third of
French sections: bidirectional controls place the opposite carriageway's
exit a few metres from an entry, and that decoy is closer than the real
partner. The generator (`buildSrc/src/main/kotlin/radar/SectionPairing.kt`)
rejects any candidate under 300 m, then does a greedy nearest-first 1:1
assignment, then drops any pair whose routed road distance is more than 2x
its straight-line distance (a real section routes almost straight; a
same-spot opposite-carriageway pair does not). Portugal/Norway, which have
no entry/exit labels at all, are paired the same way, purely by proximity.

**How it's generated.** The `generateRadarSections` Gradle task
(`app/build.gradle.kts`, implemented in `buildSrc/src/main/kotlin/radar/`) is
wired into **dev variants only** and
routes each pair through the [OSRM](http://project-osrm.org/) HTTP API and
writes `app/build/generated/assets/generateRadarSections/radar_sections.json`,
which AGP merges into the APK's assets automatically. The bundled GPX files
are declared as task inputs, so:

- an ordinary build is `UP-TO-DATE` and makes no network call at all;
- editing a radar GPX file regenerates the asset automatically, so the
  lines can never drift out of sync with the radar data;
- routed pairs are cached per-pair under `.gradle/osrm-route-cache/`
  (outside `build/`, so `./gradlew clean` doesn't force new network calls),
  so touching one country only re-routes that country's pairs.

If the routing service is unreachable, the task does **not** fail the build:
after 3 consecutive failures it stops trying and falls back to a straight
line for any pair it couldn't route (drawn dashed on the map to signal
that), while preserving the last successfully-generated asset if *nothing*
routed. Tunable via Gradle properties, e.g.:

```
./gradlew :app:assembleDevDebug \
  -PradarSections.osrmUrl=http://localhost:5000 \   # self-hosted OSRM/GraphHopper
  -PradarSections.minMeters=300 \
  -PradarSections.maxMeters=30000 \
  -PradarSections.maxDetour=2.0 \
  -PradarSections.strict=true                        # fail the build instead of falling back
```

At runtime, `SectionRepository` reads the generated JSON once and
`MapScreen` draws each section as a `Polyline` under the radar markers,
gated on the existing "Average speed" filter chip.

## Requirements to build

- **Android Studio** (bundles a compatible JDK) — or manually, JDK 17 and
  the Android SDK command-line tools.
- `app/google-services.json` is already checked into this repo (project
  `car-tracking-fc69c`, shared with the launcher). It must contain a client
  entry for **both** flavors' application ids — `com.carlauncher.companion`
  and `com.shenzou.carcompanion` — because the `com.google.gms.google-services`
  plugin validates every variant's id against the file even though the prod
  variant links no Firebase library at all. Building prod without that entry
  fails with *"No matching client found for package name
  com.shenzou.carcompanion"*; fix it by adding an Android app with that
  package name to the Firebase project and re-downloading the file.
- In the Firebase console for that project, **Anonymous** sign-in must be
  enabled under Authentication, and Firestore rules must allow
  `request.auth != null` on `tracks/{deviceId}/pushes` and
  `tracks/{deviceId}/events` (see `FIREBASE_START_EVENT.md` for the exact
  rule text — there's no `firestore.rules` file checked into either repo,
  it's configured by hand in the console).
- Anonymous sign-in and the Firestore rules only matter for the **dev**
  flavor; prod never contacts Firebase.
- The **first** dev build needs network access to generate
  `radar_sections.json` (see [Average-speed radar
  sections](#average-speed-radar-sections)) — after that it's cached and
  offline builds work fine. If you're offline on a clean checkout, that's
  expected to still succeed, just with straight fallback lines for any
  section that couldn't be routed.
- `local.properties` (gitignored in the repo root) holds optional build keys:
  Supabase credentials (`supabase.url`, `supabase.anonKey`) for cloud sync/backup,
  and an optional Carto API key (`carto.apiKey`) for retina Dark Matter map tiles.
  Without them, the app still builds and works offline.

## Cloud accounts (Supabase)

Optional across both flavors. Without an account nothing leaves the phone, and
without credentials in the build the feature doesn't appear at all.

Setup is documented in [`supabase/SETUP.md`](supabase/SETUP.md) — run
[`supabase/schema.sql`](supabase/schema.sql) in the SQL editor, then add to
`local.properties` (gitignored):

```properties
supabase.url=https://<project>.supabase.co
supabase.anonKey=<anon / publishable key>
carto.apiKey=<your carto api key (optional)>
```

The anon key is public by design — it ships in the APK and can be extracted
from it. Every access rule therefore lives in Postgres, in `schema.sql`. The
`service_role` key must never appear in this repo or in the app; it bypasses
row-level security completely.

### What it does with your data

Two independent axes, which are easy to conflate:

- **Backup** — six switches (cars, events, personal information, GPS history,
  statistics, trophies), all off by default, each opt-in separately.
- **Sharing** — one global level (only me / friends / everyone) plus a
  per-item toggle on individual cars and events.

Uploading is not sharing. **GPS history and overall statistics can never be
shared with anyone** — there is no setting for it and no RLS policy that would
permit it. The only GPS other users can ever see is the trace attached to an
event explicitly marked as shared.

A car's photo rides along with the "cars" backup toggle and its own share
flag, same as the rest of the row: it lives in a private `car-photos` Storage
bucket (not the database — see `supabase/schema.sql`'s STORAGE section), keyed
by car id, with `storage.objects` RLS that mirrors `cars_select` rather than
a public URL. It's plaintext on the server (unlike GPS/stats above), since
the whole point is for a friend to see it once the car is shared.

### End-to-end encryption

GPS history and statistics are encrypted on-device before upload
(`data/cloud/crypto/CryptoBox.kt`): a random data key encrypts the payloads,
and that key is stored server-side wrapped twice — once under a key derived
from the password (PBKDF2-HMAC-SHA256, 210k iterations), once under a one-time
recovery code shown at signup. The server holds only ciphertext.

Consequences, which the UI states plainly rather than burying:

- Changing the password re-wraps the data key, so backups survive untouched.
- Forgetting the password **and** losing the recovery code makes those backups
  permanently unreadable. The account and everything else recovers fine.

Session tokens are kept in `EncryptedSharedPreferences` rather than
supabase-kt's default plaintext store — see `data/cloud/EncryptedSessionManager.kt`.

### Password reset

By emailed link, handled through a private URI scheme (`carcompaniondev://` /
`carcompanion://`, one per flavor) rather than a typed code: Supabase locked
auth email template customisation for new free-tier projects on its default
email provider in June 2026, so `{{ .Token }}` can't be added to the template.
The stock template's link works as-is; the redirect URLs just need allow-listing
(see `supabase/SETUP.md`). No domain and no App Links setup are required.

Tapping the link reopens `MainActivity`, supabase-kt exchanges the PKCE code for
a session, and the app routes to "choose a new password". Magic-link sign-in is
deliberately unused — a user who never types a password has nothing to derive
the backup encryption key from.

### Legal

`app/src/main/assets/terms_of_use.md` and `privacy_policy.md` are shown in-app
and must be accepted (18+) before an account can be created; the accepted
version is recorded server-side. They are a good-faith draft, not
lawyer-reviewed — worth an actual lawyer's look before any public release.

### Backup, sync and restore

`CloudSyncManager` (`data/cloud/CloudSyncManager.kt`) is the one-way push:
local Room stays the source of truth, the cloud is a mirror of whatever
categories are switched on in Cloud settings. It runs two ways —

- **Automatically**, via a WorkManager `CloudSyncWorker` (network-constrained,
  every 30 minutes) started unconditionally from `CompanionApp.onCreate()` —
  it safely no-ops when signed out or the build has no Supabase credentials —
  plus an immediate one-off run on every app launch and the moment a category
  toggle flips on;
- **Manually**, via the "Sync now" button in Cloud settings, which calls
  `CloudSyncManager.syncAll()` directly rather than going through WorkManager,
  so the result shows up right away.

Each of the six categories is pushed and tracked independently: `cars` and
`events` carry `updatedAt`/`cloudSyncedAt` dirty-tracking columns (a row is
"dirty" whenever `updatedAt > cloudSyncedAt`), car modifications are replaced
wholesale alongside their parent car (cheap, and sidesteps needing a stable
remote id for them), an event's GPS trace is sent as one `event_tracks` row
holding a Google-style encoded polyline (`PolylineCodec.kt`) rather than one
row per point, trophy unlocks are pushed once and never revisited (they're
permanent), and a local delete propagates by diffing local ids against the
remote set on each sync pass, since Room has no concept of a tombstone to
push. GPS history and statistics go through the E2E path in `CryptoBox`/
`KeyVault`, chunked (1000 points/chunk) with a resumable per-device watermark
stored in `cloud_prefs` — an interrupted sync picks up where it left off
rather than re-uploading.

`CloudRestoreManager` is the deliberate exception to "one-way": a
user-triggered pull, for a fresh install or a new device, reached via
"Restore from cloud" in Cloud settings. It never runs on its own.

Signing out clears more than the session: `CloudPrefsRepository.resetOnSignOut()`
(upload toggles, visibility, GPS/stats watermarks), `CloudSyncManager.resetLocalSyncMarkers()`
(the per-row "already synced" flags), and `CloudSyncWorker.cancelAll()` all run
together — those markers are local flags, not scoped to an account, so a second
person signing in on the same phone must not inherit them.

### Friends and per-item sharing

A car or event's `is_shared` column (`ui/cloud/ShareToggle.kt`, shown on the car
and event detail screens, only once signed in) says "shareable at all" — who
can *actually* see it still follows the account-wide visibility level from
Cloud settings (only me / friends / everyone). The two are independent by
design: flipping the global level to "only me" instantly hides everything
already marked shared, without touching a single item's own switch.

Friends (`ui/friends/FriendsScreen.kt`, `data/cloud/FriendsRepository.kt`) are
added by exact username only — there is no browsing or listing of other
users anywhere in the app, matching `find_user_by_username`'s server-side
design (rate-limited exact match, see its comment in `schema.sql`) — that is
what stops the user base from being scraped. Requests, once accepted, are
symmetric; there's no "unfollow", only `block_user`, which is one-way and
permanent (no unblock RPC exists). Reading a friends list with usernames
attached needs a small SECURITY DEFINER function, `get_friends()` — a plain
client-side join of `friendships` against `profiles` is impossible by design,
since `profiles` blocks reading anyone else's row directly.

Anyone can flag a shared car via the "Report this car" action on
`SharedCarDetailScreen.kt`, which calls the `report_car()` RPC (an optional
free-text reason, deduplicated per reporter/car). There's no notification —
a filed report is only ever visible through the Supabase dashboard/SQL
editor, a deliberate manual-review choice rather than building out an alert
path. Separately, `profiles.blacklisted` is an operator-only flag (no
client-writable path — see the column-restricted `GRANT` in `schema.sql`)
that every visibility-bearing function (`can_view`, `activity_visible`,
`get_public_profile`, `find_user_by_username`, `send_friend_request`,
`get_friends`) checks and treats as "this account has no data to show
anyone but themselves," independent of and in addition to `block_user`.

### Feed and public profiles

The Feed (`ui/feed/FeedScreen.kt`) is the fifth bottom tab, and only exists at
all once signed in (`bottomTabs(signedIn: Boolean)` in `Destinations.kt`) — a
signed-out device has nothing to show there. It's a paged read of the
`get_feed` RPC: cars added/shared, events shared, trophies unlocked, each
rendered as its own taller card (`CarActivityCard`/`EventActivityCard`/
`TrophyActivityCard` in `FeedScreen.kt`) rather than one shared compact row —
a car card shows its modification count (`get_feed`'s `mod_count`, a
`car_modifications` count), an event card shows a small route preview above
its stats (`ui/common/RouteSketch.kt`, a lightweight accent-colored path —
deliberately not a real embedded map, since one live osmdroid `MapView` per
visible feed card would be a real perf/tile-bandwidth cost; its polyline is
fetched lazily per card via `SharedContentRepository.getEventTrackPreview`
and cached by event id so scrolling doesn't re-fetch it), and a trophy card
shows the trophy's description and tier. A segmented control at the top
switches between "Friends only" and "Everyone",
writing back to the account's `feedScope` in Cloud settings — a separate axis
from the *visibility* setting that controls who can see items *this* account
shares. Every visibility decision already happened server-side inside the
RPC — the client renders exactly what comes back, nothing more.

Tapping a card opens either a read-only shared car/event detail screen
(`ui/feed/SharedCarDetailScreen.kt`, `SharedEventDetailScreen.kt` — no edit,
no delete) or the actor's public profile
(`ui/feed/PublicProfileScreen.kt`, `get_public_profile` RPC), each section of
which — départements, trophies, garage — only renders if that person chose to
expose it. `SharedContentRepository.kt` fetches a stranger's shared cars,
events and trophies with a plain `.select { eq("owner_id", userId) } }`
rather than a dedicated RPC: the same RLS policies that gate a normal read
already resolve to exactly "shared and visible to me", so an unprivileged row
never comes back — there's nothing left for the client to additionally
filter. A shared event's trace renders through the exact same `TraceMap`
composable (`ui/common/TraceMap.kt`) a local event uses, not a parallel
reimplementation of it — `SharedContentRepository.getSharedEvent` decodes the
stored polyline back into the same `LocationPointEntity` shape either path
consumes. Tapping that compact trace map (on the shared event screen or a
local one) opens an almost-full-screen dialog with the same trail so it's
actually legible — one `expandable` flag on `TraceMap` itself, so both
callers get it for free.

## Build

Every task is flavor-qualified now (see [Build
flavors](#build-flavors-dev-and-prod)). `Dev` is the day-to-day default — it
is the full feature set:

```bash
./gradlew :app:assembleDevDebug     # full app  → app/build/outputs/apk/dev/debug/app-dev-debug.apk
./gradlew :app:assembleProdDebug    # stable-only → app/build/outputs/apk/prod/debug/app-prod-debug.apk
```

Plain `./gradlew assembleDebug` builds *both* flavors. Unit tests are
flavor-qualified too: `./gradlew :app:testDevDebugUnitTest` (plus
`./gradlew -p buildSrc test` for the build-logic suite, which is unflavored).

## Install

The two flavors have different application ids, so they install side by side
and can be run and compared on the same phone:

```bash
./gradlew :app:installDevDebug      # "Car Companion Dev", com.carlauncher.companion
./gradlew :app:installProdDebug     # "Car Companion",     com.shenzou.carcompanion
```

On first launch both flavors prompt for the notification permission
(Android 13+). **Dev** additionally offers to exempt itself from battery
optimization — accepting is required for the "car started" push to arrive
reliably while the app is backgrounded or closed. Prod never asks: it has no
push to receive.

## Localization

English (default/fallback), French and German, auto-detected from the
phone's system language — standard Android resource-qualifier resolution
(`res/values/`, `res/values-fr/`, `res/values-de/`), no in-app language
picker. Covers both flavors, including the dev-only beta screens. French and
German use informal address (`tu`/`toi`, `du`/`dein`) to match the app's
playful "neon arcade" identity. GPS coordinates in History stay
locale-invariant (`Locale.US`, `%.5f`) to avoid decimal-comma ambiguity;
proper nouns (French département names, the "Car Companion" brand) and
non-linguistic labels (speed-zone ranges) are left untranslated.

## Adding a car

*(Dev flavor only — prod tracks this phone and nothing else.)*

Open the **Devices** screen from the top bar and either wait for
auto-discovery (any device that has already pushed GPS data shows up on its
own) or tap **Add a car** and enter its `deviceId` directly — the same ID the
launcher generates for itself via `TileRepository.getOrCreateDeviceId()` on
its side. Give it a name; that's the nickname used both in the UI and in the
"car started" notification.

## Known limitations

- Trophy **département** detection is a nearest-centroid approximation
  (`data/repo/DepartmentLocator.kt` against a bundled 101-entry centroid
  table), not a polygon test. It is reliable well inside a département and
  can pick the neighbour within a few km of a border, or anywhere in the
  Paris petite couronne where 75/92/93/94 are only kilometres apart. Fine for
  a trophy counter; not a record of where the car actually was.

- No sign-in / user accounts — pairing is purely by knowing a `deviceId`,
  consistent with the launcher's open Firestore rules. Reasonable for a
  private, non-discoverable hobby project; not a pattern to reuse for
  anything handling real user data.
- The "car started" notification can't be delivered if the app has been
  explicitly Force Stopped via Android settings — no push, at any priority,
  can wake an app out of that state. Re-open the app once to clear it.
