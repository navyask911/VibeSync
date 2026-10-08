-- VibeSync custom session access: Truecaller-authenticated, Supabase-Auth-free client sessions.
-- The Android client continues to use the publishable key (anon role) for Data API access.
-- Authorization is carried by x-vibesync-session and validated server-side.

create table if not exists public.vibesync_sessions (
  session_hash text primary key,
  profile_id text not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  last_seen_at timestamptz not null default now()
);

create index if not exists idx_vibesync_sessions_profile_id
  on public.vibesync_sessions(profile_id);

create index if not exists idx_vibesync_sessions_expires_at
  on public.vibesync_sessions(expires_at);

alter table public.vibesync_sessions enable row level security;
revoke all on public.vibesync_sessions from anon, authenticated, public;

create or replace function public.vibesync_current_profile_id()
returns text
language sql
stable
security definer
set search_path = pg_catalog, public, extensions
as $$
  select s.profile_id
  from public.vibesync_sessions s
  where s.session_hash = encode(
    extensions.digest(
      convert_to(
        coalesce(
          (current_setting('request.headers', true)::jsonb)->>'x-vibesync-session',
          ''
        ),
        'utf8'
      ),
      'sha256'
    ),
    'hex'
  )
    and s.expires_at > now()
  limit 1;
$$;

revoke all on function public.vibesync_current_profile_id() from public;
grant execute on function public.vibesync_current_profile_id() to anon, authenticated;

-- Profiles are readable to a valid VibeSync session; all writes go through the
-- session-bound profile RPC below so clients never submit identity ownership fields.
drop policy if exists "profiles_vibesync_select" on public.profiles;
create policy "profiles_vibesync_select"
  on public.profiles
  for select
  to anon
  using (
    public.vibesync_current_profile_id() is not null
    and coalesce(is_deleted,false) = false
    and coalesce(account_status,'ACTIVE') = 'ACTIVE'
  );
grant select on public.profiles to anon;

create or replace function public.upsert_my_profile(payload jsonb)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  current_profile_id text := public.vibesync_current_profile_id();
  changed boolean;
begin
  if current_profile_id is null then
    raise exception 'vibesync session required';
  end if;

  update public.profiles
  set
    name = left(coalesce(nullif(payload->>'name',''), name), 60),
    bio = left(coalesce(nullif(payload->>'bio',''), bio), 160),
    age = greatest(13, least(coalesce((payload->>'age')::integer, age), 120)),
    gender = coalesce(nullif(payload->>'gender',''), gender),
    city = coalesce(nullif(payload->>'city',''), city),
    country = coalesce(nullif(payload->>'country',''), country),
    country_flag = coalesce(nullif(payload->>'country_flag',''), country_flag),
    place = coalesce(payload->>'place', place),
    occupation = coalesce(nullif(payload->>'occupation',''), occupation),
    qualification = coalesce(nullif(payload->>'qualification',''), qualification),
    marital_status = coalesce(nullif(payload->>'marital_status',''), marital_status),
    avatar_emoji = coalesce(nullif(payload->>'avatar_emoji',''), avatar_emoji),
    avatar_url = coalesce(payload->>'avatar_url', avatar_url),
    public_identity_key = coalesce(nullif(payload->>'public_identity_key',''), public_identity_key),
    updated_at = (extract(epoch from now()) * 1000)::bigint
  where id = current_profile_id
    and coalesce(is_deleted,false) = false;

  get diagnostics changed = row_count > 0;
  return changed;
end;
$$;

revoke all on function public.upsert_my_profile(jsonb) from public;
grant execute on function public.upsert_my_profile(jsonb) to anon;

-- Private zero-knowledge contact matching now authenticates through the
-- VibeSync session header rather than Supabase Auth JWT claims.
drop function if exists public.match_contacts(text[]);
create or replace function public.match_contacts(hashes text[])
returns table(id text, name text, profile_photo_url text, avatar_emoji text)
language plpgsql
security definer
set search_path = pg_catalog, public, extensions
as $$
declare
  current_profile_id text := public.vibesync_current_profile_id();
begin
  if current_profile_id is null then
    raise exception 'vibesync session required';
  end if;
  if hashes is null or cardinality(hashes) = 0 or cardinality(hashes) > 50 then
    raise exception 'invalid lookup batch';
  end if;

  return query
  select
    p.id::text,
    coalesce(p.name, 'VibeSync Friend')::text,
    coalesce(p.avatar_url, '')::text,
    coalesce(p.avatar_emoji, '✨')::text
  from public.phone_sync ps
  join public.profiles p on p.id::text = ps.user_id
  where ps.phone_hash = any(
    select decode(h,'hex')
    from unnest(hashes) as h
    where h ~ '^[0-9a-fA-F]{64}$'
  )
    and coalesce(p.is_deleted,false) = false
    and coalesce(p.account_status,'ACTIVE') = 'ACTIVE'
    and p.id <> current_profile_id
  limit 50;
end;
$$;

revoke all on function public.match_contacts(text[]) from public;
grant execute on function public.match_contacts(text[]) to anon;

-- Session-bound encrypted chat writes. The legacy UUID auth_user_id columns
-- remain nullable for historical records but are no longer required by VibeSync.
drop function if exists public.send_encrypted_message(text,text,text,text,text,text,integer);
create or replace function public.send_encrypted_message(
  p_message_id text,
  p_match_id text,
  p_recipient_profile_id text,
  p_ciphertext text,
  p_media_type text default 'TEXT',
  p_media_url text default '',
  p_voice_duration_seconds integer default 0
)
returns text
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  current_profile_id text := public.vibesync_current_profile_id();
  result_message_id text;
begin
  if current_profile_id is null then raise exception 'vibesync session required'; end if;
  if coalesce(p_message_id,'') = '' or coalesce(p_match_id,'') = '' then
    raise exception 'message and match identifiers are required';
  end if;
  if coalesce(p_ciphertext,'') = '' or left(p_ciphertext,9) <> 'TINK_ENC:' then
    raise exception 'Tink ciphertext required';
  end if;
  if coalesce(p_recipient_profile_id,'') = '' then
    raise exception 'recipient profile is required';
  end if;
  if p_recipient_profile_id = current_profile_id then
    raise exception 'self messaging is not allowed';
  end if;
  if not exists (
    select 1 from public.profiles p
    where p.id = p_recipient_profile_id
      and coalesce(p.is_deleted,false) = false
      and coalesce(p.account_status,'ACTIVE') = 'ACTIVE'
  ) then
    raise exception 'recipient profile not available';
  end if;

  insert into public.chat_messages(
    message_id, match_id, sender_user_id, receiver_user_id,
    sender_id, receiver_id, ciphertext, is_encrypted, is_delivered,
    is_read, media_type, media_url, voice_duration_seconds, timestamp,
    idempotency_key, dispatch_status
  )
  values(
    p_message_id, p_match_id, null, null,
    current_profile_id, p_recipient_profile_id, p_ciphertext, true, false,
    false, coalesce(nullif(p_media_type,''),'TEXT'), coalesce(p_media_url,''),
    greatest(coalesce(p_voice_duration_seconds,0),0),
    (extract(epoch from now())*1000)::bigint, p_message_id, 'PENDING'
  )
  on conflict(message_id) do update
    set ciphertext = excluded.ciphertext,
        media_type = excluded.media_type,
        media_url = excluded.media_url,
        voice_duration_seconds = excluded.voice_duration_seconds
  returning message_id into result_message_id;

  return coalesce(result_message_id, p_message_id);
end;
$$;

revoke all on function public.send_encrypted_message(text,text,text,text,text,text,integer) from public;
grant execute on function public.send_encrypted_message(text,text,text,text,text,text,integer) to anon;

drop function if exists public.ack_message_delivery(text);
create or replace function public.ack_message_delivery(p_message_id text)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  current_profile_id text := public.vibesync_current_profile_id();
  updated_count integer;
begin
  if current_profile_id is null then raise exception 'vibesync session required'; end if;
  update public.chat_messages
  set is_delivered=true, delivered_at=coalesce(delivered_at,now()), dispatch_status='DELIVERED'
  where (message_id=p_message_id or id::text=p_message_id)
    and receiver_id=current_profile_id;
  get diagnostics updated_count=row_count;
  return updated_count > 0;
end;
$$;

revoke all on function public.ack_message_delivery(text) from public;
grant execute on function public.ack_message_delivery(text) to anon;

drop function if exists public.ack_message_read(text);
create or replace function public.ack_message_read(p_message_id text)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  current_profile_id text := public.vibesync_current_profile_id();
  updated_count integer;
begin
  if current_profile_id is null then raise exception 'vibesync session required'; end if;
  update public.chat_messages
  set is_read=true, read_at=coalesce(read_at,now())
  where (message_id=p_message_id or id::text=p_message_id)
    and receiver_id=current_profile_id;
  get diagnostics updated_count=row_count;
  return updated_count > 0;
end;
$$;

revoke all on function public.ack_message_read(text) from public;
grant execute on function public.ack_message_read(text) to anon;

-- Direct chat reads are row-scoped to the current VibeSync profile.
drop policy if exists "chat_select_vibesync_session" on public.chat_messages;
create policy "chat_select_vibesync_session"
  on public.chat_messages
  for select
  to anon
  using (
    public.vibesync_current_profile_id() is not null
    and (
      sender_id = public.vibesync_current_profile_id()
      or receiver_id = public.vibesync_current_profile_id()
    )
  );
grant select on public.chat_messages to anon;

-- FCM device registry uses the opaque VibeSync profile ID rather than auth.users UUID.
alter table public.device_tokens
  add column if not exists profile_id text;

create index if not exists idx_device_tokens_profile_id
  on public.device_tokens(profile_id);

create or replace function public.register_device_token(
  p_fcm_token text,
  p_platform text default 'android'
)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  current_profile_id text := public.vibesync_current_profile_id();
begin
  if current_profile_id is null then raise exception 'vibesync session required'; end if;
  if p_fcm_token is null or length(trim(p_fcm_token)) < 20 then
    raise exception 'invalid fcm token';
  end if;

  update public.device_tokens
  set
    profile_id = current_profile_id,
    fcm_token = trim(p_fcm_token),
    platform = 'android',
    updated_at = now()
  where profile_id = current_profile_id and fcm_token = trim(p_fcm_token);

  if not found then
    insert into public.device_tokens(profile_id,fcm_token,platform)
    values(current_profile_id,trim(p_fcm_token),'android');
  end if;
  return true;
end;
$$;

revoke all on function public.register_device_token(text,text) from public;
grant execute on function public.register_device_token(text,text) to anon;

-- The legacy UUID owner policies remain for historical authenticated records,
-- but new VibeSync client calls exclusively use the anon/session path above.
