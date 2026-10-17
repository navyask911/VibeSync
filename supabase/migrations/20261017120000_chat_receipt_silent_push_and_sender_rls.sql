begin;

-- Receipt events are metadata only. Only the sender may read their delivery timeline.
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
        and p.sender_id = public.vibesync_jwt_profile_id()
    );
$function$;

revoke all on function public.vibesync_receipt_visible(text) from public, anon;
grant execute on function public.vibesync_receipt_visible(text) to authenticated;

drop policy if exists chat_message_receipts_participant_select on public.chat_message_receipts;
create policy chat_message_receipts_participant_select
  on public.chat_message_receipts
  for select to authenticated
  using (public.vibesync_receipt_visible(message_id));

-- Route durable receipt changes to the HTTP v1 FCM Edge Function. SQL never calls FCM itself.
create or replace function public.forward_chat_receipt_to_fcm_edge()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
  webhook_url text;
  webhook_secret text;
  request_id bigint;
begin
  if coalesce(new.status, '') not in ('DELIVERED', 'READ', 'FAILED') then
    return new;
  end if;

  select decrypted_secret into webhook_url
    from vault.decrypted_secrets
   where name = 'vibesync_fcm_webhook_url'
   limit 1;
  select decrypted_secret into webhook_secret
    from vault.decrypted_secrets
   where name = 'vibesync_fcm_webhook_secret'
   limit 1;

  if coalesce(webhook_url, '') <> 'https://imhcbgpvjwersbnlgwzq.supabase.co/functions/v1/vibesync-fcm-dispatch'
     or coalesce(webhook_secret, '') = '' then
    raise warning 'Receipt FCM wake-up skipped: configured Vault webhook URL/secret is missing or mismatched';
    return new;
  end if;

  select net.http_post(
    url := webhook_url,
    body := jsonb_build_object(
      'type', TG_OP,
      'table', TG_TABLE_NAME,
      'schema', TG_TABLE_SCHEMA,
      'record', to_jsonb(new)
    ),
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'x-webhook-secret', webhook_secret
    ),
    timeout_milliseconds := 5000
  ) into request_id;

  raise log 'Receipt FCM wake-up queued: message_id=% status=% request_id=%',
    new.message_id, new.status, request_id;
  return new;
exception
  when others then
    -- Receipt persistence must never be rolled back because push transport is temporarily down.
    raise warning 'Receipt FCM wake-up webhook could not be queued for message_id=%: %',
      new.message_id, SQLERRM;
    return new;
end;
$function$;

revoke all on function public.forward_chat_receipt_to_fcm_edge() from public, anon, authenticated;
drop trigger if exists trg_chat_receipts_fcm_edge_webhook on public.chat_message_receipts;
create trigger trg_chat_receipts_fcm_edge_webhook
after insert or update of status on public.chat_message_receipts
for each row execute function public.forward_chat_receipt_to_fcm_edge();

-- A token that appears after a receipt was written must get a bounded replay. Limit it to
-- recent receipt metadata for this sender and to this just-registered device token.
create or replace function public.forward_recent_chat_receipts_to_fcm_edge()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
  webhook_url text;
  webhook_secret text;
  receipt_row record;
  request_id bigint;
begin
  if coalesce(new.profile_id, '') = '' or coalesce(new.fcm_token, '') = '' then
    return new;
  end if;

  select decrypted_secret into webhook_url
    from vault.decrypted_secrets
   where name = 'vibesync_fcm_webhook_url'
   limit 1;
  select decrypted_secret into webhook_secret
    from vault.decrypted_secrets
   where name = 'vibesync_fcm_webhook_secret'
   limit 1;

  if coalesce(webhook_url, '') <> 'https://imhcbgpvjwersbnlgwzq.supabase.co/functions/v1/vibesync-fcm-dispatch'
     or coalesce(webhook_secret, '') = '' then
    raise warning 'Receipt replay skipped: configured Vault webhook URL/secret is missing or mismatched';
    return new;
  end if;

  for receipt_row in
    select r.message_id, r.match_id, r.status, r."timestamp"
      from public.chat_message_receipts r
      join public.chat_message_receipt_participants p on p.message_id = r.message_id
     where p.sender_id = new.profile_id
       and r.status in ('DELIVERED', 'READ', 'FAILED')
       and r."timestamp" > now() - interval '30 days'
     order by r."timestamp" desc
     limit 100
  loop
    begin
      select net.http_post(
        url := webhook_url,
        body := jsonb_build_object(
          'type', 'INSERT',
          'table', 'chat_message_receipts',
          'schema', 'public',
          'record', jsonb_build_object(
            'message_id', receipt_row.message_id,
            'match_id', receipt_row.match_id,
            'status', receipt_row.status,
            'timestamp', receipt_row."timestamp",
            'target_device_token_id', new.id
          )
        ),
        headers := jsonb_build_object(
          'Content-Type', 'application/json',
          'x-webhook-secret', webhook_secret
        ),
        timeout_milliseconds := 5000
      ) into request_id;
    exception when others then
      raise warning 'Receipt replay enqueue failed for message_id=%: %',
        receipt_row.message_id, SQLERRM;
    end;
  end loop;
  return new;
exception
  when others then
    raise warning 'Receipt replay could not be queued for profile_id=%: %',
      new.profile_id, SQLERRM;
    return new;
end;
$function$;

revoke all on function public.forward_recent_chat_receipts_to_fcm_edge() from public, anon, authenticated;
drop trigger if exists trg_device_token_receipt_replay_fcm_edge on public.device_tokens;
create trigger trg_device_token_receipt_replay_fcm_edge
after insert or update on public.device_tokens
for each row execute function public.forward_recent_chat_receipts_to_fcm_edge();

-- Defensive idempotent check; this table is already in the publication on the current project.
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

notify pgrst, 'reload schema';
commit;
