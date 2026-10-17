-- Targeted B-tree indexes for high-volume identity, contact, wallet and directory lookups.
-- Keep this migration safe across the current production schema and compatible variants:
--   * production identity map uses auth_uid (already indexed by its primary key);
--     some installations may use auth_uuid instead.
--   * production business wallet rows are keyed by business_id in business_wallets;
--     some compatible schemas may instead expose wallets.owner_id.
-- All statements are idempotent to support the reconciled migration pipeline.

DO $migration$
BEGIN
  IF to_regclass('public.vibesync_auth_identity_map') IS NOT NULL THEN
    IF EXISTS (
      SELECT 1
      FROM information_schema.columns
      WHERE table_schema = 'public'
        AND table_name = 'vibesync_auth_identity_map'
        AND column_name = 'auth_uuid'
    ) THEN
      EXECUTE
        'CREATE INDEX IF NOT EXISTS idx_vibesync_auth_identity_map_uuid
           ON public.vibesync_auth_identity_map (auth_uuid)';
    ELSIF EXISTS (
      SELECT 1
      FROM information_schema.columns
      WHERE table_schema = 'public'
        AND table_name = 'vibesync_auth_identity_map'
        AND column_name = 'auth_uid'
    ) AND NOT EXISTS (
      SELECT 1
      FROM pg_index i
      WHERE i.indrelid = 'public.vibesync_auth_identity_map'::regclass
        AND i.indisvalid
        AND position('(auth_uid)' IN pg_get_indexdef(i.indexrelid)) > 0
    ) THEN
      -- The current production schema calls this auth_uid. Only create an
      -- index if no valid index already serves the UUID lookup.
      EXECUTE
        'CREATE INDEX IF NOT EXISTS idx_vibesync_auth_identity_map_uuid
           ON public.vibesync_auth_identity_map (auth_uid)';
    END IF;
  END IF;
END
$migration$;

-- This index already exists in production; IF NOT EXISTS keeps clean installs
-- and drift-reconciled deployments safe and makes the intended access path explicit.
CREATE INDEX IF NOT EXISTS idx_profiles_phone
  ON public.profiles (phone_number);

DO $migration$
BEGIN
  IF to_regclass('public.wallets') IS NOT NULL
     AND EXISTS (
       SELECT 1
       FROM information_schema.columns
       WHERE table_schema = 'public'
         AND table_name = 'wallets'
         AND column_name = 'owner_id'
     ) THEN
    EXECUTE
      'CREATE INDEX IF NOT EXISTS idx_wallets_owner
         ON public.wallets (owner_id)';
  ELSIF to_regclass('public.business_wallets') IS NOT NULL THEN
    -- Production uses business_wallets(business_id), whose primary key already
    -- provides a B-tree lookup index. Avoid a redundant duplicate index.
    NULL;
  END IF;
END
$migration$;

-- The existing directory index also covers is_published, but includes both
-- published and unpublished rows. This partial index is smaller for public reads.
CREATE INDEX IF NOT EXISTS idx_businesses_published
  ON public.businesses (is_published)
  WHERE is_published = true;
