-- Expose only non-sensitive profile fields to anon-session clients.
create or replace view public.vibesync_profiles_public
with (security_invoker = true)
as
select
  id,
  name,
  bio,
  age,
  gender,
  city,
  country,
  country_flag,
  place,
  occupation,
  qualification,
  avatar_emoji,
  avatar_url,
  marital_status,
  account_status,
  is_verified,
  is_deleted,
  updated_at,
  public_identity_key
from public.profiles;

revoke all on public.profiles from anon;
grant select on public.vibesync_profiles_public to anon;