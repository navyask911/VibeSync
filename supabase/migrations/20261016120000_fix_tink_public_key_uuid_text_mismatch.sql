BEGIN;

-- Keep the public key table's publication fields on the legacy text profile identity.
-- Older deployments may have created this table before the UUID-v5 identity-map rollout.
CREATE TABLE IF NOT EXISTS public.user_public_keys (
  profile_id text PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE,
  auth_uid uuid UNIQUE,
  public_key text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.user_public_keys
  ADD COLUMN IF NOT EXISTS public_key text NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE public.user_public_keys ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.user_public_keys FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE public.user_public_keys TO authenticated;

DROP POLICY IF EXISTS user_public_keys_authenticated_read ON public.user_public_keys;
CREATE POLICY user_public_keys_authenticated_read
  ON public.user_public_keys
  FOR SELECT
  TO authenticated
  USING (true);

-- Recreate rather than replacing the function so its RPC signature and privileges are
-- unambiguous. IDs are normalized to text at comparisons; auth UUIDs are compared as text
-- only at the identity-map boundary. Publication intentionally does not assign to auth_uid:
-- that column's historical type has varied between deployments, and the public-key resolver
-- is keyed by the canonical text profile ID.
DROP FUNCTION IF EXISTS public.publish_my_tink_public_key(text);

CREATE FUNCTION public.publish_my_tink_public_key(p_public_key text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $function$
DECLARE
  owner_id text := public.vibesync_jwt_profile_id()::text;
  caller_uid text := (SELECT auth.uid())::text;
  key_doc jsonb;
BEGIN
  IF NOT public.vibesync_jwt_session_active()
     OR owner_id IS NULL
     OR btrim(owner_id) = ''
     OR caller_uid IS NULL
  THEN
    RAISE EXCEPTION 'verified_session_required' USING ERRCODE = '42501';
  END IF;

  -- Require the signed UUID subject and legacy text profile ID to resolve to the same row.
  IF NOT EXISTS (
    SELECT 1
      FROM public.vibesync_auth_identity_map AS m
     WHERE m.profile_id::text = owner_id::text
       AND m.auth_uid::text = caller_uid::text
  ) THEN
    RAISE EXCEPTION 'verified_session_required' USING ERRCODE = '42501';
  END IF;

  IF p_public_key IS NULL
     OR length(btrim(p_public_key)) < 40
     OR length(p_public_key) > 100000
  THEN
    RAISE EXCEPTION 'invalid_tink_public_key' USING ERRCODE = '22023';
  END IF;

  BEGIN
    key_doc := btrim(p_public_key)::jsonb;
  EXCEPTION WHEN others THEN
    RAISE EXCEPTION 'invalid_tink_public_key_json' USING ERRCODE = '22023';
  END;

  IF jsonb_typeof(key_doc) <> 'object'
     OR jsonb_typeof(key_doc -> 'key') <> 'array'
  THEN
    RAISE EXCEPTION 'invalid_tink_public_keyset' USING ERRCODE = '22023';
  END IF;

  IF EXISTS (
    SELECT 1
      FROM jsonb_array_elements(key_doc -> 'key') AS key_item
     WHERE upper(coalesce(key_item -> 'keyData' ->> 'keyMaterialType', ''))
       IN ('SYMMETRIC', 'ASYMMETRIC_PRIVATE')
  ) THEN
    RAISE EXCEPTION 'private_key_material_not_allowed' USING ERRCODE = '22023';
  END IF;

  UPDATE public.profiles AS p
     SET public_key = btrim(p_public_key),
         public_identity_key = btrim(p_public_key),
         updated_at = now()
   WHERE p.id::text = owner_id::text;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'owner_profile_not_found' USING ERRCODE = 'P0002';
  END IF;

  INSERT INTO public.user_public_keys(profile_id, public_key, updated_at)
  VALUES (owner_id::text, btrim(p_public_key), now())
  ON CONFLICT (profile_id) DO UPDATE
    SET public_key = EXCLUDED.public_key,
        updated_at = EXCLUDED.updated_at;

  RETURN jsonb_build_object('status', 'ok');
END;
$function$;

REVOKE ALL ON FUNCTION public.publish_my_tink_public_key(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.publish_my_tink_public_key(text) TO authenticated;

NOTIFY pgrst, 'reload schema';
COMMIT;
