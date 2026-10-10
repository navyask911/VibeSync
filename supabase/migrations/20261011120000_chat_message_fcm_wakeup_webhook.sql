-- Dispatch a data-only FCM wake-up after a real encrypted chat row is committed.
-- Token registration and delivery receipts remain bound to the custom VibeSync session.
-- The webhook URL and shared secret are read from Supabase Vault; neither secret is stored here.

create extension if not exists pg_net with schema extensions;

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
begin
  if TG_OP <> 'INSERT' then
    return new;
  end if;

  -- Only wake the recipient for actual encrypted user messages. Avoid diagnostic/control rows.
  if coalesce(new.is_encrypted, false) is not true
     or coalesce(new.ciphertext, '') not like 'TINK_ENC:%'
     or coalesce(new.message_id, '') = ''
     or coalesce(new.receiver_id, '') = ''
     or upper(coalesce(new.media_type, 'TEXT')) = 'DIAGNOSTIC'
     or coalesce(new.dispatch_status, 'PENDING') <> 'PENDING' then
    return new;
  end if;

  select decrypted_secret
    into webhook_url
    from vault.decrypted_secrets
   where name = 'vibesync_fcm_webhook_url'
   limit 1;

  select decrypted_secret
    into webhook_secret
    from vault.decrypted_secrets
   where name = 'vibesync_fcm_webhook_secret'
   limit 1;

  if coalesce(webhook_url, '') <> 'https://imhcbgpvjwersbnlgwzq.supabase.co/functions/v1/vibesync-fcm-dispatch'
     or coalesce(webhook_secret, '') = '' then
    raise warning 'FCM wake-up skipped: required Vault webhook configuration is missing or mismatched';
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
  )
  into request_id;

  raise log 'FCM wake-up queued after chat insert: message_id=% request_id=%',
    new.message_id, request_id;
  return new;
exception
  when others then
    -- Push transport failure must not roll back the encrypted message database commit.
    raise warning 'FCM wake-up webhook could not be queued: %', SQLERRM;
    return new;
end;
$$;

revoke all on function public.forward_chat_row_change_to_fcm_edge() from public;

drop trigger if exists trg_chat_messages_fcm_edge_webhook on public.chat_messages;
drop trigger if exists trg_chat_messages_firebase_webhook on public.chat_messages;

create trigger trg_chat_messages_fcm_edge_webhook
after insert on public.chat_messages
for each row
when (
  coalesce(new.is_encrypted, false) = true
  and coalesce(new.ciphertext, '') like 'TINK_ENC:%'
  and coalesce(new.dispatch_status, 'PENDING') = 'PENDING'
  and coalesce(new.receiver_id, '') <> ''
  and upper(coalesce(new.media_type, 'TEXT')) <> 'DIAGNOSTIC'
)
execute function public.forward_chat_row_change_to_fcm_edge();
