-- VibeSync Security Core v2026-10-07
-- Safe baseline: no service-role credentials are stored here.
begin;

-- --------------------------------------------------------------------------
-- Profiles: authenticated identity binding + protected server fields
-- --------------------------------------------------------------------------
alter table public.profiles add column if not exists auth_user_id uuid references auth.users(id) on delete cascade;
create unique index if not exists idx_profiles_auth_user_id on public.profiles(auth_user_id) where auth_user_id is not null;
alter table public.profiles enable row level security;

drop policy if exists "Allow public app access to profiles" on public.profiles;
drop policy if exists "Allow public full access to profiles" on public.profiles;
drop policy if exists "profiles_authenticated_select" on public.profiles;
drop policy if exists "profiles_authenticated_insert" on public.profiles;
drop policy if exists "profiles_authenticated_update" on public.profiles;

create policy "profiles_authenticated_select" on public.profiles
for select to authenticated
using (coalesce(is_deleted,false) = false);

create policy "profiles_authenticated_insert" on public.profiles
for insert to authenticated
with check (auth_user_id = (select auth.uid()));

create policy "profiles_authenticated_update" on public.profiles
for update to authenticated
using (auth_user_id = (select auth.uid()) and coalesce(is_deleted,false) = false)
with check (auth_user_id = (select auth.uid()));

revoke all on public.profiles from anon;
revoke all on public.profiles from public;
grant select (id,name,bio,age,gender,city,country,country_flag,place,occupation,qualification,marital_status,avatar_emoji,avatar_url,account_status,is_verified,is_deleted,updated_at,public_identity_key) on public.profiles to authenticated;
grant insert on public.profiles to authenticated;
grant update (name,bio,age,gender,city,country,country_flag,place,occupation,qualification,marital_status,avatar_emoji,avatar_url,public_identity_key,updated_at) on public.profiles to authenticated;

-- Prevent client-side privilege escalation for protected profile fields.
create or replace function public.protect_profile_fields()
returns trigger
language plpgsql
security invoker
set search_path = pg_catalog, public
as $$
begin
  if auth.role() = 'authenticated' then
    if tg_op = 'INSERT' then
      new.auth_user_id := auth.uid();
      new.is_verified := false;
      new.is_deleted := false;
      new.account_status := 'ACTIVE';
    else
      new.auth_user_id := old.auth_user_id;
      new.is_verified := old.is_verified;
      new.is_deleted := old.is_deleted;
      new.account_status := old.account_status;
    end if;
  end if;
  return new;
end;
$$;
drop trigger if exists trg_profiles_protected_fields on public.profiles;
create trigger trg_profiles_protected_fields before insert or update on public.profiles
for each row execute function public.protect_profile_fields();

-- --------------------------------------------------------------------------
-- Registered accounts: compatibility table, Auth remains authoritative
-- --------------------------------------------------------------------------
alter table public.registered_accounts add column if not exists auth_user_id uuid references auth.users(id) on delete cascade;
create unique index if not exists idx_registered_accounts_auth_user_id on public.registered_accounts(auth_user_id) where auth_user_id is not null;
alter table public.registered_accounts enable row level security;
drop policy if exists "Allow public full access to registered_accounts" on public.registered_accounts;
drop policy if exists "registered_accounts_select_own" on public.registered_accounts;
drop policy if exists "registered_accounts_insert_own" on public.registered_accounts;
drop policy if exists "registered_accounts_update_own" on public.registered_accounts;
create policy "registered_accounts_select_own" on public.registered_accounts for select to authenticated using (auth_user_id = (select auth.uid()));
create policy "registered_accounts_insert_own" on public.registered_accounts for insert to authenticated with check (auth_user_id = (select auth.uid()));
create policy "registered_accounts_update_own" on public.registered_accounts for update to authenticated using (auth_user_id = (select auth.uid())) with check (auth_user_id = (select auth.uid()));
revoke all on public.registered_accounts from anon;
revoke all on public.registered_accounts from public;
grant select,insert,update on public.registered_accounts to authenticated;

create or replace function public.protect_registered_account_fields()
returns trigger language plpgsql security invoker set search_path = pg_catalog, public as $$
begin
  if auth.role() = 'authenticated' then
    if tg_op = 'INSERT' then
      new.auth_user_id := auth.uid();
      new.is_verified := false;
      new.account_status := 'ACTIVE';
    else
      new.auth_user_id := old.auth_user_id;
      new.is_verified := old.is_verified;
      new.account_status := old.account_status;
    end if;
  end if;
  return new;
end; $$;
drop trigger if exists trg_registered_account_protected_fields on public.registered_accounts;
create trigger trg_registered_account_protected_fields before insert or update on public.registered_accounts
for each row execute function public.protect_registered_account_fields();

-- --------------------------------------------------------------------------
-- Device tokens: FCM registry owned by Supabase Auth identity
-- --------------------------------------------------------------------------
create table if not exists public.device_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  fcm_token text not null check (length(fcm_token) between 20 and 4096),
  platform text not null default 'android' check (platform = 'android'),
  updated_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  unique(user_id, fcm_token)
);
create index if not exists idx_device_tokens_user_updated on public.device_tokens(user_id, updated_at desc);
alter table public.device_tokens enable row level security;
drop policy if exists "device_tokens_select_own" on public.device_tokens;
drop policy if exists "device_tokens_insert_own" on public.device_tokens;
drop policy if exists "device_tokens_update_own" on public.device_tokens;
drop policy if exists "device_tokens_delete_own" on public.device_tokens;
create policy "device_tokens_select_own" on public.device_tokens for select to authenticated using (user_id = (select auth.uid()));
create policy "device_tokens_insert_own" on public.device_tokens for insert to authenticated with check (user_id = (select auth.uid()));
create policy "device_tokens_update_own" on public.device_tokens for update to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy "device_tokens_delete_own" on public.device_tokens for delete to authenticated using (user_id = (select auth.uid()));
revoke all on public.device_tokens from anon;
revoke all on public.device_tokens from public;
grant select,insert,update,delete on public.device_tokens to authenticated;

-- --------------------------------------------------------------------------
-- Private contact index
-- --------------------------------------------------------------------------
alter table public.phone_sync add column if not exists auth_user_id uuid references auth.users(id) on delete cascade;
create index if not exists idx_phone_sync_auth_user on public.phone_sync(auth_user_id);
alter table public.phone_sync enable row level security;
drop policy if exists "phone_sync_all" on public.phone_sync;
drop policy if exists "phone_sync_insert_own" on public.phone_sync;
drop policy if exists "phone_sync_delete_own" on public.phone_sync;
drop policy if exists "phone_sync_no_client_select" on public.phone_sync;
create policy "phone_sync_insert_own" on public.phone_sync for insert to authenticated with check (auth_user_id = (select auth.uid()));
create policy "phone_sync_delete_own" on public.phone_sync for delete to authenticated using (auth_user_id = (select auth.uid()));
revoke all on public.phone_sync from anon;
revoke all on public.phone_sync from public;
revoke all on public.phone_sync from authenticated;
grant insert,delete on public.phone_sync to authenticated;

-- Match RPC: authenticated only, hardened search_path, minimal return surface.
create or replace function public.match_contacts(hashes text[])
returns table (id text, name text, profile_photo_url text, avatar_emoji text)
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
  if (select auth.uid()) is null then raise exception 'authentication required'; end if;
  if hashes is null or cardinality(hashes) = 0 or cardinality(hashes) > 50 then raise exception 'invalid lookup batch'; end if;
  return query
  select p.id::text, p.name::text, coalesce(p.avatar_url,'')::text, coalesce(p.avatar_emoji,'✨')::text
  from public.phone_sync ps
  join public.profiles p on p.id::text = ps.user_id
  where ps.phone_hash = any (
    select decode(h,'hex') from unnest(hashes) as h
    where h ~ '^[0-9a-fA-F]{64}$'
  )
  and coalesce(p.is_deleted,false) = false
  limit 50;
end;
$$;
revoke all on function public.match_contacts(text[]) from public;
revoke all on function public.match_contacts(text[]) from anon;
revoke all on function public.match_contacts(text[]) from authenticated;
grant execute on function public.match_contacts(text[]) to authenticated;

-- --------------------------------------------------------------------------
-- Chat transport rows: encrypted payload only + authenticated identities
-- --------------------------------------------------------------------------
alter table public.chat_messages add column if not exists message_id text;
alter table public.chat_messages add column if not exists match_id text;
alter table public.chat_messages add column if not exists sender_id text;
alter table public.chat_messages add column if not exists receiver_id text;
alter table public.chat_messages add column if not exists text text;
alter table public.chat_messages add column if not exists message text;
alter table public.chat_messages add column if not exists timestamp bigint;
alter table public.chat_messages add column if not exists is_delivered boolean not null default false;
alter table public.chat_messages add column if not exists is_encrypted boolean not null default true;
alter table public.chat_messages add column if not exists media_url text default '';
alter table public.chat_messages add column if not exists media_type text default 'TEXT';
alter table public.chat_messages add column if not exists voice_duration_seconds integer default 0;
alter table public.chat_messages add column if not exists sender_user_id uuid references auth.users(id) on delete cascade;
alter table public.chat_messages add column if not exists receiver_user_id uuid references auth.users(id) on delete cascade;
alter table public.chat_messages add column if not exists ciphertext text;
alter table public.chat_messages add column if not exists idempotency_key text;
alter table public.chat_messages add column if not exists dispatch_status text not null default 'PENDING';
alter table public.chat_messages add column if not exists dispatch_attempts integer not null default 0;
alter table public.chat_messages add column if not exists delivered_at timestamptz;
alter table public.chat_messages add column if not exists read_at timestamptz;
alter table public.chat_messages add column if not exists expires_at timestamptz not null default (now() + interval '24 hours');
create unique index if not exists idx_chat_messages_sender_idempotency on public.chat_messages(sender_user_id,idempotency_key) where idempotency_key is not null;
create index if not exists idx_chat_messages_receiver_pending on public.chat_messages(receiver_user_id,dispatch_status,expires_at);
create index if not exists idx_chat_messages_sender_status on public.chat_messages(sender_user_id,dispatch_status,expires_at);
alter table public.chat_messages enable row level security;
drop policy if exists "Allow chat read and write" on public.chat_messages;
drop policy if exists "chat_select_participant" on public.chat_messages;
drop policy if exists "chat_insert_sender" on public.chat_messages;
drop policy if exists "chat_update_receiver" on public.chat_messages;
drop policy if exists "chat_delete_participant" on public.chat_messages;
create policy "chat_select_participant" on public.chat_messages for select to authenticated using (sender_user_id = (select auth.uid()) or receiver_user_id = (select auth.uid()));
create policy "chat_insert_sender" on public.chat_messages for insert to authenticated with check (sender_user_id = (select auth.uid()) and receiver_user_id is not null and is_encrypted = true and coalesce(ciphertext,'') <> '');
create policy "chat_delete_participant" on public.chat_messages for delete to authenticated using (sender_user_id = (select auth.uid()) or receiver_user_id = (select auth.uid()));
revoke all on public.chat_messages from anon;
revoke all on public.chat_messages from public;
grant select on public.chat_messages to authenticated;
grant insert on public.chat_messages to authenticated;
grant delete on public.chat_messages to authenticated;

-- Never persist a legacy plaintext field when the client supplies ciphertext.
create or replace function public.normalize_chat_ciphertext()
returns trigger language plpgsql security invoker set search_path = pg_catalog, public as $$
begin
  if auth.role() = 'authenticated' then
    if not new.is_encrypted then raise exception 'encrypted messages required'; end if;
    if coalesce(new.ciphertext,'') = '' and coalesce(new.text,'') <> '' then new.ciphertext := new.text; end if;
    new.text := null;
    new.message := null;
    new.sender_phone := null;
    new.receiver_phone := null;
    if new.sender_user_id is null then new.sender_user_id := auth.uid(); end if;
    if new.timestamp is null then new.timestamp := (extract(epoch from now())*1000)::bigint; end if;
  end if;
  return new;
end; $$;
drop trigger if exists trg_chat_ciphertext on public.chat_messages;
create trigger trg_chat_ciphertext before insert on public.chat_messages for each row execute function public.normalize_chat_ciphertext();

-- --------------------------------------------------------------------------
-- Deprecated messages table: inaccessible to end-user clients.
-- --------------------------------------------------------------------------
alter table public.messages enable row level security;
drop policy if exists "Allow public full access to messages" on public.messages;
revoke all on public.messages from anon;
revoke all on public.messages from authenticated;
revoke all on public.messages from public;

-- --------------------------------------------------------------------------
-- Cleanup: transient delivery records can be purged without touching Room.
-- --------------------------------------------------------------------------
create or replace function public.purge_delivered_messages()
returns integer
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare deleted_count integer;
begin
  delete from public.chat_messages
  where expires_at < now()
     or (is_delivered = true and delivered_at < now() - interval '2 hours' and is_read = true);
  get diagnostics deleted_count = row_count;
  return deleted_count;
end;
$$;
revoke all on function public.purge_delivered_messages() from public;
revoke all on function public.purge_delivered_messages() from anon;
revoke all on function public.purge_delivered_messages() from authenticated;

-- Schedule cleanup every 15 minutes. pg_cron is installed on the live project.
select cron.unschedule(jobid) from cron.job where jobname = 'vibesync-purge-delivered-messages';
select cron.schedule('vibesync-purge-delivered-messages','*/15 * * * *','select public.purge_delivered_messages();');

commit;