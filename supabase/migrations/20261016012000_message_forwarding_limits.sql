-- Batch-forward encrypted direct and group messages atomically.
-- Forward metadata controls picker limits; media object paths remain unchanged and are never re-uploaded.
begin;

alter table public.chat_messages
  add column if not exists forward_count integer not null default 0,
  add column if not exists is_forwarded boolean not null default false;

alter table public.chat_group_messages
  add column if not exists forward_count integer not null default 0,
  add column if not exists is_forwarded boolean not null default false,
  add column if not exists media_url text not null default '',
  add column if not exists voice_duration_seconds integer not null default 0;

-- Group chat originally allowed TEXT only. Forwarded media references continue to point at the
-- existing object/encrypted payload; no media bytes are copied by the forwarding operation.
do $constraint_cleanup$
declare
  item record;
begin
  for item in
    select conname
    from pg_constraint
    where conrelid = 'public.chat_group_messages'::regclass
      and contype = 'c'
      and pg_get_constraintdef(oid) ilike '%media_type%'
  loop
    execute format('alter table public.chat_group_messages drop constraint %I', item.conname);
  end loop;
end;
$constraint_cleanup$;

alter table public.chat_group_messages
  add constraint chat_group_messages_media_type_check
  check (media_type in ('TEXT','IMAGE','PHOTO','VOICE','AUDIO','VIDEO','DOCUMENT','FILE'));

-- Writes are performed with caller privileges; the existing participant policies remain in force.
grant insert (
  message_id, match_id, sender_id, receiver_id, ciphertext, is_encrypted,
  is_delivered, is_read, media_type, media_url, voice_duration_seconds,
  timestamp, idempotency_key, forward_count, is_forwarded
) on public.chat_messages to authenticated;

grant insert (
  message_id, group_id, sender_profile_id, sender_name, media_type, media_url,
  voice_duration_seconds, forward_count, is_forwarded
) on public.chat_group_messages to authenticated;

grant insert (group_id, message_id, recipient_profile_id, ciphertext)
  on public.chat_group_message_envelopes to authenticated;

create or replace function public.vibesync_forward_message(
  p_source_message_id text,
  p_target_chat_ids text[],
  p_forward_payloads jsonb
)
returns jsonb
language plpgsql
security invoker
set search_path = public, pg_temp
as $function$
declare
  v_actor text := public.vibesync_current_profile_id();
  v_source_forward_count integer := 0;
  v_source_is_forwarded boolean := false;
  v_source_media_type text := 'TEXT';
  v_source_media_url text := '';
  v_source_voice_seconds integer := 0;
  v_source_group_id uuid;
  v_source_found boolean := false;
  v_source_depth integer := 0;
  v_new_forward_count integer := 1;
  v_target_count integer := coalesce(array_length(p_target_chat_ids, 1), 0);
  v_group_target_count integer := 0;
  v_item jsonb;
  v_envelope jsonb;
  v_target_type text;
  v_target_chat_id text;
  v_target_message_id text;
  v_recipient text;
  v_ciphertext text;
  v_sender_name text;
  v_expected_match_id text;
  v_envelopes jsonb;
  v_member_count integer;
  v_seen_recipient_count integer;
  v_seen_targets text[] := array[]::text[];
  v_seen_recipients text[];
begin
  if not public.vibesync_jwt_session_active() or coalesce(v_actor, '') = '' then
    raise exception 'verified_session_required' using errcode = '42501';
  end if;
  if coalesce(trim(p_source_message_id), '') = '' then
    raise exception 'source_message_id_required' using errcode = '22023';
  end if;
  if v_target_count < 1 or v_target_count > 5 then
    raise exception 'forward_target_limit_exceeded' using errcode = '22023';
  end if;
  if cardinality(p_target_chat_ids) <>
     (select count(distinct target_id) from unnest(p_target_chat_ids) as targets(target_id)) then
    raise exception 'duplicate_forward_targets' using errcode = '22023';
  end if;
  if coalesce(jsonb_typeof(p_forward_payloads), '') <> 'array'
     or jsonb_array_length(p_forward_payloads) <> v_target_count then
    raise exception 'forward_payload_target_count_mismatch' using errcode = '22023';
  end if;

  -- Only participant-visible direct messages or messages in a group the actor currently belongs to.
  select coalesce(m.forward_count, 0), coalesce(m.is_forwarded, false),
         coalesce(m.media_type, 'TEXT'), coalesce(m.media_url, ''),
         coalesce(m.voice_duration_seconds, 0)
    into v_source_forward_count, v_source_is_forwarded, v_source_media_type,
         v_source_media_url, v_source_voice_seconds
  from public.chat_messages m
  where m.message_id = p_source_message_id
    and (m.sender_id = v_actor or m.receiver_id = v_actor)
  limit 1;
  v_source_found := found;

  if not v_source_found and p_source_message_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    select coalesce(gm.forward_count, 0), coalesce(gm.is_forwarded, false),
           coalesce(gm.media_type, 'TEXT'), coalesce(gm.media_url, ''),
           coalesce(gm.voice_duration_seconds, 0), gm.group_id
      into v_source_forward_count, v_source_is_forwarded, v_source_media_type,
           v_source_media_url, v_source_voice_seconds, v_source_group_id
    from public.chat_group_messages gm
    where gm.message_id = p_source_message_id::uuid
      and public.vibesync_chat_group_member_role(gm.group_id, v_actor) is not null
    limit 1;
    v_source_found := found;
  end if;

  if not v_source_found then
    raise exception 'source_message_not_found_or_not_visible' using errcode = '42501';
  end if;
  if coalesce(v_source_media_type, '') in ('REACTION', 'MESSAGE_DELETE', 'MESSAGE_EDIT', 'DISAPPEARING_TIMER') then
    raise exception 'control_messages_cannot_be_forwarded' using errcode = '22023';
  end if;

  v_source_depth := greatest(coalesce(v_source_forward_count, 0),
                             case when v_source_is_forwarded then 1 else 0 end);
  v_new_forward_count := v_source_depth + 1;
  if v_source_depth >= 5 and v_target_count <> 1 then
    raise exception 'frequently_forwarded_message_single_target_only' using errcode = '22023';
  end if;

  select count(*)::integer into v_group_target_count
  from jsonb_array_elements(p_forward_payloads) payload(value)
  where payload.value->>'target_type' = 'group';
  if v_source_depth >= 1 and v_group_target_count > 1 then
    raise exception 'forwarded_messages_allow_only_one_group_target' using errcode = '22023';
  end if;

  -- Validate the entire destination set before any insert. The database, not caller metadata,
  -- determines source depth/media attributes and the count assigned to every forwarded copy.
  for v_item in select value from jsonb_array_elements(p_forward_payloads)
  loop
    v_target_type := lower(coalesce(v_item->>'target_type', ''));
    v_target_chat_id := nullif(trim(v_item->>'target_chat_id'), '');
    v_target_message_id := nullif(trim(v_item->>'target_message_id'), '');
    if v_target_type not in ('direct', 'group') or v_target_chat_id is null or v_target_message_id is null then
      raise exception 'invalid_forward_destination' using errcode = '22023';
    end if;
    if not (v_target_chat_id = any(p_target_chat_ids)) then
      raise exception 'forward_destination_not_in_target_list' using errcode = '22023';
    end if;
    if v_target_chat_id = any(v_seen_targets) then
      raise exception 'duplicate_forward_targets' using errcode = '22023';
    end if;
    v_seen_targets := array_append(v_seen_targets, v_target_chat_id);

    if v_target_type = 'direct' then
      v_recipient := nullif(trim(v_item->>'recipient_profile_id'), '');
      v_ciphertext := coalesce(v_item->>'ciphertext', '');
      if v_recipient is null or v_recipient = v_actor or v_ciphertext not like 'TINK_ENC:%' then
        raise exception 'invalid_direct_forward_envelope' using errcode = '22023';
      end if;
      v_expected_match_id := case
        when lower(v_actor) < lower(v_recipient) then lower(v_actor) || '_' || lower(v_recipient)
        else lower(v_recipient) || '_' || lower(v_actor)
      end;
      if lower(v_target_chat_id) <> v_expected_match_id then
        raise exception 'forward_chat_does_not_match_recipient' using errcode = '42501';
      end if;
    else
      if v_target_chat_id !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
         or v_target_message_id !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
         or coalesce(jsonb_typeof(v_item->'envelopes'), '') <> 'array' then
        raise exception 'invalid_group_forward_envelopes' using errcode = '22023';
      end if;
    end if;
  end loop;
  if cardinality(v_seen_targets) <> v_target_count then
    raise exception 'forward_payload_target_count_mismatch' using errcode = '22023';
  end if;

  -- One function invocation is one transaction: any rejected destination/envelope rolls back all.
  -- Existing media paths and the encrypted voice reference remain the same; no media is re-uploaded.
  for v_item in select value from jsonb_array_elements(p_forward_payloads)
  loop
    v_target_type := lower(v_item->>'target_type');
    v_target_chat_id := trim(v_item->>'target_chat_id');
    v_target_message_id := trim(v_item->>'target_message_id');

    if v_target_type = 'direct' then
      v_recipient := trim(v_item->>'recipient_profile_id');
      v_ciphertext := coalesce(v_item->>'ciphertext', '');
      insert into public.chat_messages (
        message_id, match_id, sender_id, receiver_id, ciphertext,
        is_encrypted, is_delivered, is_read, media_type, media_url,
        voice_duration_seconds, timestamp, idempotency_key, forward_count, is_forwarded
      ) values (
        v_target_message_id, v_target_chat_id, v_actor, v_recipient, v_ciphertext,
        true, false, false, v_source_media_type, v_source_media_url,
        coalesce(v_source_voice_seconds, 0), (extract(epoch from now()) * 1000)::bigint,
        v_target_message_id, v_new_forward_count, true
      );
    else
      v_source_group_id := v_target_chat_id::uuid;
      if public.vibesync_chat_group_member_role(v_source_group_id, v_actor) is null
         or not public.vibesync_can_send_chat_group_message(v_source_group_id, v_actor) then
        raise exception 'group_membership_or_send_permission_required' using errcode = '42501';
      end if;
      select count(*)::integer into v_member_count
      from public.chat_group_members m
      where m.group_id = v_source_group_id and m.left_at is null;
      v_envelopes := v_item->'envelopes';
      if v_member_count < 1 or jsonb_array_length(v_envelopes) <> v_member_count then
        raise exception 'envelope_for_every_active_member_required' using errcode = '22023';
      end if;

      v_sender_name := coalesce(nullif(trim(v_item->>'sender_name'), ''), 'VibeSync member');
      insert into public.chat_group_messages (
        message_id, group_id, sender_profile_id, sender_name, media_type, media_url,
        voice_duration_seconds, forward_count, is_forwarded
      ) values (
        v_target_message_id::uuid, v_source_group_id, v_actor, v_sender_name,
        v_source_media_type, v_source_media_url, coalesce(v_source_voice_seconds, 0),
        v_new_forward_count, true
      );

      v_seen_recipients := array[]::text[];
      v_seen_recipient_count := 0;
      for v_envelope in select value from jsonb_array_elements(v_envelopes)
      loop
        v_recipient := nullif(trim(v_envelope->>'recipient_profile_id'), '');
        v_ciphertext := coalesce(v_envelope->>'ciphertext', '');
        if v_recipient is null or v_ciphertext not like 'TINK_ENC:%'
           or v_recipient = any(v_seen_recipients) then
          raise exception 'invalid_or_duplicate_group_forward_envelope' using errcode = '22023';
        end if;
        if not exists (
          select 1 from public.chat_group_members active
          where active.group_id = v_source_group_id
            and active.profile_id = v_recipient and active.left_at is null
        ) then
          raise exception 'forward_envelope_recipient_not_active_member' using errcode = '42501';
        end if;
        v_seen_recipients := array_append(v_seen_recipients, v_recipient);
        insert into public.chat_group_message_envelopes (
          group_id, message_id, recipient_profile_id, ciphertext
        ) values (
          v_source_group_id, v_target_message_id::uuid, v_recipient, v_ciphertext
        );
        v_seen_recipient_count := v_seen_recipient_count + 1;
      end loop;
      if v_seen_recipient_count <> v_member_count then
        raise exception 'envelope_for_every_active_member_required' using errcode = '22023';
      end if;
    end if;
  end loop;

  return jsonb_build_object(
    'forward_count', v_new_forward_count,
    'forwarded_count', v_target_count
  );
end;
$function$;

alter function public.vibesync_forward_message(text, text[], jsonb) security invoker;
alter function public.vibesync_forward_message(text, text[], jsonb) set search_path = public, pg_temp;
revoke all on function public.vibesync_forward_message(text, text[], jsonb) from public, anon;
grant execute on function public.vibesync_forward_message(text, text[], jsonb) to authenticated;

notify pgrst, 'reload schema';
commit;
