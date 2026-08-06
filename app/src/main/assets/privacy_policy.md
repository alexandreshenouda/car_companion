# Privacy Policy

**Version 2026-08-03**

<!--
  PLACEHOLDERS — replace before any public release:
    Alexandre Shenouda · carcompanion.compost087@passmail.net · France
  See terms_of_use.md. Also not lawyer-reviewed.
-->

**Alexandre Shenouda** ("we") is the data controller for the Car Companion cloud service. This
explains what we hold, why, and what you can do about it.

The short version: **without an account, nothing leaves your phone.** With an account, only
the categories you switch on leave your phone, and the two most sensitive ones are
encrypted so that we cannot read them.

## 1. If you don't create an account

We collect nothing. No account, no analytics, no advertising, no trackers, no crash
reporting. GPS recording, history, statistics, your garage, events and trophies all live in
a database on your phone and go nowhere.

Map tiles are fetched from OpenStreetMap-based tile servers as you pan the map, which
necessarily tells those servers your approximate viewing area. That is a direct connection
between your phone and them; we are not involved and receive nothing.

## 2. If you do create an account

### Always stored

- **Email address** — sign-in, and password reset.
- **Username** — so friends can find you.
- **Password** — stored only as a bcrypt hash by our authentication provider. We never
  see, store or log the password itself.
- **Terms version and acceptance date** — to show that you accepted, and when.
- **Encrypted key material** — lets you decrypt your own backups on a new phone.

### Stored only if you switch it on

Each of these is off until you enable it, and can be turned off again:

- **Cars** — makes, models, years, odometer, modifications. **Photos are never uploaded**
  and stay on your phone.
- **Events** — meets, track days, explorations, and the GPS trace attached to them.
- **Personal information** — age, city, and the départements you'd like to meet in.
- **GPS history** — *end-to-end encrypted* (see §3).
- **Statistics** — *end-to-end encrypted* (see §3).
- **Trophies** — which achievements you've unlocked.

### Created by using the service

Friend relationships, and Feed entries recording that you shared a car or event or unlocked
a trophy. These are visible only under the sharing rules you chose.

## 3. End-to-end encryption

Your GPS history and statistics are encrypted **on your phone**, with AES-256-GCM, using a
key that is itself protected by a key derived from your password. We store the result. We
do not have the key and cannot decrypt it — not on request, not for debugging, not if
compelled, because there is nothing to hand over but ciphertext.

Consequences, stated plainly:

- These categories can never be shared with another user. There is no feature for it.
- If you forget your password **and** lose your recovery code, this data is permanently
  unrecoverable. We cannot help. Everything else about your account survives.

## 4. What is never shared, and what can be

Never visible to another user, under any setting:

- your full GPS history;
- your overall driving statistics;
- your email address;
- car photos (they are never uploaded at all).


Can be visible, only if you switch it on, and only to the audience you chose (nobody /
friends / everyone):

- individual cars you mark as shared;
- individual events you mark as shared, including their GPS trace;
- your trophies, city and départements, if you enable those profile sections.

A GPS trace shows where you drove. Consider whether that reveals where you live before
sharing an event.

## 5. Who else sees your data

- **Supabase** hosts the database and authentication on our behalf, as our processor.
  They can technically access what is stored unencrypted; they cannot read the
  end-to-end-encrypted categories.
- **Other users**, strictly as described in §4.
- **Nobody else.** We do not sell your data, share it with advertisers or data brokers, or
  use it to train anything.

Data is stored on Supabase infrastructure; the region is set at project creation.

## 6. How long we keep it

Until you delete it. Deleting an item removes it from the cloud; deleting your account
removes everything associated with it, including encrypted backups, friendships and Feed
entries. Deletion is immediate rather than deferred, and is not recoverable.

Backups held by our hosting provider may persist for a short period afterwards as part of
their normal operation.

## 7. Your rights

Under the GDPR and equivalent law you can:

- **access** the data we hold about you;
- **correct** it — most of it is directly editable in the app;
- **delete** it — Profile → Cloud → Delete account, no email required;
- **export** it — GPX export for traces, and account data export in the app;
- **object** or **restrict** processing;
- **complain** to your data protection authority (in France, the CNIL).

Our legal basis is contract (running the account you asked for) and consent (each upload
category, which you switch on and can switch off).

Ask at carcompanion.compost087@passmail.net for anything the app doesn't let you do directly.

## 8. Children

The service is for adults only — 18 or over. We do not knowingly hold data about children.
If you believe we do, write to carcompanion.compost087@passmail.net and we will delete it.

## 9. Security

Passwords are bcrypt-hashed by our authentication provider and never seen by us. Access to
every table is enforced database-side by row-level security rules, so one user's
credentials cannot reach another user's rows. Session tokens are stored encrypted on your
device using the Android Keystore. GPS history and statistics are end-to-end encrypted as
described in §3.

No system is perfect. If you find a security problem, please tell us at carcompanion.compost087@passmail.net
before telling anyone else.

## 10. Changes

We'll ask you to accept a new version in the app if we change this materially.

## 11. Contact

carcompanion.compost087@passmail.net
