BEGIN;

-- Preserve the current public-directory view, including its explicit RLS behavior,
-- paid/utility visibility filter, privacy-aware rounded coordinates, and column order.
-- is_discoverable is appended so CREATE OR REPLACE VIEW remains backward compatible.
GRANT SELECT (is_discoverable) ON TABLE public.profiles TO anon;

CREATE OR REPLACE VIEW public.vibesync_profiles_public
WITH (security_invoker = true) AS
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
  CASE
    WHEN location_visibility = 'EVERYONE'::text
      AND latitude >= -90::double precision
      AND latitude <= 90::double precision
      AND longitude >= -180::double precision
      AND longitude <= 180::double precision
      AND NOT (latitude = 0::double precision AND longitude = 0::double precision)
    THEN round(latitude::numeric, 3)::double precision
    ELSE NULL::double precision
  END AS latitude,
  CASE
    WHEN location_visibility = 'EVERYONE'::text
      AND latitude >= -90::double precision
      AND latitude <= 90::double precision
      AND longitude >= -180::double precision
      AND longitude <= 180::double precision
      AND NOT (latitude = 0::double precision AND longitude = 0::double precision)
    THEN round(longitude::numeric, 3)::double precision
    ELSE NULL::double precision
  END AS longitude,
  subscription_status,
  badge_purchased,
  is_discoverable
FROM public.profiles
WHERE COALESCE(is_deleted, false) = false
  AND COALESCE(account_status, 'ACTIVE'::text) = 'ACTIVE'::text
  AND (
    subscription_status = 'active'::text
    AND subscription_expires_at > now()
    AND tier_badge = ANY (ARRAY['crown_gold'::text, 'crown_silver'::text, 'crown_blue'::text])
    OR is_public_utility = true
      AND tier_badge = 'gray'::text
  );

GRANT SELECT ON public.vibesync_profiles_public TO anon;
NOTIFY pgrst, 'reload schema';
COMMIT;
