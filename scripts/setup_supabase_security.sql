-- VibeSync Supabase security baseline.
-- Review against the live project's actual schema before applying.
-- This file deliberately does not embed service-role/secret credentials.

BEGIN;

-- ---------------------------------------------------------------------------
-- FCM device registry
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.device_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    fcm_token text NOT NULL,
    platform text NOT NULL DEFAULT 'android',
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT device_tokens_platform_check CHECK (platform IN ('android')),
    CONSTRAINT device_tokens_unique_token UNIQUE (user_id, fcm_token)
);

CREATE INDEX IF NOT EXISTS idx_device_tokens_user_id
    ON public.device_tokens(user_id);

ALTER TABLE public.device_tokens ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "device_tokens_select_own" ON public.device_tokens;
CREATE POLICY "device_tokens_select_own"
    ON public.device_tokens
    FOR SELECT
    TO authenticated
    USING ((SELECT auth.uid()) = user_id);

DROP POLICY IF EXISTS "device_tokens_insert_own" ON public.device_tokens;
CREATE POLICY "device_tokens_insert_own"
    ON public.device_tokens
    FOR INSERT
    TO authenticated
    WITH CHECK ((SELECT auth.uid()) = user_id);

DROP POLICY IF EXISTS "device_tokens_update_own" ON public.device_tokens;
CREATE POLICY "device_tokens_update_own"
    ON public.device_tokens
    FOR UPDATE
    TO authenticated
    USING ((SELECT auth.uid()) = user_id)
    WITH CHECK ((SELECT auth.uid()) = user_id);

DROP POLICY IF EXISTS "device_tokens_delete_own" ON public.device_tokens;
CREATE POLICY "device_tokens_delete_own"
    ON public.device_tokens
    FOR DELETE
    TO authenticated
    USING ((SELECT auth.uid()) = user_id);

REVOKE ALL ON public.device_tokens FROM anon;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.device_tokens TO authenticated;

-- ---------------------------------------------------------------------------
-- Privacy-preserving contact index
--
-- NOTE: This keeps the existing TEXT user_id shape for compatibility with
-- historical VibeSync profile identifiers. A future identity migration should
-- move this relationship to auth.users(id) UUID.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.phone_sync (
    user_id text NOT NULL,
    phone_hash bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, phone_hash)
);

CREATE INDEX IF NOT EXISTS idx_phone_sync_phone_hash
    ON public.phone_sync USING btree (phone_hash);

ALTER TABLE public.phone_sync ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "phone_sync_no_client_select" ON public.phone_sync;
-- Intentionally no SELECT policy. Contact matching is server-side through RPC.

DROP POLICY IF EXISTS "phone_sync_insert_own" ON public.phone_sync;
CREATE POLICY "phone_sync_insert_own"
    ON public.phone_sync
    FOR INSERT
    TO authenticated
    WITH CHECK (
        EXISTS (
            SELECT 1
            FROM public.profiles p
            WHERE p.id::text = phone_sync.user_id
              AND COALESCE(
                    NULLIF(p.auth_user_id::text, ''),
                    NULLIF(p.user_id::text, '')
                  ) = (SELECT auth.uid())::text
        )
    );

DROP POLICY IF EXISTS "phone_sync_delete_own" ON public.phone_sync;
CREATE POLICY "phone_sync_delete_own"
    ON public.phone_sync
    FOR DELETE
    TO authenticated
    USING (
        EXISTS (
            SELECT 1
            FROM public.profiles p
            WHERE p.id::text = phone_sync.user_id
              AND COALESCE(
                    NULLIF(p.auth_user_id::text, ''),
                    NULLIF(p.user_id::text, '')
                  ) = (SELECT auth.uid())::text
        )
    );

REVOKE ALL ON public.phone_sync FROM anon;
GRANT INSERT, DELETE ON public.phone_sync TO authenticated;

-- ---------------------------------------------------------------------------
-- Private contact matching RPC
--
-- Do not expose phone_sync as a table query. The client sends only transformed
-- lookup values and receives minimal matches.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.match_contacts(hashes text[])
RETURNS TABLE (
    id text,
    name text,
    profile_photo_url text,
    avatar_emoji text,
    phone_hash text
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF (SELECT auth.uid()) IS NULL THEN
        RAISE EXCEPTION 'authentication required';
    END IF;

    RETURN QUERY
    SELECT
        p.id::text,
        p.name::text,
        COALESCE(p.avatar_url, '')::text,
        COALESCE(p.avatar_emoji, '✨')::text,
        encode(ps.phone_hash, 'hex')::text
    FROM public.phone_sync ps
    JOIN public.profiles p ON p.id::text = ps.user_id
    WHERE ps.phone_hash = ANY (
        SELECT decode(h, 'hex')
        FROM unnest(hashes) AS h
        WHERE h ~ '^[0-9a-fA-F]{64}$'
    )
      AND COALESCE(p.is_deleted, false) = false
    LIMIT 50;
END;
$$;

REVOKE ALL ON FUNCTION public.match_contacts(text[]) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.match_contacts(text[]) FROM anon;
GRANT EXECUTE ON FUNCTION public.match_contacts(text[]) TO authenticated;

COMMIT;
