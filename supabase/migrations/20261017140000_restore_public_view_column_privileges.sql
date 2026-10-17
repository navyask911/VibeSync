BEGIN;

-- The public profile view is SECURITY INVOKER. Restore only the public-directory columns
-- its projection and row-level visibility predicate need; never grant table-wide SELECT.
REVOKE SELECT ON TABLE public.profiles FROM anon;
GRANT SELECT (
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
  location_visibility,
  latitude,
  longitude,
  tier_badge,
  subscription_status,
  subscription_expires_at,
  is_public_utility,
  badge_purchased,
  is_discoverable
) ON TABLE public.profiles TO anon;

GRANT SELECT ON public.vibesync_profiles_public TO anon;
NOTIFY pgrst, 'reload schema';
COMMIT;
