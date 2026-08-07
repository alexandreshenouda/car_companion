-- =====================================================================
-- Car Companion — Supabase schema
--
-- Paste this whole file into the Supabase SQL editor and run it.
-- It is idempotent: re-running it is safe and is how you apply changes.
--
-- SECURITY MODEL — read before editing anything below
--
--   The anon key ships inside the APK and is therefore public. Every
--   security guarantee in this app lives in this file, not in the client.
--   Consequently:
--
--     1. Every table has RLS ENABLED *and* FORCED, and denies by default.
--        A table added without RLS is readable by anyone with the APK.
--     2. Every SECURITY DEFINER function pins `SET search_path`. Without
--        it, a caller can shadow `public` and escalate privileges.
--     3. `profiles` is never directly selectable by strangers. Discovery
--        goes through find_user_by_username(), which is exact-match and
--        rate-limited, so the user list cannot be scraped.
--     4. Sharing visibility is resolved AT READ TIME from the owner's
--        current profile.visibility. It is never denormalised onto rows,
--        so flipping the global setting to 'private' instantly hides all
--        historic content, including old feed entries.
--     5. private_backups + user_keys are owner-only and hold ciphertext
--        the server cannot decrypt. No policy may ever widen them.
-- =====================================================================


-- =====================================================================
-- 1. ENUM-LIKE DOMAINS
-- =====================================================================

do $$ begin
  create type public.visibility_level as enum ('private', 'friends', 'public');
exception when duplicate_object then null; end $$;

do $$ begin
  create type public.feed_scope as enum ('friends', 'everyone');
exception when duplicate_object then null; end $$;

do $$ begin
  create type public.friendship_status as enum ('pending', 'accepted', 'blocked');
exception when duplicate_object then null; end $$;

do $$ begin
  create type public.activity_kind as enum (
    'car_added', 'car_shared', 'event_shared', 'trophy_unlocked'
  );
exception when duplicate_object then null; end $$;

do $$ begin
  create type public.backup_kind as enum ('gps', 'stats');
exception when duplicate_object then null; end $$;


-- =====================================================================
-- 2. TABLES
-- =====================================================================

-- ---------------------------------------------------------------------
-- profiles — one row per auth user, created automatically by trigger.
-- Usernames are stored already-lowercased so a plain UNIQUE is enough
-- (no citext extension needed).
-- ---------------------------------------------------------------------
create table if not exists public.profiles (
  id                uuid primary key references auth.users(id) on delete cascade,
  username          text        not null unique,
  display_name      text,
  age               int,
  city              text,
  department_codes  text[]      not null default '{}',

  -- The single global switch governing everything this user shares.
  visibility        public.visibility_level not null default 'private',
  -- Which activities this user wants to SEE in their own feed.
  feed_scope        public.feed_scope       not null default 'friends',

  -- Per-section opt-in for the public profile page.
  share_profile     boolean not null default false,
  share_garage      boolean not null default false,
  share_trophies    boolean not null default false,

  terms_version     text,
  terms_accepted_at timestamptz,

  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),

  constraint username_shape  check (username ~ '^[a-z0-9_]{3,20}$'),
  constraint display_name_len check (display_name is null or char_length(display_name) <= 40),
  constraint city_len         check (city is null or char_length(city) <= 80),
  -- 18+ requirement, enforced in the database and not only in the UI.
  constraint age_adult        check (age is null or (age >= 18 and age <= 120)),
  constraint departments_sane check (cardinality(department_codes) <= 101)
);

-- ---------------------------------------------------------------------
-- friendships — undirected once accepted, stored directionally.
-- ---------------------------------------------------------------------
create table if not exists public.friendships (
  requester_id uuid not null references auth.users(id) on delete cascade,
  addressee_id uuid not null references auth.users(id) on delete cascade,
  status       public.friendship_status not null default 'pending',
  created_at   timestamptz not null default now(),
  responded_at timestamptz,
  primary key (requester_id, addressee_id),
  constraint no_self_friendship check (requester_id <> addressee_id)
);

create index if not exists friendships_addressee_idx on public.friendships (addressee_id, status);
create index if not exists friendships_requester_idx on public.friendships (requester_id, status);

-- ---------------------------------------------------------------------
-- cars — mirrors CarEntity minus photoPath (no image uploads).
-- ---------------------------------------------------------------------
create table if not exists public.cars (
  id                uuid primary key,
  owner_id          uuid not null references auth.users(id) on delete cascade,
  name              text not null,
  brand             text,
  model             text,
  year              int,
  details           text,
  odometer_km       double precision,
  is_favorite       boolean not null default false,
  is_shared         boolean not null default false,
  -- No path/URL stored here — the object lives in the `car-photos` bucket at a
  -- fixed key derived from `id` (see section 8, STORAGE). Null means no photo;
  -- non-null doubles as a change marker so viewers know to re-fetch.
  photo_updated_at  timestamptz,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  constraint car_name_len check (char_length(name) between 1 and 60),
  constraint car_year_sane check (year is null or (year between 1885 and 2100)),
  constraint car_details_len check (details is null or char_length(details) <= 2000),
  constraint car_odo_sane check (odometer_km is null or (odometer_km >= 0 and odometer_km < 10000000))
);

-- Idempotent add for anyone re-running this file against a database created before photos existed.
alter table public.cars add column if not exists photo_updated_at timestamptz;

create index if not exists cars_owner_idx on public.cars (owner_id);
create index if not exists cars_shared_idx on public.cars (owner_id) where is_shared;

create table if not exists public.car_modifications (
  id           uuid primary key,
  car_id       uuid not null references public.cars(id) on delete cascade,
  -- Denormalised so RLS policies never need to join through cars.
  owner_id     uuid not null references auth.users(id) on delete cascade,
  title        text not null,
  category     text,
  installed_at timestamptz,
  cost         double precision,
  notes        text,
  created_at   timestamptz not null default now(),
  constraint mod_title_len check (char_length(title) between 1 and 100),
  constraint mod_notes_len check (notes is null or char_length(notes) <= 2000)
);

create index if not exists car_mods_car_idx on public.car_modifications (car_id);

-- ---------------------------------------------------------------------
-- events + event_tracks.
--
-- Aggregates are denormalised onto `events` so the feed can render a card
-- without ever fetching the trace. The trace itself is one row holding an
-- encoded polyline, not one row per GPS point — roughly 10x smaller, which
-- matters a great deal on the 500 MB free tier.
-- ---------------------------------------------------------------------
create table if not exists public.events (
  id              uuid primary key,
  owner_id        uuid not null references auth.users(id) on delete cascade,
  car_id          uuid references public.cars(id) on delete set null,
  title           text not null,
  type            text not null,
  start_ts        timestamptz not null,
  end_ts          timestamptz not null,
  location_label  text,
  notes           text,
  points_source   text not null default 'DEVICE',
  distance_km     double precision not null default 0,
  max_speed_kmh   int not null default 0,
  moving_seconds  bigint not null default 0,
  point_count     int not null default 0,
  is_shared       boolean not null default false,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  constraint event_title_len check (char_length(title) between 1 and 100),
  constraint event_notes_len check (notes is null or char_length(notes) <= 4000),
  constraint event_range check (end_ts >= start_ts),
  constraint event_source check (points_source in ('DEVICE', 'GPX'))
);

create index if not exists events_owner_idx on public.events (owner_id, start_ts desc);
create index if not exists events_shared_idx on public.events (owner_id) where is_shared;

create table if not exists public.event_tracks (
  event_id         uuid primary key references public.events(id) on delete cascade,
  owner_id         uuid not null references auth.users(id) on delete cascade,
  -- Google encoded-polyline (precision 5) of the lat/lng sequence.
  encoded_polyline text not null,
  -- Parallel arrays, same length as the decoded polyline.
  speeds_kmh       int[] not null default '{}',
  time_offsets_s   int[] not null default '{}',
  updated_at       timestamptz not null default now(),
  -- Hard ceiling: an event trace should never be megabytes.
  constraint polyline_size check (octet_length(encoded_polyline) <= 400000)
);

-- ---------------------------------------------------------------------
-- trophy_unlocks
-- ---------------------------------------------------------------------
create table if not exists public.trophy_unlocks (
  owner_id    uuid not null references auth.users(id) on delete cascade,
  trophy_id   text not null,
  unlocked_at timestamptz not null,
  primary key (owner_id, trophy_id),
  constraint trophy_id_len check (char_length(trophy_id) between 1 and 60)
);

-- ---------------------------------------------------------------------
-- private_backups — end-to-end encrypted. GPS history and statistics.
--
-- The server stores opaque ciphertext and cannot read it. There is
-- deliberately no shared-visibility policy on this table and there must
-- never be one: this data is private at all times by product decision.
-- ---------------------------------------------------------------------
create table if not exists public.private_backups (
  owner_id     uuid not null references auth.users(id) on delete cascade,
  kind         public.backup_kind not null,
  chunk_index  int not null,
  -- AES-256-GCM over gzip-compressed JSON, base64. AAD binds owner|kind|index
  -- so chunks cannot be swapped between users, categories, or positions.
  --
  -- base64 text rather than bytea on purpose: PostgREST renders bytea as a
  -- `\x`-escaped hex string, which every client then has to special-case.
  ciphertext   text not null,
  nonce        text not null,
  created_at   timestamptz not null default now(),
  primary key (owner_id, kind, chunk_index),
  constraint nonce_len check (char_length(nonce) = 16),          -- base64 of 12 bytes
  constraint chunk_size check (char_length(ciphertext) <= 5600000)
);

-- ---------------------------------------------------------------------
-- user_keys — the wrapped data-encryption key.
--
-- Two independent wrappings of the SAME random DEK:
--   * under a key derived from the password  (normal unlock)
--   * under a key derived from a recovery code (forgotten password)
-- Losing both means the encrypted backups are unrecoverable, by design.
-- ---------------------------------------------------------------------
-- All byte fields are base64 text, for the same PostgREST reason as above.
create table if not exists public.user_keys (
  owner_id              uuid primary key references auth.users(id) on delete cascade,
  wrapped_dek_password  text not null,
  salt_password         text not null,
  nonce_password        text not null,
  wrapped_dek_recovery  text not null,
  salt_recovery         text not null,
  nonce_recovery        text not null,
  kdf                   text not null default 'PBKDF2-HMAC-SHA256',
  kdf_iterations        int  not null default 210000,
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now(),
  constraint salt_len_pw   check (char_length(salt_password) = 24),   -- base64 of 16 bytes
  constraint salt_len_rec  check (char_length(salt_recovery) = 24),
  constraint nonce_len_pw  check (char_length(nonce_password) = 16),  -- base64 of 12 bytes
  constraint nonce_len_rec check (char_length(nonce_recovery) = 16),
  constraint iterations_floor check (kdf_iterations >= 210000)
);

-- ---------------------------------------------------------------------
-- activities — the feed spine. Append-only, written by triggers.
-- ---------------------------------------------------------------------
create table if not exists public.activities (
  id          uuid primary key default gen_random_uuid(),
  actor_id    uuid not null references auth.users(id) on delete cascade,
  kind        public.activity_kind not null,
  subject_id  uuid,     -- car / event id
  subject_key text,     -- trophy id (not a uuid)
  created_at  timestamptz not null default now()
);

create index if not exists activities_feed_idx on public.activities (created_at desc);
create index if not exists activities_actor_idx on public.activities (actor_id, created_at desc);

-- ---------------------------------------------------------------------
-- lookup_attempts — username-search rate limiting. The free plan has no
-- request-level rate limiting, so enumeration is throttled here.
-- ---------------------------------------------------------------------
create table if not exists public.lookup_attempts (
  actor_id     uuid not null references auth.users(id) on delete cascade,
  window_start timestamptz not null,
  attempts     int not null default 0,
  primary key (actor_id, window_start)
);


-- =====================================================================
-- 3. HELPER FUNCTIONS
--
-- All SECURITY DEFINER (they must see rows the caller's own policies
-- would hide) and all with a pinned search_path.
-- =====================================================================

create or replace function public.are_friends(a uuid, b uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select exists (
    select 1 from public.friendships f
    where f.status = 'accepted'
      and ((f.requester_id = a and f.addressee_id = b)
        or (f.requester_id = b and f.addressee_id = a))
  );
$$;

-- Is the current user allowed to see an item owned by p_owner that carries
-- the per-item share flag p_is_shared?
--
-- Owner always yes. Otherwise the item must be shared AND the owner's
-- current global visibility must reach the viewer. Reading profile.visibility
-- here (rather than a copy on the row) is what makes "set everything to
-- private" take effect instantly and retroactively.
create or replace function public.can_view(p_owner uuid, p_is_shared boolean)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select case
    when auth.uid() is null then false
    when p_owner = auth.uid() then true
    when not coalesce(p_is_shared, false) then false
    else exists (
      select 1 from public.profiles p
      where p.id = p_owner
        and (
          p.visibility = 'public'
          or (p.visibility = 'friends' and public.are_friends(auth.uid(), p_owner))
        )
    )
  end;
$$;

-- Whether p_owner has opted their trophies into being visible at all (before the
-- friends/public visibility check even applies). SECURITY DEFINER on purpose: a plain
-- policy expression runs as the CALLING user, and `profiles_select_own` means a direct
-- `select ... from profiles` can only ever see your own row — so a raw subquery here would
-- silently return nothing for every owner except yourself, making `share_trophies` look
-- false to everyone but its own owner regardless of its real value. Bypassing that RLS
-- deliberately, the same way `can_view` above already does, is the whole point of this
-- function existing rather than inlining the check.
create or replace function public.shares_trophies(p_owner uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select coalesce((select p.share_trophies from public.profiles p where p.id = p_owner), false);
$$;

-- Feed rows re-check their subject, so unsharing a car or event removes it
-- from everyone's feed — including the owner's own — without needing to
-- delete the activity row. The owner only ever short-circuits the *profile*
-- visibility gate below (that gate is about whether other people are
-- allowed to see this actor's activity at all, meaningless applied to
-- yourself) — the per-item is_shared check still applies to the owner
-- exactly like anyone else, so your own feed shows the same thing a friend
-- would see, not your full sharing history. trophy_unlocked is the one
-- exception: it's your own achievement regardless of the share_trophies
-- toggle, which only governs whether *others* see it.
create or replace function public.activity_visible(
  p_actor uuid, p_kind public.activity_kind, p_subject uuid, p_subject_key text
)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select case
    when auth.uid() is null then false
    when p_actor <> auth.uid() and not exists (
      select 1 from public.profiles p
      where p.id = p_actor
        and (
          p.visibility = 'public'
          or (p.visibility = 'friends' and public.are_friends(auth.uid(), p_actor))
        )
    ) then false
    when p_kind in ('car_added', 'car_shared') then exists (
      select 1 from public.cars c
      where c.id = p_subject and c.owner_id = p_actor and c.is_shared
    )
    when p_kind = 'event_shared' then exists (
      select 1 from public.events e
      where e.id = p_subject and e.owner_id = p_actor and e.is_shared
    )
    when p_kind = 'trophy_unlocked' then (
      p_actor = auth.uid()
      or exists (select 1 from public.profiles p where p.id = p_actor and p.share_trophies)
    )
    else false
  end;
$$;


-- =====================================================================
-- 4. TRIGGERS
-- =====================================================================

-- Profile row is created as part of signup. Username uniqueness is
-- enforced here, inside the same transaction as the auth user, so a taken
-- username aborts the signup rather than leaving an account without a
-- profile.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_username text := lower(trim(coalesce(new.raw_user_meta_data->>'username', '')));
  v_terms    text := new.raw_user_meta_data->>'terms_version';
begin
  if v_username !~ '^[a-z0-9_]{3,20}$' then
    raise exception 'invalid_username'
      using hint = 'Username must be 3-20 chars: a-z, 0-9, underscore.';
  end if;

  if v_terms is null or v_terms = '' then
    raise exception 'terms_not_accepted';
  end if;

  insert into public.profiles (id, username, display_name, terms_version, terms_accepted_at)
  values (
    new.id,
    v_username,
    nullif(trim(coalesce(new.raw_user_meta_data->>'display_name', '')), ''),
    v_terms,
    now()
  );
  return new;
exception
  when unique_violation then
    raise exception 'username_taken'
      using hint = 'That username is already in use.';
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();


create or replace function public.touch_updated_at()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

drop trigger if exists profiles_touch on public.profiles;
create trigger profiles_touch before update on public.profiles
  for each row execute function public.touch_updated_at();

drop trigger if exists cars_touch on public.cars;
create trigger cars_touch before update on public.cars
  for each row execute function public.touch_updated_at();

drop trigger if exists events_touch on public.events;
create trigger events_touch before update on public.events
  for each row execute function public.touch_updated_at();

drop trigger if exists user_keys_touch on public.user_keys;
create trigger user_keys_touch before update on public.user_keys
  for each row execute function public.touch_updated_at();


-- Activity emission. Deliberately idempotent-ish: re-sharing something
-- that was already announced does not produce a duplicate card. Unsharing
-- deletes the row rather than leaving it for activity_visible() to filter
-- forever — it's already permanently invisible to everyone at that point
-- (owner included, see activity_visible()'s comment), so keeping it around
-- is pure bloat that every future get_feed() call has to scan past.
create or replace function public.emit_car_activity()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
  if new.is_shared and (tg_op = 'INSERT' or not old.is_shared) then
    insert into public.activities (actor_id, kind, subject_id)
    select new.owner_id, 'car_added', new.id
    where not exists (
      select 1 from public.activities a
      where a.actor_id = new.owner_id and a.subject_id = new.id
    );
  elsif tg_op = 'UPDATE' and old.is_shared and not new.is_shared then
    delete from public.activities
    where actor_id = new.owner_id and subject_id = new.id and kind = 'car_added';
  end if;
  return new;
end;
$$;

drop trigger if exists cars_activity on public.cars;
create trigger cars_activity after insert or update of is_shared on public.cars
  for each row execute function public.emit_car_activity();


create or replace function public.emit_event_activity()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
  if new.is_shared and (tg_op = 'INSERT' or not old.is_shared) then
    insert into public.activities (actor_id, kind, subject_id)
    select new.owner_id, 'event_shared', new.id
    where not exists (
      select 1 from public.activities a
      where a.actor_id = new.owner_id and a.subject_id = new.id
    );
  elsif tg_op = 'UPDATE' and old.is_shared and not new.is_shared then
    delete from public.activities
    where actor_id = new.owner_id and subject_id = new.id and kind = 'event_shared';
  end if;
  return new;
end;
$$;

drop trigger if exists events_activity on public.events;
create trigger events_activity after insert or update of is_shared on public.events
  for each row execute function public.emit_event_activity();


create or replace function public.emit_trophy_activity()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
  insert into public.activities (actor_id, kind, subject_key)
  select new.owner_id, 'trophy_unlocked', new.trophy_id
  where not exists (
    select 1 from public.activities a
    where a.actor_id = new.owner_id
      and a.kind = 'trophy_unlocked'
      and a.subject_key = new.trophy_id
  );
  return new;
end;
$$;

drop trigger if exists trophies_activity on public.trophy_unlocks;
create trigger trophies_activity after insert on public.trophy_unlocks
  for each row execute function public.emit_trophy_activity();


-- =====================================================================
-- 5. ROW LEVEL SECURITY
--
-- Enabled AND forced on every table. FORCE matters: without it the table
-- owner role bypasses policies, which is easy to trip over when testing
-- from the SQL editor and get a false sense of safety.
-- =====================================================================

alter table public.profiles          enable row level security;
alter table public.friendships       enable row level security;
alter table public.cars              enable row level security;
alter table public.car_modifications enable row level security;
alter table public.events            enable row level security;
alter table public.event_tracks      enable row level security;
alter table public.trophy_unlocks    enable row level security;
alter table public.private_backups   enable row level security;
alter table public.user_keys         enable row level security;
alter table public.activities        enable row level security;
alter table public.lookup_attempts   enable row level security;

alter table public.profiles          force row level security;
alter table public.friendships       force row level security;
alter table public.cars              force row level security;
alter table public.car_modifications force row level security;
alter table public.events            force row level security;
alter table public.event_tracks      force row level security;
alter table public.trophy_unlocks    force row level security;
alter table public.private_backups   force row level security;
alter table public.user_keys         force row level security;
alter table public.activities        force row level security;
alter table public.lookup_attempts   force row level security;

-- ---- profiles --------------------------------------------------------
-- Own row only. Other people's profiles are reachable exclusively through
-- get_public_profile(), which applies the visibility rules. This is what
-- prevents the user list from being enumerated.
drop policy if exists profiles_select_own on public.profiles;
create policy profiles_select_own on public.profiles
  for select to authenticated using (id = auth.uid());

drop policy if exists profiles_update_own on public.profiles;
create policy profiles_update_own on public.profiles
  for update to authenticated
  using (id = auth.uid()) with check (id = auth.uid());

-- No INSERT policy: rows come only from the signup trigger.
-- No DELETE policy: deletion cascades from auth.users.

-- ---- friendships -----------------------------------------------------
drop policy if exists friendships_select_involved on public.friendships;
create policy friendships_select_involved on public.friendships
  for select to authenticated
  using (requester_id = auth.uid() or addressee_id = auth.uid());

-- Writes go through the RPCs only, so that the addressee cannot be forged
-- and a request cannot be self-accepted.
drop policy if exists friendships_delete_involved on public.friendships;
create policy friendships_delete_involved on public.friendships
  for delete to authenticated
  using (requester_id = auth.uid() or addressee_id = auth.uid());

-- ---- cars ------------------------------------------------------------
drop policy if exists cars_select on public.cars;
create policy cars_select on public.cars
  for select to authenticated using (public.can_view(owner_id, is_shared));

drop policy if exists cars_insert_own on public.cars;
create policy cars_insert_own on public.cars
  for insert to authenticated with check (owner_id = auth.uid());

drop policy if exists cars_update_own on public.cars;
create policy cars_update_own on public.cars
  for update to authenticated
  using (owner_id = auth.uid()) with check (owner_id = auth.uid());

drop policy if exists cars_delete_own on public.cars;
create policy cars_delete_own on public.cars
  for delete to authenticated using (owner_id = auth.uid());

-- ---- car_modifications ----------------------------------------------
-- Visible exactly when the parent car is.
drop policy if exists car_mods_select on public.car_modifications;
create policy car_mods_select on public.car_modifications
  for select to authenticated
  using (exists (select 1 from public.cars c where c.id = car_id));

drop policy if exists car_mods_write_own on public.car_modifications;
create policy car_mods_write_own on public.car_modifications
  for all to authenticated
  using (owner_id = auth.uid()) with check (owner_id = auth.uid());

-- ---- events ----------------------------------------------------------
drop policy if exists events_select on public.events;
create policy events_select on public.events
  for select to authenticated using (public.can_view(owner_id, is_shared));

drop policy if exists events_insert_own on public.events;
create policy events_insert_own on public.events
  for insert to authenticated with check (owner_id = auth.uid());

drop policy if exists events_update_own on public.events;
create policy events_update_own on public.events
  for update to authenticated
  using (owner_id = auth.uid()) with check (owner_id = auth.uid());

drop policy if exists events_delete_own on public.events;
create policy events_delete_own on public.events
  for delete to authenticated using (owner_id = auth.uid());

-- ---- event_tracks ----------------------------------------------------
-- The ONLY GPS data that can ever be seen by another user, and only when
-- its parent event is explicitly shared.
drop policy if exists event_tracks_select on public.event_tracks;
create policy event_tracks_select on public.event_tracks
  for select to authenticated
  using (exists (select 1 from public.events e where e.id = event_id));

drop policy if exists event_tracks_write_own on public.event_tracks;
create policy event_tracks_write_own on public.event_tracks
  for all to authenticated
  using (owner_id = auth.uid()) with check (owner_id = auth.uid());

-- ---- trophy_unlocks --------------------------------------------------
drop policy if exists trophies_select on public.trophy_unlocks;
create policy trophies_select on public.trophy_unlocks
  for select to authenticated
  using (
    owner_id = auth.uid()
    or (
      public.shares_trophies(owner_id)
      and public.can_view(owner_id, true)
    )
  );

drop policy if exists trophies_write_own on public.trophy_unlocks;
create policy trophies_write_own on public.trophy_unlocks
  for all to authenticated
  using (owner_id = auth.uid()) with check (owner_id = auth.uid());

-- ---- private_backups -------------------------------------------------
-- Owner only, forever. Do not add a sharing policy to this table.
drop policy if exists backups_own on public.private_backups;
create policy backups_own on public.private_backups
  for all to authenticated
  using (owner_id = auth.uid()) with check (owner_id = auth.uid());

-- ---- user_keys -------------------------------------------------------
drop policy if exists user_keys_own on public.user_keys;
create policy user_keys_own on public.user_keys
  for all to authenticated
  using (owner_id = auth.uid()) with check (owner_id = auth.uid());

-- ---- activities ------------------------------------------------------
drop policy if exists activities_select on public.activities;
create policy activities_select on public.activities
  for select to authenticated
  using (public.activity_visible(actor_id, kind, subject_id, subject_key));

-- Inserts come from triggers (SECURITY DEFINER) only — no client policy.

-- ---- lookup_attempts -------------------------------------------------
-- No policy at all: RLS is on and forced, so clients cannot read or reset
-- their own rate-limit counter. Only the SECURITY DEFINER RPC touches it.


-- =====================================================================
-- 6. RPCs
-- =====================================================================

-- Exact-match username lookup, rate limited to 60/hour per user.
-- Returns at most one row. There is no prefix search and no listing, by
-- design — that is what stops the user base being scraped.
create or replace function public.find_user_by_username(p_username text)
returns table (user_id uuid, user_name text, user_display_name text)
language plpgsql
volatile
security definer
set search_path = public, pg_temp
as $$
declare
  v_actor uuid := auth.uid();
  v_norm  text := lower(trim(coalesce(p_username, '')));
  v_count int;
begin
  if v_actor is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if v_norm !~ '^[a-z0-9_]{3,20}$' then
    return;
  end if;

  insert into public.lookup_attempts as la (actor_id, window_start, attempts)
  values (v_actor, date_trunc('hour', now()), 1)
  on conflict (actor_id, window_start)
    do update set attempts = la.attempts + 1
  returning la.attempts into v_count;

  if v_count > 60 then
    raise exception 'rate_limited' using hint = 'Too many searches. Try again later.';
  end if;

  return query
    select p.id, p.username, p.display_name
    from public.profiles p
    where p.username = v_norm and p.id <> v_actor;
end;
$$;


create or replace function public.send_friend_request(p_username text)
returns text
language plpgsql
volatile
security definer
set search_path = public, pg_temp
as $$
declare
  v_actor  uuid := auth.uid();
  v_target uuid;
  v_norm   text := lower(trim(coalesce(p_username, '')));
begin
  if v_actor is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;

  select p.id into v_target from public.profiles p where p.username = v_norm;
  if v_target is null or v_target = v_actor then
    raise exception 'user_not_found';
  end if;

  -- Blocked in either direction: fail silently-ish, never reveal the block.
  if exists (
    select 1 from public.friendships f
    where f.status = 'blocked'
      and ((f.requester_id = v_actor and f.addressee_id = v_target)
        or (f.requester_id = v_target and f.addressee_id = v_actor))
  ) then
    return 'pending';
  end if;

  -- They already asked us: treat this as an accept.
  if exists (
    select 1 from public.friendships f
    where f.requester_id = v_target and f.addressee_id = v_actor and f.status = 'pending'
  ) then
    update public.friendships
      set status = 'accepted', responded_at = now()
      where requester_id = v_target and addressee_id = v_actor;
    return 'accepted';
  end if;

  insert into public.friendships (requester_id, addressee_id, status)
  values (v_actor, v_target, 'pending')
  on conflict (requester_id, addressee_id) do nothing;

  return 'pending';
end;
$$;


create or replace function public.respond_friend_request(p_requester uuid, p_accept boolean)
returns void
language plpgsql
volatile
security definer
set search_path = public, pg_temp
as $$
declare
  v_actor uuid := auth.uid();
begin
  if v_actor is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;

  -- Only the addressee may respond; a requester cannot accept their own.
  if p_accept then
    update public.friendships
      set status = 'accepted', responded_at = now()
      where requester_id = p_requester and addressee_id = v_actor and status = 'pending';
  else
    delete from public.friendships
      where requester_id = p_requester and addressee_id = v_actor and status = 'pending';
  end if;
end;
$$;


create or replace function public.block_user(p_user uuid)
returns void
language plpgsql
volatile
security definer
set search_path = public, pg_temp
as $$
declare
  v_actor uuid := auth.uid();
begin
  if v_actor is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  delete from public.friendships
    where (requester_id = v_actor and addressee_id = p_user)
       or (requester_id = p_user and addressee_id = v_actor);
  insert into public.friendships (requester_id, addressee_id, status, responded_at)
  values (v_actor, p_user, 'blocked', now());
end;
$$;


-- Feed page. Visibility is enforced by activity_visible() rather than
-- trusted from the client's p_scope, which only narrows the result.
create or replace function public.get_feed(
  p_scope    text default null,
  p_before   timestamptz default null,
  p_limit    int default 30
)
returns table (
  activity_id   uuid,
  actor_id      uuid,
  actor_name    text,
  actor_display text,
  kind          text,
  subject_id    uuid,
  subject_key   text,
  created_at    timestamptz,
  title         text,
  subtitle      text,
  distance_km   double precision,
  max_speed_kmh int,
  mod_count     int,
  photo_updated_at timestamptz
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select
    a.id,
    a.actor_id,
    p.username,
    p.display_name,
    a.kind::text,
    a.subject_id,
    a.subject_key,
    a.created_at,
    case a.kind
      when 'event_shared' then e.title
      when 'trophy_unlocked' then a.subject_key
      else c.name
    end,
    case a.kind
      when 'event_shared' then e.type
      else nullif(concat_ws(' ', c.brand, c.model), '')
    end,
    coalesce(e.distance_km, 0),
    coalesce(e.max_speed_kmh, 0),
    case when a.kind in ('car_added', 'car_shared')
      then (select count(*)::int from public.car_modifications m where m.car_id = c.id)
      else 0
    end,
    c.photo_updated_at
  from public.activities a
  join public.profiles p on p.id = a.actor_id
  left join public.events e on e.id = a.subject_id and a.kind = 'event_shared'
  left join public.cars   c on c.id = a.subject_id and a.kind in ('car_added', 'car_shared')
  where public.activity_visible(a.actor_id, a.kind, a.subject_id, a.subject_key)
    and (p_before is null or a.created_at < p_before)
    and (
      coalesce(p_scope, 'everyone') = 'everyone'
      or a.actor_id = auth.uid()
      or public.are_friends(auth.uid(), a.actor_id)
    )
  order by a.created_at desc
  limit least(coalesce(p_limit, 30), 100);
$$;


-- The caller's friends plus pending requests in both directions, with usernames attached.
--
-- Exists because `friendships` only stores two uuids — a plain client-side join against
-- `profiles` to get a username is impossible by design (profiles_select_own blocks reading
-- anyone else's row; that's what stops the user base being scraped). This SECURITY DEFINER
-- function is the one place allowed to look both up together, and only for people the caller
-- already has *some* relationship with — not an arbitrary lookup.
create or replace function public.get_friends()
returns table (
  other_id uuid,
  other_username text,
  other_display_name text,
  status text,
  direction text
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select
    other.id,
    other.username,
    other.display_name,
    f.status::text,
    case
      when f.status = 'accepted' then 'accepted'
      when f.requester_id = auth.uid() then 'outgoing'
      else 'incoming'
    end
  from public.friendships f
  join public.profiles other
    on other.id = case when f.requester_id = auth.uid() then f.addressee_id else f.requester_id end
  where (f.requester_id = auth.uid() or f.addressee_id = auth.uid())
    and f.status in ('accepted', 'pending')
  order by f.status, other.username;
$$;


-- Another user's profile, reduced to whatever they chose to expose.
create or replace function public.get_public_profile(p_user_id uuid)
returns table (
  user_id          uuid,
  user_name        text,
  display_name     text,
  city             text,
  department_codes text[],
  share_garage     boolean,
  share_trophies   boolean,
  is_friend        boolean,
  friend_state     text
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select
    p.id,
    p.username,
    case when p.share_profile or p.id = auth.uid() then p.display_name end,
    case when p.share_profile or p.id = auth.uid() then p.city end,
    case when p.share_profile or p.id = auth.uid() then p.department_codes else '{}'::text[] end,
    p.share_garage,
    p.share_trophies,
    public.are_friends(auth.uid(), p.id),
    coalesce((
      select f.status::text from public.friendships f
      where (f.requester_id = auth.uid() and f.addressee_id = p.id)
         or (f.requester_id = p.id and f.addressee_id = auth.uid())
      limit 1
    ), 'none')
  from public.profiles p
  where p.id = p_user_id
    and auth.uid() is not null
    and (
      p.id = auth.uid()
      or p.visibility = 'public'
      or (p.visibility = 'friends' and public.are_friends(auth.uid(), p.id))
    );
$$;


-- GDPR erasure. Deleting the auth user cascades through every table above.
create or replace function public.delete_my_account()
returns void
language plpgsql
volatile
security definer
set search_path = public, pg_temp, auth
as $$
declare
  v_actor uuid := auth.uid();
begin
  if v_actor is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  delete from auth.users where id = v_actor;
end;
$$;


-- =====================================================================
-- 7. GRANTS
--
-- Supabase grants broadly to anon/authenticated by default; tighten it.
-- `anon` (pre-login) must reach nothing at all — signup and login go
-- through GoTrue, not through PostgREST.
-- =====================================================================

revoke all on public.profiles, public.friendships, public.cars,
              public.car_modifications, public.events, public.event_tracks,
              public.trophy_unlocks, public.private_backups, public.user_keys,
              public.activities, public.lookup_attempts
  from anon;

revoke all on public.lookup_attempts from authenticated;
revoke all on public.activities from authenticated;
grant select on public.activities to authenticated;

grant select, insert, update, delete on
  public.cars, public.car_modifications, public.events, public.event_tracks,
  public.trophy_unlocks, public.private_backups, public.user_keys
  to authenticated;
grant select, update on public.profiles to authenticated;
grant select, delete on public.friendships to authenticated;

-- RPCs: authenticated only, never anon.
do $$
declare fn text;
begin
  foreach fn in array array[
    'public.find_user_by_username(text)',
    'public.send_friend_request(text)',
    'public.respond_friend_request(uuid, boolean)',
    'public.block_user(uuid)',
    'public.get_feed(text, timestamptz, int)',
    'public.get_friends()',
    'public.get_public_profile(uuid)',
    'public.delete_my_account()'
  ] loop
    execute format('revoke all on function %s from public, anon', fn);
    execute format('grant execute on function %s to authenticated', fn);
  end loop;
end $$;

-- Internal helpers, blocked for anonymous (pre-login) callers only.
--
-- They still need EXECUTE granted to `authenticated` — not to let it call them directly as
-- RPCs (harmless if it does; they return no more than the RLS policies already reveal), but
-- because RLS policies invoke them from inside a query running AS the `authenticated` role.
-- Revoking from `public` strips every role's default grant, `authenticated` included, unless
-- explicitly re-granted — miss that and every policy that calls one of these starts failing
-- with "permission denied for function ...", not a normal RLS denial. This bit an upsert
-- specifically: `INSERT ... ON CONFLICT DO UPDATE` (what an "upsert" compiles to) evaluates
-- the table's SELECT policy too, to check for a conflicting row, even when nothing conflicts.
revoke all on function public.are_friends(uuid, uuid) from public, anon;
revoke all on function public.can_view(uuid, boolean) from public, anon;
revoke all on function public.shares_trophies(uuid) from public, anon;
revoke all on function
  public.activity_visible(uuid, public.activity_kind, uuid, text) from public, anon;

grant execute on function public.are_friends(uuid, uuid) to authenticated;
grant execute on function public.can_view(uuid, boolean) to authenticated;
grant execute on function public.shares_trophies(uuid) to authenticated;
grant execute on function
  public.activity_visible(uuid, public.activity_kind, uuid, text) to authenticated;


-- =====================================================================
-- 8. STORAGE — car photos
--
-- Private bucket. Object key is the flat `{car_id}.jpg`, deliberately with no
-- owner-id folder: both the uploader (CloudSyncManager, which already has the
-- car row at hand) and a friend viewing a shared car (SharedContentRepository,
-- which only ever has the car id) can build the path from what they already
-- have, with no extra round trip.
--
-- Neither policy below re-derives can_view(). Like car_mods_select above,
-- they exists()-check the `cars` table and let its own cars_select RLS
-- (already can_view()-gated) transitively decide visibility — that subquery
-- runs as the calling role, so a car the caller can't see simply doesn't
-- match, with no separate visibility logic to keep in sync here.
-- =====================================================================

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('car-photos', 'car-photos', false, 2097152, array['image/jpeg'])
on conflict (id) do update
  set file_size_limit = excluded.file_size_limit,
      allowed_mime_types = excluded.allowed_mime_types;

-- Guards the uuid cast: only a name shaped exactly like `<uuid>.jpg` is ever
-- attempted, so a stray/malformed object key filters out as "no match" rather
-- than raising an "invalid input syntax for uuid" error inside the policy.
create or replace function public.car_id_from_photo_key(name text)
returns uuid
language sql
immutable
as $$
  select substring(name from '^([0-9a-fA-F-]{36})\.jpg$')::uuid;
$$;

-- objects.name (not the bare column) throughout below: `cars` has its own `name`
-- column (the car's display name), and inside the EXISTS subquery an unqualified
-- `name` resolves to that closer scope, not storage.objects' — silently feeding
-- car_id_from_photo_key() a value like "Porsche 911" instead of the object key,
-- which never matches the uuid regex and made every policy check fail closed.
drop policy if exists car_photos_select on storage.objects;
create policy car_photos_select on storage.objects
  for select to authenticated
  using (
    bucket_id = 'car-photos'
    and exists (select 1 from public.cars c where c.id = public.car_id_from_photo_key(objects.name))
  );

drop policy if exists car_photos_write_own on storage.objects;
create policy car_photos_write_own on storage.objects
  for all to authenticated
  using (
    bucket_id = 'car-photos'
    and exists (
      select 1 from public.cars c
      where c.id = public.car_id_from_photo_key(objects.name) and c.owner_id = auth.uid()
    )
  )
  with check (
    bucket_id = 'car-photos'
    and exists (
      select 1 from public.cars c
      where c.id = public.car_id_from_photo_key(objects.name) and c.owner_id = auth.uid()
    )
  );
