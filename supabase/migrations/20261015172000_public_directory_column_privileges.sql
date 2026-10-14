-- Expose only the columns needed by the public directory views to anonymous clients.
-- Row-Level Security continues to decide which paid/utility rows can be seen.
BEGIN;

REVOKE SELECT ON TABLE public.profiles FROM PUBLIC, anon;
REVOKE SELECT ON TABLE public.businesses FROM PUBLIC, anon;

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
  is_public_utility
) ON TABLE public.profiles TO anon;

GRANT SELECT (
  id,
  owner_profile_id,
  name,
  tagline,
  category,
  description,
  address,
  city,
  latitude,
  longitude,
  rating,
  review_count,
  banner_url,
  logo_emoji,
  phone_number,
  website_url,
  social_handle,
  is_verified,
  follower_count,
  active_offer_summary,
  verification_tier,
  tier_badge,
  subscription_status,
  subscription_expires_at,
  is_public_utility,
  is_published,
  created_at_epoch_ms,
  updated_at
) ON TABLE public.businesses TO anon;

-- Keep the purpose-built views available to anonymous directory browsing.
GRANT SELECT ON public.vibesync_profiles_public TO anon;
GRANT SELECT ON public.hosted_business_directory TO anon;
GRANT SELECT ON public.hosted_public_directory TO anon;

NOTIFY pgrst, 'reload schema';
COMMIT;
