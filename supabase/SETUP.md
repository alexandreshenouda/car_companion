# Supabase setup

Everything you need to do in the Supabase dashboard, once. Project:
`aehugggajqlnexhfrejf`.

---

## 1. Run the schema

SQL Editor → new query → paste all of [`schema.sql`](schema.sql) → Run.

It is idempotent, so this is also how you apply later changes: edit the file, re-run
the whole thing.

**Do not create these tables through the dashboard's table editor.** Tables made that
way come with no RLS policies, and a table without policies is readable by anyone who
extracts the anon key from the APK — which takes about a minute. The policies in
`schema.sql` *are* the security model.

### Verify it took

```sql
-- Every row must show rowsecurity = true. If any is false, stop and fix it.
select tablename, rowsecurity
from pg_tables
where schemaname = 'public'
order by tablename;
```

---

## 2. Get the anon key into the app

Settings → API → **Project API keys** → copy the `anon` / `public` key.

Add to `local.properties` in the repo root (gitignored):

```properties
supabase.url=https://aehugggajqlnexhfrejf.supabase.co
supabase.anonKey=eyJhbGciOi...
```

Without these the app still builds and runs — it just hides all cloud features and
behaves exactly like the offline version.

> **The `service_role` key must never leave the dashboard.** It bypasses every RLS
> policy in `schema.sql`. It does not belong in this repo, in `local.properties`, in
> the app, or in a chat message. There is no feature here that needs it.

---

## 3. Auth settings

Authentication → Providers → **Email**:

| Setting | Value | Why |
|---|---|---|
| Enable email provider | on | |
| Confirm email | your call — see below | |
| Minimum password length | 10 | Matches `AuthRepository.MIN_PASSWORD_LENGTH` |

**Confirm email on** is the safer choice: it stops someone signing up with an address
they don't control. The cost is that new users can't use the app until they click
through, and Supabase's built-in SMTP is limited to a few emails an hour. The app
handles both settings — with confirmation on, signup returns
`SignUpResult.NeedsEmailConfirmation` and encryption keys are provisioned on first
successful sign-in instead.

If the email volume limit or the locked templates become a real constraint, the fix is
custom SMTP (Resend's free tier is 3k/month), which also unlocks template editing on the
free plan. Nothing in the app depends on that, so it's optional.

### Redirect URLs for password reset — required

Password reset works by emailing a link that reopens the app. **No email template
editing is needed** (which matters: since 3 June 2026 new free-tier projects on
Supabase's default email provider [can no longer customise auth email
templates](https://supabase.com/changelog/46599-changes-to-email-template-customisation-on-free-tier),
so the `{{ .Token }}` code approach is unavailable unless you add custom SMTP). The
stock "Reset Password" template already contains the link — it just has to be allowed
to point back at the app.

Authentication → URL Configuration → **Redirect URLs** → add both:

```
carcompaniondev://auth-callback
carcompanion://auth-callback
```

Two entries because the flavors have different applicationIds and install side by side;
sharing one scheme would make Android show a disambiguation dialog on every reset link.
These match `authRedirectScheme` in `app/build.gradle.kts` and the `<intent-filter>` on
`MainActivity`.

A redirect URL that isn't on this list is silently replaced by the Site URL, so the link
will open a browser to something unhelpful instead of the app. If reset links don't come
back to the app, check this list first.

> Magic-link sign-in is deliberately **not** used. Beyond hitting the same locked-template
> limitation, a user who signs in without ever typing a password has nothing to derive the
> backup encryption key from — passwordless login and password-derived encryption can't
> coexist. See the encryption notes in `README.md`.

### Rate limits

Authentication → Rate Limits. The defaults are reasonable; the one worth lowering is
**token refresh** if you ever see abuse. Note the free plan has no *request*-level rate
limiting, which is why username lookup is throttled in SQL instead
(`lookup_attempts`, 60/hour/user).

---

## 4. Things to know about the free plan

- **The project pauses after 7 days with no activity.** The app treats an unreachable
  backend as "cloud unavailable" and keeps working locally, but you'll need to un-pause
  from the dashboard.
- **500 MB database.** GPS history is the only category that can realistically fill
  it — roughly 1000 hours of driving at 1 Hz, after the downsampling the app applies.
  Everything else is negligible.
- **No image uploads**, by product decision. Car photos never leave the phone, so a
  shared car shows other users its details but no picture.
- **2 projects max.** Both app flavors (`dev` and `prod`) share this one project, so a
  dev account and a prod account are the same account. If dev test data ever starts
  polluting the real feed, the fix is a second project and a per-flavor `local.properties`.

---

## 5. Verifying the security model actually holds

Do this once after running the schema, and again after any policy change. **Testing
through the app UI proves nothing** — the UI only shows what it fetched, so a broken
policy looks identical to a working one from inside the app. Query PostgREST directly.

Create two accounts (A and B), then grab B's access token (log it from the app, or use
the SQL editor's auth admin) and try to read A's data as B:

```bash
SUPABASE_URL=https://aehugggajqlnexhfrejf.supabase.co
ANON=<anon key>
B_TOKEN=<account B's access token>

# Each of these MUST return [] — an empty array, not an error and not rows.
for t in cars events private_backups user_keys profiles trophy_unlocks; do
  echo "--- $t"
  curl -s "$SUPABASE_URL/rest/v1/$t?select=*" \
    -H "apikey: $ANON" -H "Authorization: Bearer $B_TOKEN"
done
```

Expected: B sees only B's own rows. Specifically:

- `private_backups` and `user_keys` — **always** empty for anyone but the owner. There
  is no sharing policy on these tables and there must never be one.
- `cars` / `events` — only A's rows that are both `is_shared = true` *and* reachable
  under A's current `profiles.visibility`.
- `profiles` — only B's own row. If B can see A's profile row directly, the discovery
  RPC has been bypassed and the whole user list is enumerable.

Then confirm the encryption is real: open `private_backups` in the dashboard's table
editor. The `ciphertext` column must be unreadable base64. If you can recognise
coordinates in there, the client-side encryption is not running.

Finally, check that the global switch is retroactive: set A's `visibility` to
`private` and re-run the loop. B must immediately see nothing, including feed
activities that were visible a moment ago.
