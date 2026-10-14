-- Force encrypted message creates through the pre-database rate-limited Edge gateway.
-- The RPC continues validating the active VibeSync session header and payload server-side.
begin;

revoke all on function public.send_encrypted_message(text, text, text, text, text, text, integer)
  from public, anon, authenticated;
grant execute on function public.send_encrypted_message(text, text, text, text, text, text, integer)
  to service_role;

notify pgrst, 'reload schema';
commit;
