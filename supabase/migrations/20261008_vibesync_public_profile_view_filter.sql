-- Keep anon access limited to active, non-deleted public profile fields.
create or replace view public.vibesync_profiles_public as
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
grant select on public.vibesync_profiles_public to anon;