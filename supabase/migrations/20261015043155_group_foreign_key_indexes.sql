-- Cover the active recipient-membership FK and sender-profile FK for group timeline rows.
begin;
create index if not exists idx_chat_group_envelopes_group_recipient
  on public.chat_group_message_envelopes(group_id, recipient_profile_id);
create index if not exists idx_chat_group_messages_sender_profile
  on public.chat_group_messages(sender_profile_id);
commit;
