-- VibeSync does not use Supabase Auth sessions. Keep the hardened RPC surface
-- callable only through the publishable anon role plus x-vibesync-session.
revoke execute on function public.vibesync_current_profile_id() from authenticated;
revoke execute on function public.upsert_my_profile(jsonb) from authenticated;
revoke execute on function public.match_contacts(text[]) from authenticated;
revoke execute on function public.send_encrypted_message(text,text,text,text,text,text,integer) from authenticated;
revoke execute on function public.ack_message_delivery(text) from authenticated;
revoke execute on function public.ack_message_read(text) from authenticated;
revoke execute on function public.register_device_token(text,text) from authenticated;
revoke execute on function public.delete_encrypted_message(text,text) from authenticated;
revoke execute on function public.get_public_identity_key_by_phone_hash(text) from authenticated;

grant execute on function public.vibesync_current_profile_id() to anon;
grant execute on function public.upsert_my_profile(jsonb) to anon;
grant execute on function public.match_contacts(text[]) to anon;
grant execute on function public.send_encrypted_message(text,text,text,text,text,text,integer) to anon;
grant execute on function public.ack_message_delivery(text) to anon;
grant execute on function public.ack_message_read(text) to anon;
grant execute on function public.register_device_token(text,text) to anon;
grant execute on function public.delete_encrypted_message(text,text) to anon;
grant execute on function public.get_public_identity_key_by_phone_hash(text) to anon;