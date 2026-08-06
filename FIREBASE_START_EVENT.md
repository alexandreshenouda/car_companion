# Firebase "launcher started" signal

This documents a small addition to the existing GPS-tracking Firebase setup
(see `GPS_TRACKING.md` for the full picture — project id, auth model, and
the `pushes` data model this sits alongside). It's written for whoever
(human or agent) wants to build something on top of it next.

## What it is

Two things happen every time the launcher starts (`MainActivity.onCreate()`,
cold start only — not on resume):

1. A Firestore document is written under `tracks/{deviceId}/events` — a
   passive signal a client can observe if it's actively listening.
2. A **real FCM push** is sent to topic `launcher_events`. Any client (e.g.
   a future companion app) subscribed to that topic receives a
   system-level push regardless of whether it's open or listening at the
   time — unlike the Firestore signal above.

Both are fire-and-forget from `MainActivity.recordLauncherStartedEvent()`;
neither blocks the other or app startup.

FCM sending runs entirely on the free **Spark** plan — no Blaze upgrade, no
credit card. The launcher calls the FCM HTTP v1 send API directly, from the
app itself, authenticating with a Google service-account key bundled in the
APK. There is no Cloud Function and none is needed — Cloud Functions (and
any other GCP serverless compute) is the only piece of this that would
actually require Blaze, and this design has no such piece.

**Trade-off, accepted deliberately:** the service-account key ships inside
the installed APK. Anyone who extracts it (APK decompilation is not hard)
could use it to send arbitrary FCM pushes through this Firebase project. It
cannot read/write Firestore data or do anything else in the project — the
key is scoped to messaging-only (see "Firebase Console setup" below), so
the blast radius of a leak is "someone can spam a push topic nobody
sensitive listens to," not "someone owns the whole project." Reasonable for
a private, non-discoverable hobby project, consistent with the existing
open Firestore rules — not a pattern to reuse as-is for anything handling
real user data.

## Data model

```
tracks/{deviceId}/events/{autoId}
```

Sibling to the existing `tracks/{deviceId}/pushes/{autoId}` GPS-trail
collection — same `deviceId` (the per-install UUID from
`TileRepository.getOrCreateDeviceId()`), same anonymous-auth model.

```jsonc
{
  "type": "launcher_started",
  "at": <Firestore server Timestamp>
}
```

- `type`: currently always the literal string `"launcher_started"`. Kept as
  a field (rather than assuming the collection only ever holds one kind of
  event) in case other event types get added later.
- `at`: set via `FieldValue.serverTimestamp()`, same convention as
  `pushedAt` in the `pushes` collection.

One document is written per cold start of `MainActivity`, not per resume —
backgrounding/foregrounding the launcher via tile taps does not create new
events.

## Auth

**Firestore write:** requires anonymous auth, same as the GPS trail
(`request.auth != null`). Issued via the shared helper
`AppAuth.ensureSignedIn { ... }`
(`app/src/main/java/com/carlauncher/pcm/AppAuth.kt`), which both this
feature and `GpsTracker` use — it signs in anonymously if needed and only
runs the callback once a real user exists, avoiding a `permission-denied`
race on cold start (Firestore does not retry a `permission-denied` write
once auth catches up — it's a terminal failure, not a queued one).

**FCM push:** a completely separate auth flow, unrelated to
`Firebase.auth`/anonymous sign-in. It's a standard Google service-account
OAuth2 flow — see "How the push is sent" below.

## Security rule (manual step — not in this repo)

The existing rule only covers `pushes`:

```
match /tracks/{deviceId}/pushes/{pushId} {
  allow read, write: if request.auth != null;
}
```

Firestore rules don't cascade to sibling subcollections, so `events` needs
its own block, added manually in the Firebase Console (Firestore →
Rules) — there is no `firestore.rules` file checked into this repo, the
rule text here is documentation of what must be configured by hand, same
as the original `pushes` rule:

```
match /tracks/{deviceId}/events/{eventId} {
  allow read, write: if request.auth != null;
}
```

This rule only governs the Firestore document (item 1 above). The FCM push
(item 2) isn't gated by Firestore rules at all — it's authorized by the
service-account key instead.

## How the push is sent (`FcmPusher.kt`)

No `firebase-messaging` Gradle dependency was added — that SDK is for
*receiving* pushes, not sending them, and sending is all this app needs to
do. Instead, `FcmPusher` talks to Google's REST APIs directly using only
platform classes (`java.security`, `org.json`, `HttpURLConnection` — zero
new dependencies):

1. Loads a service-account key from the bundled asset
   `app/src/main/assets/fcm_service_account.json` (see "Firebase Console
   setup" below — **this file is gitignored and not in this repo**; you
   must place it yourself, and a checkout without it just skips the push
   silently, logging an error rather than crashing).
2. Builds and RS256-signs a JWT with that key's private key
   (`iss`/`scope`/`aud`/`iat`/`exp` claims — standard Google service-account
   JWT-bearer flow), and exchanges it for a short-lived OAuth2 access token
   at `https://oauth2.googleapis.com/token`.
3. `POST`s to `https://fcm.googleapis.com/v1/projects/{project_id}/messages:send`
   with that token, targeting `topic: "launcher_events"`.

All of this runs on a background thread (network + RSA signing, never on
the main thread), and every step is wrapped so a failure (missing asset,
expired/misconfigured key, no network) just logs an error — it never
crashes or blocks the rest of app startup.

## Firebase Console setup (manual steps — do these once)

1. **Create a narrowly-scoped service account.** Do *not* use the
   "Generate new private key" button under Firebase Console → Project
   Settings → Service Accounts — that one is bound to the default
   `firebase-adminsdk-*` account, which has broad Editor-level access to
   the whole project, a much bigger leak surface than this needs. Instead,
   in [Google Cloud Console](https://console.cloud.google.com/) → IAM &
   Admin → Service Accounts (same project, `car-tracking-fc69c`):
   - Create service account (any name, e.g. `fcm-sender`).
   - Grant it exactly one role: **Firebase Cloud Messaging API Admin**
     (`roles/firebasemessaging.admin`, sometimes labeled "Firebase Cloud
     Messaging Admin" in the picker). Nothing else — no Firestore, no
     Editor.
   - Open the new service account → Keys → Add key → Create new key →
     JSON. This downloads the file you need.
2. **Place the key in the repo, uncommitted.** Save the downloaded file as
   `app/src/main/assets/fcm_service_account.json`. It's already covered by
   `.gitignore` — do not force-add it.
3. **Confirm the Cloud Messaging API is enabled**: Google Cloud Console →
   APIs & Services → Library → search "Firebase Cloud Messaging API" →
   should show as enabled (it is by default for any Firebase project with
   FCM available, but worth a glance if the send call fails with a 403).
4. **No Blaze upgrade needed** — this whole feature runs on the free Spark
   plan.
5. Optional, only relevant once a companion app exists: it should call
   `FirebaseMessaging.getInstance().subscribeToTopic("launcher_events")`
   once (e.g. on first launch) to actually receive these pushes — sending
   to a topic with zero subscribers succeeds silently, it just reaches no
   one.

## Building on this

To receive the real push from a companion app: add `firebase-messaging` to
its Gradle deps, subscribe to topic `launcher_events` as described above,
and implement a `FirebaseMessagingService` to handle the incoming message
(show a notification, wake up UI, etc.) — standard FCM client-side
integration, no different from any other Android app receiving FCM.

To observe "the car just started" as a passive signal instead (e.g. for
history/backfill, or while the companion app is already open and doesn't
need a system push): sign in anonymously, then listen to
`tracks/{deviceId}/events` ordered by `at`, e.g.
`.whereEqualTo("type", "launcher_started")`.

## Source files (launcher side)

- `app/src/main/java/com/carlauncher/pcm/AppAuth.kt` — shared anonymous
  sign-in helper (Firestore event write only).
- `app/src/main/java/com/carlauncher/pcm/FcmPusher.kt` — mints its own
  OAuth token from the bundled service-account key and sends the real FCM
  push to topic `launcher_events`.
- `app/src/main/java/com/carlauncher/pcm/MainActivity.kt` —
  `recordLauncherStartedEvent()`, called once from `onCreate()`, does both
  the Firestore write and calls `FcmPusher.sendLauncherStartedPush()`.
