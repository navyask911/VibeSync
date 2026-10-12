-- Resolve canonical chat peers before writing encrypted messages.
-- Older app builds occasionally sent a phone number in p_recipient_profile_id.
-- The RPC repairs that input from the canonical match pair or the zero-knowledge phone index.
begin;

create or replace function public.resolve_profile_id_by_phone_hash(p_hash text)
returns text
language plpgsql
security definer
set search_path = pg_catalog, public, extensions
as $$
declare
  current_profile_id text := public.vibesync_current_profile_id();
  result_profile_id text;
begin
  if current_profile_id is null then
    raise exception 'vibesync session required';
  end if;
  if p_hash is null or p_hash !~ '^[0-9a-fA-F]{64}$' then
    return null;
  end if;

  select p.id::text
    into result_profile_id
  from public.phone_sync ps
  join public.profiles p on p.id::text = ps.user_id
  where ps.phone_hash = decode(p_hash, 'hex')
    and coalesce(p.is_deleted, false) = false
    and coalesce(p.account_status, 'ACTIVE') = 'ACTIVE'
    and p.id::text <> current_profile_id
  limit 1;

  return result_profile_id;
end;
$$;

revoke all on function public.resolve_profile_id_by_phone_hash(text) from public, anon, authenticated;
grant execute on function public.resolve_profile_id_by_phone_hash(text) to anon;

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
  recipient_phone_digits text;
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

  recipient_phone_digits := regexp_replace(p_recipient_profile_id, '[^0-9]', '', 'g');
  recipient_looks_like_phone :=
    length(recipient_phone_digits) between 10 and 15
    and p_recipient_profile_id ~ '^[0-9+(). -]+$';

  -- Preserve an existing canonical profile ID when one was supplied.
  if not recipient_looks_like_phone then
    select p.id::text
      into resolved_recipient_profile_id
    from public.profiles p
    where p.id::text = p_recipient_profile_id
      and coalesce(p.is_deleted, false) = false
      and coalesce(p.account_status, 'ACTIVE') = 'ACTIVE'
    limit 1;
  end if;

  -- A composite match is authoritative for legacy callers passing a phone or stale peer ID,
  -- but only when the pair actually contains the authenticated VibeSync sender profile.
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

  -- Last-resort compatibility for older clients: hash the supplied phone in SQL and
  -- resolve through phone_sync. Only the canonical profile ID is written to chat_messages.
  if resolved_recipient_profile_id is null and recipient_looks_like_phone then
    if length(recipient_phone_digits) >= 10 then
      select p.id::text
        into resolved_recipient_profile_id
      from public.phone_sync ps
      join public.profiles p on p.id::text = ps.user_id
      where ps.phone_hash = extensions.digest(
          convert_to(right(recipient_phone_digits, 10), 'utf8'),
          'sha256'
        )
        and coalesce(p.is_deleted, false) = false
        and coalesce(p.account_status, 'ACTIVE') = 'ACTIVE'
        and p.id::text <> current_profile_id
      limit 1;
    end if;
  end if;

  if resolved_recipient_profile_id is null then
    raise exception 'recipient profile not available';
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

revoke all on function public.send_encrypted_message(text, text, text, text, text, text, integer) from public;
revoke all on function public.send_encrypted_message(text, text, text, text, text, text, integer) from anon, authenticated;
grant execute on function public.send_encrypted_message(text, text, text, text, text, text, integer) to anon;

notify pgrst, 'reload schema';
commit;
