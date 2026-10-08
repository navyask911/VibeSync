-- Route the chat_messages row-change webhook to the existing Supabase Edge Function.
-- A shared secret is managed in Supabase Vault and compared by a service_role-only RPC;
-- neither the secret value nor Firebase credentials are stored in source control.

create extension if not exists pg_net with schema extensions;

do $$
declare
  v_url_id uuid;
  v_secret_id uuid;
begin
  select id into v_url_id
    from vault.secrets
   where name in ('vibesync_fcm_webhook_url', 'firebase_supabase_webhook_url')
   order by case name when 'vibesync_fcm_webhook_url' then 0 else 1 end
   limit 1;

  if v_url_id is null then
    perform vault.create_secret(
      'https://imhcbgpvjwersbnlgwzq.supabase.co/functions/v1/vibesync-fcm-dispatch',
      'vibesync_fcm_webhook_url',
      'Supabase chat message webhook URL'
    );
  else
    perform vault.update_secret(
      secret_id := v_url_id,
      new_secret := 'https://imhcbgpvjwersbnlgwzq.supabase.co/functions/v1/vibesync-fcm-dispatch',
      new_name := 'vibesync_fcm_webhook_url',
      new_description := 'Supabase chat message webhook URL'
    );
  end if;

  select id into v_secret_id
    from vault.secrets
   where name in ('vibesync_fcm_webhook_secret', 'firebase_supabase_webhook_secret')
   order by case name when 'vibesync_fcm_webhook_secret' then 0 else 1 end
   limit 1;

  if v_secret_id is null then
    perform vault.create_secret(
      encode(extensions.gen_random_bytes(32), 'hex'),
      'vibesync_fcm_webhook_secret',
      'Shared secret for chat message webhook authentication'
    );
  else
    perform vault.update_secret(
      secret_id := v_secret_id,
      new_name := 'vibesync_fcm_webhook_secret',
      new_description := 'Shared secret for chat message webhook authentication'
    );
  end if;
end;
$$;

create or replace function public.verify_vibesync_fcm_webhook_secret(p_supplied_secret text)
returns boolean
language sql
security definer
set search_path = pg_catalog, public, vault
as $$
  select coalesce(
    p_supplied_secret is not null
    and p_supplied_secret <> ''
    and exists (
      select 1
      from vault.decrypted_secrets s
      where s.name = 'vibesync_fcm_webhook_secret'
        and s.decrypted_secret = p_supplied_secret
    ),
    false
  );
$$;

revoke all on function public.verify_vibesync_fcm_webhook_secret(text) from public, anon, authenticated;
grant execute on function public.verify_vibesync_fcm_webhook_secret(text) to service_role;

drop trigger if exists trg_chat_messages_firebase_webhook on public.chat_messages;
drop function if exists public.forward_chat_row_change_to_firebase();

create or replace function public.forward_chat_row_change_to_fcm_edge()
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
  select decrypted_secret into webhook_url
    from vault.decrypted_secrets
   where name = 'vibesync_fcm_webhook_url'
   limit 1;

  select decrypted_secret into webhook_secret
    from vault.decrypted_secrets
   where name = 'vibesync_fcm_webhook_secret'
   limit 1;

  if coalesce(webhook_url, '') = '' or coalesce(webhook_secret, '') = '' then
    raise warning 'VibeSync FCM webhook skipped: Vault configuration is missing';
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

  raise log 'VibeSync FCM webhook queued: table=%.% operation=% request_id=%',
    TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP, request_id;

  return new;
exception
  when others then
    raise warning 'VibeSync FCM webhook queue failed: %', SQLERRM;
    return new;
end;
$$;

revoke all on function public.forward_chat_row_change_to_fcm_edge() from public, anon, authenticated, service_role;

create trigger trg_chat_messages_fcm_edge_webhook
after insert or update of message_id, match_id, sender_id, receiver_id, ciphertext, is_encrypted, media_type
on public.chat_messages
for each row
execute function public.forward_chat_row_change_to_fcm_edge();
