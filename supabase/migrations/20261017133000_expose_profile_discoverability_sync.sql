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
  -- Preserve the existing view's privacy-aware approximate coordinates. Never expose raw
  -- coordinates for a profile whose location_visibility is not EVERYONE.
  CASE
    WHEN location_visibility = 'EVERYONE'
      AND latitude BETWEEN -90 AND 90
      AND longitude BETWEEN -180 AND 180
      AND NOT (latitude = 0 AND longitude = 0)
    THEN round(latitude::numeric, 3)::double precision
    ELSE NULL
  END AS latitude,
  CASE
    WHEN location_visibility = 'EVERYONE'
      AND latitude BETWEEN -90 AND 90
      AND longitude BETWEEN -180 AND 180
      AND NOT (latitude = 0 AND longitude = 0)
    THEN round(longitude::numeric, 3)::double precision
    ELSE NULL
  END AS longitude,
  -- Keep the directory metadata the Android client already selects.
  subscription_status,
  badge_purchased,
  is_discoverable
FROM public.profiles
WHERE coalesce(is_deleted, false) = false
  AND coalesce(account_status, 'ACTIVE') = 'ACTIVE';

REVOKE ALL ON public.profiles FROM anon;
GRANT SELECT ON public.vibesync_profiles_public TO anon;

NOTIFY pgrst, 'reload schema';
COMMIT;
