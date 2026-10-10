-- Re-registering an existing token uses ON CONFLICT DO UPDATE; replay pending
-- messages after both new-token inserts and token refreshes.
drop trigger if exists trg_device_token_pending_fcm_replay on public.device_tokens;
create trigger trg_device_token_pending_fcm_replay
after insert or update on public.device_tokens
for each row
when (coalesce(new.profile_id, '') <> '')
execute function public.forward_pending_chat_rows_to_fcm_edge();
