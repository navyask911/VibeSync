-- Make the public-profile view security-invoker safe.
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
from public.profiles
where coalesce(is_deleted,false)=false
  and coalesce(account_status,'ACTIVE')='ACTIVE';

revoke all on public.profiles from anon;
grant select (
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
) on public.profiles to anon;

grant select on public.vibesync_profiles_public to anon;