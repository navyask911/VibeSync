-- Keep the active FCM trigger compatible with legacy chat_messages rows.
-- Current live rows use id/sender_phone; the deployed Edge Function reads
-- record.message_id and record.sender_id, so supply both root and record keys.

create or replace function public.forward_chat_row_change_to_fcm_edge()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public, extensions, vault
as $$
declare
  webhook_url text;
  webhook_secret text;
  request_id bigint;
  row_payload jsonb;
  payload_message_id text;
  payload_sender_id text;
begin
  row_payload := to_jsonb(new);

  payload_message_id := coalesce(nullif(row_payload ->> 'message_id', ''), nullif(row_payload ->> 'id', ''));
  payload_sender_id := coalesce(nullif(row_payload ->> 'sender_id', ''), nullif(row_payload ->> 'sender_phone', ''));

  if coalesce(payload_message_id, '') = '' or coalesce(payload_sender_id, '') = '' then
    raise warning 'VibeSync FCM webhook skipped: message identifier or sender is missing';
    return new;
  end if;

  row_payload := row_payload || jsonb_build_object('message_id', payload_message_id, 'sender_id', payload_sender_id);

  select decrypted_secret into webhook_url
    from vault.decrypted_secrets where name = 'vibesync_fcm_webhook_url' limit 1;
  select decrypted_secret into webhook_secret
    from vault.decrypted_secrets where name = 'vibesync_fcm_webhook_secret' limit 1;

  if coalesce(webhook_url, '') = '' or coalesce(webhook_secret, '') = '' then
    raise warning 'VibeSync FCM webhook skipped: Vault configuration is missing';
    return new;
  end if;

  select net.http_post(
    url := webhook_url,
    body := jsonb_build_object(
      'type', TG_OP, 'table', TG_TABLE_NAME, 'schema', TG_TABLE_SCHEMA,
      'message_id', payload_message_id, 'sender_id', payload_sender_id, 'record', row_payload
    ),
    headers := jsonb_build_object('Content-Type', 'application/json', 'x-webhook-secret', webhook_secret),
    timeout_milliseconds := 5000
  ) into request_id;

  raise log 'VibeSync FCM webhook queued: table=%.% operation=% message_id=% request_id=%',
    TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP, payload_message_id, request_id;
  return new;
exception
  when others then
    raise warning 'VibeSync FCM webhook queue failed: %', SQLERRM;
    return new;
end;
$$;

-- Keep the historical function name compatible for any lingering/manual caller.
create or replace function public.forward_chat_row_change_to_firebase()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public, extensions, vault
as $$
declare
  webhook_url text;
  webhook_secret text;
  request_id bigint;
  row_payload jsonb;
  payload_message_id text;
  payload_sender_id text;
begin
  row_payload := to_jsonb(new);

  payload_message_id := coalesce(nullif(row_payload ->> 'message_id', ''), nullif(row_payload ->> 'id', ''));
  payload_sender_id := coalesce(nullif(row_payload ->> 'sender_id', ''), nullif(row_payload ->> 'sender_phone', ''));

  if coalesce(payload_message_id, '') = '' or coalesce(payload_sender_id, '') = '' then
    raise warning 'VibeSync FCM webhook skipped: message identifier or sender is missing';
    return new;
  end if;

  row_payload := row_payload || jsonb_build_object('message_id', payload_message_id, 'sender_id', payload_sender_id);

  select decrypted_secret into webhook_url
    from vault.decrypted_secrets where name = 'vibesync_fcm_webhook_url' limit 1;
  select decrypted_secret into webhook_secret
    from vault.decrypted_secrets where name = 'vibesync_fcm_webhook_secret' limit 1;

  if coalesce(webhook_url, '') = '' or coalesce(webhook_secret, '') = '' then
    raise warning 'VibeSync FCM webhook skipped: Vault configuration is missing';
    return new;
  end if;

  select net.http_post(
    url := webhook_url,
    body := jsonb_build_object(
      'type', TG_OP, 'table', TG_TABLE_NAME, 'schema', TG_TABLE_SCHEMA,
      'message_id', payload_message_id, 'sender_id', payload_sender_id, 'record', row_payload
    ),
    headers := jsonb_build_object('Content-Type', 'application/json', 'x-webhook-secret', webhook_secret),
    timeout_milliseconds := 5000
  ) into request_id;

  raise log 'VibeSync FCM webhook queued: table=%.% operation=% message_id=% request_id=%',
    TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP, payload_message_id, request_id;
  return new;
exception
  when others then
    raise warning 'VibeSync FCM webhook queue failed: %', SQLERRM;
    return new;
end;
$$;

revoke all on function public.forward_chat_row_change_to_fcm_edge() from public, anon, authenticated, service_role;
revoke all on function public.forward_chat_row_change_to_firebase() from public, anon, authenticated, service_role;
