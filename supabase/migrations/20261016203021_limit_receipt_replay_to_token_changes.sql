begin;

-- Token registration RPCs may update updated_at on every app launch. Receipt history should
-- be replayed only when the delivery address or owning profile changes, not on timestamp-only
-- or unrelated device metadata updates.
drop trigger if exists trg_device_token_receipt_replay_fcm_edge on public.device_tokens;
create trigger trg_device_token_receipt_replay_fcm_edge
after insert or update of fcm_token, profile_id on public.device_tokens
for each row execute function public.forward_recent_chat_receipts_to_fcm_edge();

commit;
