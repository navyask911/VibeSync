-- Complete the contact-discovery cutover. Message sends accept canonical profile IDs only;
-- contact numbers are resolved before send through match_contacts_by_phone_numbers(text[]).

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
set search_path = pg_catalog, public, extensions
as $$
declare
  current_profile_id text := public.vibesync_current_profile_id();
  resolved_recipient_profile_id text;
  recipient_digits text;
  recipient_looks_like_phone boolean := false;
  match_parts text[];
  match_peer_candidate text;
  result_message_id text;
begin
  if current_profile_id is null then
    raise exception 'vibesync session required';
  end if;
  if coalesce(p_message_id, '') = '' or coalesce(p_match_id, '') = '' then
    raise exception 'message and match identifiers are required';
  end if;
  if coalesce(p_ciphertext, '') = '' or left(p_ciphertext, 9) <> 'TINK_ENC:' then
    raise exception 'Tink ciphertext required';
  end if;
  if coalesce(p_recipient_profile_id, '') = '' then
    raise exception 'recipient profile is required';
  end if;

  recipient_digits := regexp_replace(p_recipient_profile_id, '[^0-9]', '', 'g');
  recipient_looks_like_phone :=
    length(recipient_digits) between 10 and 15
    and p_recipient_profile_id ~ '^[0-9+(). -]+$';

  -- A provided recipient must be a canonical, active profile ID, never a phone-derived token.
  if not recipient_looks_like_phone then
    select p.id::text
      into resolved_recipient_profile_id
    from public.profiles p
    where p.id::text = p_recipient_profile_id
      and coalesce(p.is_deleted, false) = false
      and coalesce(p.account_status, 'ACTIVE') = 'ACTIVE'
    limit 1;
  end if;

  -- Compatibility for stale callers is limited to a canonical participant pair in match_id.
  -- The pair must contain the authenticated sender; phone numbers are never parsed or hashed here.
  if resolved_recipient_profile_id is null then
    match_parts := regexp_match(
      coalesce(p_match_id, ''),
      '^(?:match_)?([0-9a-fA-F]{16})_([0-9a-fA-F]{16})$'
    );
    match_peer_candidate := null;
    if match_parts is not null then
      if lower(current_profile_id) = lower(match_parts[1]) then
        match_peer_candidate := match_parts[2];
      elsif lower(current_profile_id) = lower(match_parts[2]) then
        match_peer_candidate := match_parts[1];
      end if;
    end if;

    if match_peer_candidate is not null then
      select p.id::text
        into resolved_recipient_profile_id
      from public.profiles p
      where lower(p.id::text) = lower(match_peer_candidate)
        and coalesce(p.is_deleted, false) = false
        and coalesce(p.account_status, 'ACTIVE') = 'ACTIVE'
      limit 1;
    end if;
  end if;

  if resolved_recipient_profile_id is null then
    raise exception 'canonical recipient profile not available; resolve phone contacts through the E.164 RPC first';
  end if;
  if lower(resolved_recipient_profile_id) = lower(current_profile_id) then
    raise exception 'self messaging is not allowed';
  end if;

  insert into public.chat_messages(
    message_id, match_id, sender_user_id, receiver_user_id,
    sender_id, receiver_id, ciphertext, is_encrypted, is_delivered,
    is_read, media_type, media_url, voice_duration_seconds, timestamp,
    idempotency_key, dispatch_status
  )
  values(
    p_message_id, p_match_id, null, null,
    current_profile_id, resolved_recipient_profile_id, p_ciphertext, true, false,
    false, coalesce(nullif(p_media_type, ''), 'TEXT'), coalesce(p_media_url, ''),
    greatest(coalesce(p_voice_duration_seconds, 0), 0),
    (extract(epoch from now()) * 1000)::bigint, p_message_id, 'PENDING'
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

-- Keep profile protection intact without maintaining the retired phone_hash field.
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
    new.phone_number := null;
    new.phone_number_e164 := null;
    new.clean_phone := null;
  end if;
  return new;
end;
$$;

-- Remove the old blind/hash matching and public-key fallback RPCs.
drop function if exists public.match_contacts(text[]);
drop function if exists public.resolve_profile_id_by_phone_hash(text);
drop function if exists public.get_public_identity_key_by_phone_hash(text);

-- Remove legacy hashed phone storage and its ownership trigger functions.
drop table if exists public.phone_sync;
drop function if exists public.protect_phone_sync_owner();
drop function if exists public.set_phone_sync_owner();

alter table public.profiles drop column if exists phone_hash;
