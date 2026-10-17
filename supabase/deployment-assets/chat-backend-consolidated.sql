-- VibeSync chat backend consolidated SQL snapshot
-- Source of truth: the four individually versioned migrations listed below.
-- This is a convenience copy for review / one-shot setup on a database that already
-- has the base VibeSync schema and all helper functions referenced by these migrations.
-- Production project imhcbgpvjwersbnlgwzq has ALREADY applied these versions via CI;
-- do not replay this consolidated snapshot on that project.
--
-- Includes:
-- * chat_message_receipts + private participant mapping, RLS, grants/publication,
--   acknowledge_and_purge_message(text), 30-day TTL cleanup and cron scheduling
-- * receipt-bound media purge mapping and retry cleanup
-- * service-only daily quota storage and consume_chat_ai_image_quota(text, integer)
-- * private encrypted chat-attachment bucket and participant-scoped storage policies
--
-- Individual migrations remain the canonical migration history. The original BEGIN /
-- COMMIT boundaries are preserved, so each section commits in its migration order.


-- ============================================================================
-- SOURCE: supabase/migrations/20261016190000_chat_delivery_receipts_ephemerality.sql
-- ============================================================================

begin;

-- Realtime carries only the four small control fields requested by the client.
create table if not exists public.chat_message_receipts (
  message_id text not null,
  match_id text not null,
  status text not null check (status in ('SENT_TO_SERVER', 'DELIVERED', 'READ', 'FAILED')),
  "timestamp" timestamptz not null default now(),
  primary key (message_id, status)
);

-- RLS needs a durable participant mapping because DELIVERED purges the ciphertext row.
-- This internal table is not exposed to PostgREST clients.
create table if not exists public.chat_message_receipt_participants (
  message_id text primary key,
  match_id text not null,
  sender_id text not null,
  receiver_id text not null,
  created_at timestamptz not null default now()
);

alter table public.chat_message_receipts enable row level security;
alter table public.chat_message_receipt_participants enable row level security;
revoke all on table public.chat_message_receipts from public, anon, authenticated;
revoke all on table public.chat_message_receipt_participants from public, anon, authenticated;
grant select on table public.chat_message_receipts to authenticated;

create or replace function public.vibesync_receipt_visible(p_message_id text)
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public
as $function$
  select public.vibesync_jwt_session_active()
    and exists (
      select 1
      from public.chat_message_receipt_participants p
      where p.message_id = p_message_id
        and (
          p.sender_id = public.vibesync_jwt_profile_id()
          or p.receiver_id = public.vibesync_jwt_profile_id()
        )
    );
$function$;

revoke all on function public.vibesync_receipt_visible(text) from public, anon;
grant execute on function public.vibesync_receipt_visible(text) to authenticated;

drop policy if exists chat_message_receipts_participant_select on public.chat_message_receipts;
create policy chat_message_receipts_participant_select
  on public.chat_message_receipts
  for select to authenticated
  using (public.vibesync_receipt_visible(message_id));

create index if not exists chat_message_receipts_match_timestamp_idx
  on public.chat_message_receipts (match_id, "timestamp");

create or replace function public.capture_chat_message_receipt()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $function$
begin
  insert into public.chat_message_receipt_participants
    (message_id, match_id, sender_id, receiver_id, created_at)
  values
    (new.message_id, new.match_id, new.sender_id, new.receiver_id, coalesce(new.created_at, now()))
  on conflict (message_id) do update set
    match_id = excluded.match_id,
    sender_id = excluded.sender_id,
    receiver_id = excluded.receiver_id;

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
drop trigger if exists trg_chat_message_receipt_ledger on public.chat_messages;
create trigger trg_chat_message_receipt_ledger
after insert or update of is_delivered, is_read on public.chat_messages
for each row execute function public.capture_chat_message_receipt();

-- A READ receipt remains valid after DELIVERED removed the ciphertext row.
create or replace function public.acknowledge_chat_message_read(p_message_id text)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $function$
declare
  v_actor text := public.vibesync_jwt_profile_id();
  v_participant public.chat_message_receipt_participants%rowtype;
  v_now timestamptz := clock_timestamp();
begin
  if not public.vibesync_jwt_session_active() or coalesce(v_actor, '') = '' then
    raise exception 'verified_session_required' using errcode = '42501';
  end if;
  select p.* into v_participant
    from public.chat_message_receipt_participants p
   where p.message_id = btrim(coalesce(p_message_id, ''))
   for update;
  if not found or v_participant.receiver_id <> v_actor then
    raise exception 'message_not_addressed_to_current_profile' using errcode = '42501';
  end if;
  insert into public.chat_message_receipts(message_id, match_id, status, "timestamp")
  values (v_participant.message_id, v_participant.match_id, 'READ', v_now)
  on conflict (message_id, status) do update
    set "timestamp" = excluded."timestamp";
  return jsonb_build_object(
    'ok', true, 'message_id', v_participant.message_id,
    'match_id', v_participant.match_id, 'status', 'READ', 'timestamp', v_now
  );
end;
$function$;
revoke all on function public.acknowledge_chat_message_read(text) from public, anon;
grant execute on function public.acknowledge_chat_message_read(text) to authenticated;

-- Called only after the receiving device has decrypted and committed the message to Room.
-- The caller uses the returned media_url, when present, to remove the object via Storage API.
create or replace function public.acknowledge_and_purge_message(p_message_id text)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $function$
declare
  v_actor text := public.vibesync_jwt_profile_id();
  v_row public.chat_messages%rowtype;
  v_now timestamptz := clock_timestamp();
begin
  if not public.vibesync_jwt_session_active()
     or coalesce(v_actor, '') = '' then
    raise exception 'verified_session_required' using errcode = '42501';
  end if;
  if coalesce(btrim(p_message_id), '') = '' then
    raise exception 'message_id_required' using errcode = '22023';
  end if;

  select m.* into v_row
  from public.chat_messages m
  where m.message_id = btrim(p_message_id)
    and m.receiver_id = v_actor
  for update;

  if not found then
    -- Idempotent retries are safe: no ciphertext is returned by this RPC.
    return jsonb_build_object('ok', true, 'already_purged', true, 'message_id', btrim(p_message_id));
  end if;

  if not coalesce(v_row.is_encrypted, false)
     or coalesce(v_row.ciphertext, '') not like 'TINK_ENC:%' then
    raise exception 'encrypted_message_required' using errcode = '22023';
  end if;

  insert into public.chat_message_receipt_participants
    (message_id, match_id, sender_id, receiver_id, created_at)
  values
    (v_row.message_id, v_row.match_id, v_row.sender_id, v_row.receiver_id, coalesce(v_row.created_at, v_now))
  on conflict (message_id) do nothing;

  insert into public.chat_message_receipts(message_id, match_id, status, "timestamp")
  values (v_row.message_id, v_row.match_id, 'DELIVERED', v_now)
  on conflict (message_id, status) do nothing;

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

create or replace function public.nack_chat_message(p_message_id text)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $function$
declare
  v_actor text := public.vibesync_jwt_profile_id();
  v_row public.chat_messages%rowtype;
  v_now timestamptz := clock_timestamp();
begin
  if not public.vibesync_jwt_session_active()
     or coalesce(v_actor, '') = '' then
    raise exception 'verified_session_required' using errcode = '42501';
  end if;

  select m.* into v_row
  from public.chat_messages m
  where m.message_id = btrim(coalesce(p_message_id, ''))
    and m.receiver_id = v_actor
  for update;

  if not found then
    return jsonb_build_object('ok', false, 'reason', 'message_unavailable');
  end if;

  insert into public.chat_message_receipt_participants
    (message_id, match_id, sender_id, receiver_id, created_at)
  values (v_row.message_id, v_row.match_id, v_row.sender_id, v_row.receiver_id, coalesce(v_row.created_at, v_now))
  on conflict (message_id) do nothing;

  insert into public.chat_message_receipts(message_id, match_id, status, "timestamp")
  values (v_row.message_id, v_row.match_id, 'FAILED', v_now)
  on conflict (message_id, status) do update set "timestamp" = excluded."timestamp";

  return jsonb_build_object(
    'ok', true, 'message_id', v_row.message_id, 'match_id', v_row.match_id,
    'status', 'FAILED', 'timestamp', v_now
  );
end;
$function$;

revoke all on function public.nack_chat_message(text) from public, anon;
grant execute on function public.nack_chat_message(text) to authenticated;

-- Keep only metadata on the Realtime-published receipt table.
do $publication$
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime')
     and not exists (
       select 1 from pg_publication_tables
       where pubname = 'supabase_realtime'
         and schemaname = 'public'
         and tablename = 'chat_message_receipts'
     ) then
    alter publication supabase_realtime add table public.chat_message_receipts;
  end if;
end;
$publication$;

-- Reap ciphertext that was never delivered. Delivered rows are purged on acknowledgement.
-- Supabase Storage objects are deliberately not deleted with SQL; SQL deletion would orphan bytes.
create or replace function public.cleanup_expired_undelivered_chat_messages()
returns bigint
language plpgsql
security definer
set search_path = pg_catalog, public
as $function$
declare
  v_deleted bigint := 0;
begin
  delete from public.chat_messages
  where coalesce(is_delivered, false) = false
    and coalesce(created_at, to_timestamp(timestamp / 1000.0)) < now() - interval '30 days';
  get diagnostics v_deleted = row_count;

  -- Receipt history is also bounded: delete expired status rows first, then
  -- remove participant mappings only when no receipt for the message remains.
  delete from public.chat_message_receipts
  where "timestamp" < now() - interval '30 days';
  delete from public.chat_message_receipt_participants p
  where p.created_at < now() - interval '30 days'
    and not exists (
      select 1 from public.chat_message_receipts r
      where r.message_id = p.message_id
    );

  return v_deleted;
end;
$function$;

revoke all on function public.cleanup_expired_undelivered_chat_messages() from public, anon, authenticated;
grant execute on function public.cleanup_expired_undelivered_chat_messages() to service_role;

do $cron$
begin
  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    perform cron.schedule(
      'vibesync-chat-undelivered-ttl-30d',
      '17 * * * *',
      'select public.cleanup_expired_undelivered_chat_messages();'
    );
  end if;
exception when others then
  raise warning '30-day undelivered-message TTL schedule could not be registered: %', SQLERRM;
end;
$cron$;

notify pgrst, 'reload schema';
commit;


-- ============================================================================
-- SOURCE: supabase/migrations/20261016210000_chat_media_purge_edge.sql
-- ============================================================================

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


-- ============================================================================
-- SOURCE: supabase/migrations/20261016220000_chat_ai_image_daily_quotas.sql
-- ============================================================================

begin;

create table if not exists public.chat_ai_image_daily_usage (
    profile_id text not null,
    usage_date date not null,
    request_count integer not null default 0 check (request_count >= 0),
    primary key (profile_id, usage_date)
);

alter table public.chat_ai_image_daily_usage enable row level security;
revoke all on table public.chat_ai_image_daily_usage from public, anon, authenticated;
grant select, insert, update, delete on table public.chat_ai_image_daily_usage to service_role;

-- Only the server-side Edge Function can consume quota; callers cannot choose another profile.
create or replace function public.consume_chat_ai_image_quota(
    p_profile_id text,
    p_daily_limit integer default 8
)
returns integer
language plpgsql
set search_path = ''
as $function$
declare
    v_profile_id text := nullif(btrim(p_profile_id), '');
    v_daily_limit integer := least(greatest(coalesce(p_daily_limit, 8), 1), 12);
    v_used integer;
    v_day date := (timezone('utc', now()))::date;
begin
    if v_profile_id is null then
        raise exception 'profile_id_required' using errcode = '22023';
    end if;

    insert into public.chat_ai_image_daily_usage(profile_id, usage_date, request_count)
    values (v_profile_id, v_day, 1)
    on conflict (profile_id, usage_date) do update
       set request_count = public.chat_ai_image_daily_usage.request_count + 1
     where public.chat_ai_image_daily_usage.request_count < v_daily_limit
    returning request_count into v_used;

    return v_used;
end;
$function$;

revoke all on function public.consume_chat_ai_image_quota(text, integer) from public, anon, authenticated;
grant execute on function public.consume_chat_ai_image_quota(text, integer) to service_role;

notify pgrst, 'reload schema';
commit;


-- ============================================================================
-- SOURCE: supabase/migrations/20261016230000_chat_encrypted_attachments.sql
-- ============================================================================

-- All generic chat attachments are encrypted by the sender before private Storage upload.
begin;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('chat-attachments', 'chat-attachments', false, 26214464, array['application/octet-stream'])
on conflict (id) do update
set public = false, file_size_limit = 26214464,
    allowed_mime_types = array['application/octet-stream'];

-- Receipt mappings outlive the ciphertext row and are used to authorize recipient downloads
-- after the hard-delete RPC removes chat_messages.
create or replace function public.vibesync_can_read_chat_storage_object(p_bucket_id text, p_name text)
returns boolean
language plpgsql
stable
security definer
set search_path = pg_catalog, public
as $$
declare
    v_actor text := public.vibesync_jwt_profile_id();
    v_parts text[] := string_to_array(coalesce(p_name, ''), '/');
    v_owner text;
    v_match_id text;
    v_message_id text;
begin
    if v_actor is null or v_actor = '' or not public.vibesync_jwt_session_active() then
        return false;
    end if;
    if array_length(v_parts, 1) <> 4 or
       v_parts[1] is null or v_parts[1] = '' or
       v_parts[2] is null or v_parts[3] is null or v_parts[4] is null or
       v_parts[1] like '%..%' or v_parts[2] like '%..%' or
       v_parts[3] like '%..%' or v_parts[4] like '%..%' then
        return false;
    end if;

    v_owner := v_parts[1];
    v_match_id := v_parts[3];

    if p_bucket_id = 'chat-attachments' and v_parts[2] = 'attachments'
       and right(v_parts[4], 4) = '.enc' then
        v_message_id := left(v_parts[4], length(v_parts[4]) - 4);
    elsif p_bucket_id = 'chat-voice-notes' and v_parts[2] = 'voice-notes'
       and right(v_parts[4], 8) = '.m4a.enc' then
        v_message_id := left(v_parts[4], length(v_parts[4]) - 8);
    else
        return false;
    end if;

    if v_message_id is null or v_message_id = '' or v_message_id like '%..%' then
        return false;
    end if;
    if v_owner = v_actor then
        return true;
    end if;

    return exists (
        select 1
        from public.chat_message_receipt_participants p
        where p.message_id = v_message_id
          and p.match_id = v_match_id
          and p.sender_id = v_owner
          and p.receiver_id = v_actor
    );
end;
$$;

revoke all on function public.vibesync_can_read_chat_storage_object(text, text) from public, anon;
grant execute on function public.vibesync_can_read_chat_storage_object(text, text) to authenticated;

drop policy if exists vibesync_chat_attachments_owner_insert on storage.objects;
create policy vibesync_chat_attachments_owner_insert on storage.objects
for insert to authenticated
with check (
    bucket_id = 'chat-attachments'
    and public.vibesync_jwt_session_active()
    and (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
    and (storage.foldername(name))[2] = 'attachments'
);

drop policy if exists vibesync_chat_attachments_owner_update on storage.objects;
create policy vibesync_chat_attachments_owner_update on storage.objects
for update to authenticated
using (
    bucket_id = 'chat-attachments'
    and public.vibesync_jwt_session_active()
    and (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
    and (storage.foldername(name))[2] = 'attachments'
)
with check (
    bucket_id = 'chat-attachments'
    and public.vibesync_jwt_session_active()
    and (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
    and (storage.foldername(name))[2] = 'attachments'
);

drop policy if exists vibesync_chat_attachments_participant_select on storage.objects;
create policy vibesync_chat_attachments_participant_select on storage.objects
for select to authenticated
using (bucket_id = 'chat-attachments' and public.vibesync_can_read_chat_storage_object(bucket_id, name));

drop policy if exists vibesync_chat_attachments_owner_delete on storage.objects;
create policy vibesync_chat_attachments_owner_delete on storage.objects
for delete to authenticated
using (
    bucket_id = 'chat-attachments'
    and public.vibesync_jwt_session_active()
    and (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
    and (storage.foldername(name))[2] = 'attachments'
);

-- Upsert permission is scoped to an active sender and only their private voice-note folder.
drop policy if exists vibesync_voice_notes_owner_update on storage.objects;
create policy vibesync_voice_notes_owner_update on storage.objects
for update to authenticated
using (
    bucket_id = 'chat-voice-notes'
    and public.vibesync_jwt_session_active()
    and (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
    and (storage.foldername(name))[2] = 'voice-notes'
)
with check (
    bucket_id = 'chat-voice-notes'
    and public.vibesync_jwt_session_active()
    and (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
    and (storage.foldername(name))[2] = 'voice-notes'
);

-- New voice objects will be match/message scoped. Keep the original policy for legacy objects.
drop policy if exists vibesync_voice_notes_receipt_participant_select on storage.objects;
create policy vibesync_voice_notes_receipt_participant_select on storage.objects
for select to authenticated
using (bucket_id = 'chat-voice-notes' and public.vibesync_can_read_chat_storage_object(bucket_id, name));

update storage.buckets
set file_size_limit = 15728656,
    allowed_mime_types = array['application/octet-stream'],
    public = false
where id = 'chat-voice-notes';

notify pgrst, 'reload schema';
commit;
