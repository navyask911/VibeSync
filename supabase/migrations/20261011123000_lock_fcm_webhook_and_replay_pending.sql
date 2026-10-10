-- Keep the Vault-backed webhook authentication oracle private and replay pending
-- notifications when a recipient registers a new FCM device token.
revoke all on function public.verify_vibesync_fcm_webhook_secret(text) from public, anon, authenticated;
grant execute on function public.verify_vibesync_fcm_webhook_secret(text) to service_role;

create or replace function public.forward_pending_chat_rows_to_fcm_edge()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public, extensions, vault
as $$
declare
  webhook_url text;
  webhook_secret text;
  pending_message public.chat_messages%rowtype;
  request_id bigint;
begin
  if coalesce(new.profile_id, '') = '' then
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

  if webhook_url <> 'https://imhcbgpvjwersbnlgwzq.supabase.co/functions/v1/vibesync-fcm-dispatch'
     or coalesce(webhook_secret, '') = '' then
    raise warning 'Pending FCM replay skipped: Vault webhook configuration is missing or mismatched';
    return new;
  end if;

  -- Messages stay pending if no token existed at initial INSERT. A newly registered token
  -- triggers a bounded replay of unexpired encrypted rows for this profile. The Edge Function
  -- atomically claims each PENDING row, so duplicate device registrations remain safe.
  for pending_message in
    select *
      from public.chat_messages
     where receiver_id = new.profile_id
       and coalesce(dispatch_status, 'PENDING') = 'PENDING'
       and coalesce(is_encrypted, false) = true
       and coalesce(ciphertext, '') like 'TINK_ENC:%'
       and coalesce(media_type, 'TEXT') <> 'DIAGNOSTIC'
       and expires_at > now()
     order by timestamp asc
     limit 100
  loop
    begin
      select net.http_post(
        url := webhook_url,
        body := jsonb_build_object(
          'type', 'INSERT',
          'table', 'chat_messages',
          'schema', 'public',
          'record', to_jsonb(pending_message)
        ),
        headers := jsonb_build_object(
          'Content-Type', 'application/json',
          'x-webhook-secret', webhook_secret
        ),
        timeout_milliseconds := 5000
      ) into request_id;
    exception when others then
      raise warning 'Pending FCM replay enqueue failed for message_id=%: %',
        pending_message.message_id, SQLERRM;
    end;
  end loop;

  return new;
exception
  when others then
    -- Token registration must succeed even if push transport is temporarily unavailable.
    raise warning 'Pending FCM replay trigger failed: %', SQLERRM;
    return new;
end;
$$;

revoke all on function public.forward_pending_chat_rows_to_fcm_edge() from public;

drop trigger if exists trg_device_token_pending_fcm_replay on public.device_tokens;
create trigger trg_device_token_pending_fcm_replay
after insert on public.device_tokens
for each row
when (coalesce(new.profile_id, '') <> '')
execute function public.forward_pending_chat_rows_to_fcm_edge();
