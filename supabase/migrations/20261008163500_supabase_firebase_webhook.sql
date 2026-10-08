-- VibeSync: forward INSERT/UPDATE changes from public.chat_messages
-- to the Firebase Cloud Function via asynchronous pg_net.
--
-- Secrets are deliberately NOT stored in source control. Populate these
-- Supabase Vault entries once before relying on the webhook:
--   firebase_supabase_webhook_url
--   firebase_supabase_webhook_secret
--
-- Example one-time SQL (run in the Supabase SQL editor; do not commit values):
-- select vault.create_secret(
--   'https://asia-south1-vibesync-chat-social-connect.cloudfunctions.net/supabaseRowWebhook',
--   'firebase_supabase_webhook_url'
-- );
-- select vault.create_secret(
--   '<LONG_RANDOM_SHARED_SECRET>',
--   'firebase_supabase_webhook_secret'
-- );

create extension if not exists pg_net
  with schema extensions;

create or replace function public.forward_chat_row_change_to_firebase()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public, extensions
as $$
declare
  webhook_url text;
  webhook_secret text;
  request_id bigint;
begin
  select decrypted_secret
    into webhook_url
    from vault.decrypted_secrets
   where name = 'firebase_supabase_webhook_url'
   limit 1;

  select decrypted_secret
    into webhook_secret
    from vault.decrypted_secrets
   where name = 'firebase_supabase_webhook_secret'
   limit 1;

  if coalesce(webhook_url, '') = '' or coalesce(webhook_secret, '') = '' then
    raise warning 'Supabase→Firebase webhook skipped: Vault secrets are not configured';
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

  raise log 'Supabase→Firebase webhook queued: table=%.% operation=% request_id=%',
    TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP, request_id;

  return new;
exception
  when others then
    -- External delivery must never roll back the originating row change.
    raise warning 'Supabase→Firebase webhook failed to queue: %', SQLERRM;
    return new;
end;
$$;

revoke all on function public.forward_chat_row_change_to_firebase() from public;

drop trigger if exists trg_chat_messages_firebase_webhook on public.chat_messages;

create trigger trg_chat_messages_firebase_webhook
after insert or update on public.chat_messages
for each row
execute function public.forward_chat_row_change_to_firebase();
