begin;

-- Keep the storage object reference in the private receipt mapping after the ciphertext row is deleted.
alter table public.chat_message_receipt_participants
  add column if not exists media_url text not null default '';

create or replace function public.capture_chat_message_receipt()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $function$
begin
  insert into public.chat_message_receipt_participants as existing
    (message_id, match_id, sender_id, receiver_id, created_at, media_url)
  values
    (new.message_id, new.match_id, new.sender_id, new.receiver_id,
     coalesce(new.created_at, now()), coalesce(new.media_url, ''))
  on conflict (message_id) do update set
    match_id = excluded.match_id,
    sender_id = excluded.sender_id,
    receiver_id = excluded.receiver_id,
    media_url = case
      when coalesce(existing.media_url, '') = ''
      then excluded.media_url
      else existing.media_url
    end;

  insert into public.chat_message_receipts(message_id, match_id, status, "timestamp")
  values (new.message_id, new.match_id, 'SENT_TO_SERVER', now())
  on conflict (message_id, status) do nothing;

  if coalesce(new.is_delivered, false) then
    insert into public.chat_message_receipts(message_id, match_id, status, "timestamp")
    values (new.message_id, new.match_id, 'DELIVERED', coalesce(new.delivered_at, now()))
    on conflict (message_id, status) do nothing;
  end if;

  if coalesce(new.is_read, false) then
    insert into public.chat_message_receipts(message_id, match_id, status, "timestamp")
    values (new.message_id, new.match_id, 'READ', coalesce(new.read_at, now()))
    on conflict (message_id, status) do nothing;
  end if;

  return new;
end;
$function$;
revoke all on function public.capture_chat_message_receipt() from public, anon, authenticated;

create or replace function public.acknowledge_and_purge_message(p_message_id text)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $function$
declare
  v_actor text := public.vibesync_jwt_profile_id();
  v_row public.chat_messages%rowtype;
  v_participant public.chat_message_receipt_participants%rowtype;
  v_now timestamptz := clock_timestamp();
  v_id text := btrim(coalesce(p_message_id, ''));
begin
  if not public.vibesync_jwt_session_active() or coalesce(v_actor, '') = '' then
    raise exception 'verified_session_required' using errcode = '42501';
  end if;
  if v_id = '' then
    raise exception 'message_id_required' using errcode = '22023';
  end if;

  select m.* into v_row
    from public.chat_messages m
   where m.message_id = v_id and m.receiver_id = v_actor
   for update;

  if not found then
    -- Idempotent retry can still trigger server-side media cleanup from the private mapping.
    select p.* into v_participant
      from public.chat_message_receipt_participants p
     where p.message_id = v_id and p.receiver_id = v_actor;
    if found then
      return jsonb_build_object(
        'ok', true,
        'already_purged', true,
        'message_id', v_participant.message_id,
        'match_id', v_participant.match_id,
        'status', 'DELIVERED',
        'timestamp', v_now,
        'media_url', coalesce(v_participant.media_url, '')
      );
    end if;
    return jsonb_build_object('ok', true, 'already_purged', true, 'message_id', v_id);
  end if;

  if not coalesce(v_row.is_encrypted, false)
     or coalesce(v_row.ciphertext, '') not like 'TINK_ENC:%' then
    raise exception 'encrypted_message_required' using errcode = '22023';
  end if;

  insert into public.chat_message_receipt_participants as existing
    (message_id, match_id, sender_id, receiver_id, created_at, media_url)
  values
    (v_row.message_id, v_row.match_id, v_row.sender_id, v_row.receiver_id,
     coalesce(v_row.created_at, v_now), coalesce(v_row.media_url, ''))
  on conflict (message_id) do update set
    match_id = excluded.match_id,
    sender_id = excluded.sender_id,
    receiver_id = excluded.receiver_id,
    media_url = case
      when coalesce(existing.media_url, '') = ''
      then excluded.media_url
      else existing.media_url
    end;

  insert into public.chat_message_receipts(message_id, match_id, status, "timestamp")
  values (v_row.message_id, v_row.match_id, 'DELIVERED', v_now)
  on conflict (message_id, status) do update set "timestamp" = excluded."timestamp";

  delete from public.chat_messages
   where message_id = v_row.message_id and receiver_id = v_actor;

  return jsonb_build_object(
    'ok', true,
    'already_purged', false,
    'message_id', v_row.message_id,
    'match_id', v_row.match_id,
    'status', 'DELIVERED',
    'timestamp', v_now,
    'media_url', coalesce(v_row.media_url, '')
  );
end;
$function$;
revoke all on function public.acknowledge_and_purge_message(text) from public, anon;
grant execute on function public.acknowledge_and_purge_message(text) to authenticated;

create or replace function public.cleanup_expired_undelivered_chat_messages()
returns bigint
language plpgsql
security definer
set search_path = pg_catalog, public, vault
as $function$
declare
  v_deleted bigint := 0;
  v_message record;
  v_webhook_secret text;
  v_request_id bigint;
begin
  -- Preserve object references in the private mapping before expired ciphertext rows disappear.
  insert into public.chat_message_receipt_participants as existing
    (message_id, match_id, sender_id, receiver_id, created_at, media_url)
  select m.message_id, m.match_id, m.sender_id, m.receiver_id,
         coalesce(m.created_at, to_timestamp(m.timestamp / 1000.0)),
         coalesce(m.media_url, '')
    from public.chat_messages m
   where coalesce(m.is_delivered, false) = false
     and coalesce(m.created_at, to_timestamp(m.timestamp / 1000.0)) < now() - interval '30 days'
  on conflict (message_id) do update set
    media_url = case
      when coalesce(existing.media_url, '') = ''
      then excluded.media_url
      else existing.media_url
    end;

  delete from public.chat_messages
   where coalesce(is_delivered, false) = false
     and coalesce(created_at, to_timestamp(timestamp / 1000.0)) < now() - interval '30 days';
  get diagnostics v_deleted = row_count;

  -- Retry Storage cleanup for delivered objects and >30-day undelivered objects.
  -- The request contains metadata IDs only; the edge function reads the URL from the private table.
  if exists (select 1 from pg_extension where extname = 'pg_net') then
    select decrypted_secret into v_webhook_secret
      from vault.decrypted_secrets
     where name = 'vibesync_fcm_webhook_secret'
     limit 1;

    if coalesce(v_webhook_secret, '') <> '' then
      for v_message in
        select p.message_id
          from public.chat_message_receipt_participants p
         where coalesce(p.media_url, '') <> ''
           and (
             exists (
               select 1 from public.chat_message_receipts r
                where r.message_id = p.message_id and r.status = 'DELIVERED'
             )
             or p.created_at < now() - interval '30 days'
           )
         order by p.created_at
         limit 500
      loop
        begin
          select net.http_post(
            url := 'https://imhcbgpvjwersbnlgwzq.supabase.co/functions/v1/vibesync-purge-chat-media',
            body := jsonb_build_object('mode', 'retry_media', 'message_id', v_message.message_id),
            headers := jsonb_build_object(
              'Content-Type', 'application/json',
              'x-webhook-secret', v_webhook_secret
            ),
            timeout_milliseconds := 5000
          ) into v_request_id;
        exception when others then
          raise warning 'Chat media cleanup enqueue failed for message_id=%: %',
            v_message.message_id, SQLERRM;
        end;
      end loop;
    end if;
  end if;

  delete from public.chat_message_receipts
   where "timestamp" < now() - interval '30 days';

  delete from public.chat_message_receipt_participants p
   where p.created_at < now() - interval '30 days'
     and coalesce(p.media_url, '') = ''
     and not exists (
       select 1 from public.chat_message_receipts r
        where r.message_id = p.message_id
     );

  return v_deleted;
end;
$function$;
revoke all on function public.cleanup_expired_undelivered_chat_messages() from public, anon, authenticated;
grant execute on function public.cleanup_expired_undelivered_chat_messages() to service_role;

notify pgrst, 'reload schema';
commit;
