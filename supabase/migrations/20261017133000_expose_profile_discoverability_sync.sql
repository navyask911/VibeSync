BEGIN;

-- Keep profile discoverability explicit in client sync while exposing no additional sensitive fields.
-- Preserve the existing active-profile and non-deleted filters.
CREATE OR REPLACE VIEW public.vibesync_profiles_public AS
SELECT
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
  public_identity_key,
  is_discoverable
FROM public.profiles
WHERE coalesce(is_deleted, false) = false
  AND coalesce(account_status, 'ACTIVE') = 'ACTIVE';

REVOKE ALL ON public.profiles FROM anon;
GRANT SELECT ON public.vibesync_profiles_public TO anon;

NOTIFY pgrst, 'reload schema';
COMMIT;
