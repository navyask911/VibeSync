-- Fix the partial unique-index conflict target used by the VibeSync
-- custom-session device token registry.

create or replace function public.register_device_token(
  p_fcm_token text,
  p_platform text default 'android'
)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  current_profile_id text := public.vibesync_current_profile_id();
begin
  if current_profile_id is null then
    raise exception 'vibesync session required';
  end if;
  if p_fcm_token is null or length(trim(p_fcm_token)) < 20 then
    raise exception 'invalid fcm token';
  end if;

  insert into public.device_tokens(
    profile_id,
    user_id,
    fcm_token,
    platform,
    updated_at,
    created_at
  )
  values (
    current_profile_id,
    null,
    trim(p_fcm_token),
    coalesce(nullif(p_platform, ''), 'android'),
    now(),
    now()
  )
  on conflict (profile_id, fcm_token) where profile_id is not null do update
    set platform = excluded.platform,
        updated_at = now();

  return true;
end;
$$;

revoke all on function public.register_device_token(text, text) from public;
revoke execute on function public.register_device_token(text, text) from authenticated;
grant execute on function public.register_device_token(text, text) to anon;
