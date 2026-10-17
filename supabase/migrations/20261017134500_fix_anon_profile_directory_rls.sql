BEGIN;

-- Keep JWT/session-dependent profile access policies available only to authenticated clients.
-- Anonymous directory access gets the same public visibility predicate without calling
-- authenticated-session helper functions from an anon execution context.
ALTER POLICY profiles_public_directory_paywall
  ON public.profiles
  TO authenticated;

DROP POLICY IF EXISTS profiles_public_directory_anon ON public.profiles;
CREATE POLICY profiles_public_directory_anon
  ON public.profiles
  FOR SELECT
  TO anon
  USING (
    NOT COALESCE(is_deleted, false)
    AND COALESCE(account_status, 'ACTIVE'::text) = 'ACTIVE'::text
    AND (
      (
        subscription_status = 'active'::text
        AND subscription_expires_at > now()
        AND tier_badge = ANY (ARRAY['crown_gold'::text, 'crown_silver'::text, 'crown_blue'::text])
      )
      OR (
        is_public_utility = true
        AND tier_badge = 'gray'::text
      )
    )
  );

NOTIFY pgrst, 'reload schema';
COMMIT;
