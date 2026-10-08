-- Fix the custom-session FCM registration RPC to use the hardened
-- vibesync_current_profile_id() / register_device_token() path.
-- No Supabase Auth JWT is required for VibeSync device token registration.

create or replace function public.register_fcm_token(p_fcm_token text)
returns void
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
  if public.register_device_token(trim(p_fcm_token), 'android') <> true then
    raise exception 'FCM token registration failed';
  end if;
end;
$$;

revoke all on function public.register_fcm_token(text) from public;
revoke execute on function public.register_fcm_token(text) from authenticated;
grant execute on function public.register_fcm_token(text) to anon;
