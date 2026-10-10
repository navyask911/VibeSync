-- Remove permissive legacy chat policies/table grants that bypassed participant-level RLS.
-- Chat writes use send_encrypted_message/ack_* RPCs; direct access is limited to participant rows.
begin;

alter table public.chat_messages enable row level security;

drop policy if exists "Allow all operations on chat_messages" on public.chat_messages;
drop policy if exists chat_messages_open_access on public.chat_messages;
drop policy if exists chat_messages_truecaller_realtime_select_participant on public.chat_messages;
drop policy if exists chat_messages_truecaller_jwt_insert_sender on public.chat_messages;
drop policy if exists chat_messages_truecaller_jwt_update_participant on public.chat_messages;
drop policy if exists chat_messages_truecaller_jwt_delete_participant on public.chat_messages;
drop policy if exists chat_messages_legacy_session_select_participant on public.chat_messages;

-- Remove broad table-level SELECT/INSERT/UPDATE/DELETE/TRUNCATE/etc. grants.
-- Re-grant only the operations and columns the application actually uses.
revoke all on table public.chat_messages from anon, authenticated, public;

grant select (
  message_id, match_id, sender_id, receiver_id, ciphertext, timestamp,
  is_delivered, is_read, media_url, media_type, voice_duration_seconds, created_at
) on table public.chat_messages to anon, authenticated;

grant insert (
  message_id, match_id, sender_id, receiver_id, ciphertext, is_encrypted,
  media_url, media_type, voice_duration_seconds, timestamp, idempotency_key
) on table public.chat_messages to authenticated;

grant update (is_delivered, is_read, delivered_at, read_at, dispatch_status)
  on table public.chat_messages to authenticated;
grant delete on table public.chat_messages to authenticated;

create policy chat_messages_truecaller_jwt_select_participant
  on public.chat_messages
  for select to authenticated
  using (
    public.vibesync_jwt_session_active()
    and (
      sender_id = public.vibesync_jwt_profile_id()
      or receiver_id = public.vibesync_jwt_profile_id()
    )
  );

create policy chat_messages_truecaller_jwt_insert_sender
  on public.chat_messages
  for insert to authenticated
  with check (
    public.vibesync_jwt_session_active()
    and sender_id = public.vibesync_jwt_profile_id()
    and coalesce(receiver_id, '') <> ''
    and is_encrypted is true
    and coalesce(ciphertext, '') <> ''
  );

create policy chat_messages_truecaller_jwt_update_participant
  on public.chat_messages
  for update to authenticated
  using (
    public.vibesync_jwt_session_active()
    and (
      sender_id = public.vibesync_jwt_profile_id()
      or receiver_id = public.vibesync_jwt_profile_id()
    )
  )
  with check (
    public.vibesync_jwt_session_active()
    and (
      sender_id = public.vibesync_jwt_profile_id()
      or receiver_id = public.vibesync_jwt_profile_id()
    )
  );

create policy chat_messages_truecaller_jwt_delete_participant
  on public.chat_messages
  for delete to authenticated
  using (
    public.vibesync_jwt_session_active()
    and (
      sender_id = public.vibesync_jwt_profile_id()
      or receiver_id = public.vibesync_jwt_profile_id()
    )
  );

-- Compatibility for the app's legacy x-vibesync-session header path. No writes are granted
-- to anon; all mutating chat operations use the session-validated RPCs.
create policy chat_messages_legacy_session_select_participant
  on public.chat_messages
  for select to anon
  using (
    public.vibesync_current_profile_id() is not null
    and (
      sender_id = public.vibesync_current_profile_id()
      or receiver_id = public.vibesync_current_profile_id()
    )
  );

notify pgrst, 'reload schema';
commit;
