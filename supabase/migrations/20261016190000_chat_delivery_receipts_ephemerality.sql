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
