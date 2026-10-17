BEGIN;

-- A profile timestamp is stored as epoch milliseconds (bigint), while the key registry
-- timestamp is timestamptz. Keep those two physical types explicit in the RPC.
ALTER TABLE public.user_public_keys
  ADD COLUMN IF NOT EXISTS key_id text;

-- Remove every existing overload so PostgREST can resolve exactly one publish RPC.
DO $drop$
DECLARE
  r record;
BEGIN
  FOR r IN
    SELECT p.oid::regprocedure AS signature
      FROM pg_proc AS p
     WHERE p.proname = 'publish_my_tink_public_key'
       AND p.pronamespace = 'public'::regnamespace
  LOOP
    EXECUTE 'DROP FUNCTION ' || r.signature;
  END LOOP;
END;
$drop$;

CREATE FUNCTION public.publish_my_tink_public_key(
  p_public_key text,
  p_key_id text DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $function$
DECLARE
  caller_uid uuid := auth.uid();
  owner_id text;
  key_doc jsonb;
  declared_primary_id text;
  effective_key_id text;
  profile_updated_at_ms bigint;
BEGIN
  IF caller_uid IS NULL THEN
    RAISE EXCEPTION 'authenticated_user_required' USING ERRCODE = '42501';
  END IF;

  IF NOT public.vibesync_jwt_session_active() THEN
    RAISE EXCEPTION 'verified_session_required' USING ERRCODE = '42501';
  END IF;

  owner_id := public.vibesync_jwt_profile_id();
  IF owner_id IS NULL OR btrim(owner_id) = '' THEN
    RAISE EXCEPTION 'verified_session_required' USING ERRCODE = '42501';
  END IF;

  -- Keep UUID values UUID-typed and the legacy application profile ID text-typed.
  IF NOT EXISTS (
    SELECT 1
      FROM public.vibesync_auth_identity_map AS m
     WHERE m.auth_uid = caller_uid
       AND m.profile_id = owner_id
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
     OR jsonb_array_length(key_doc -> 'key') = 0
  THEN
    RAISE EXCEPTION 'invalid_tink_public_keyset' USING ERRCODE = '22023';
  END IF;

  -- This endpoint accepts public asymmetric keysets only. Never store private or symmetric key
  -- material, even if a client bypasses its local Tink public-key validation.
  IF EXISTS (
    SELECT 1
      FROM jsonb_array_elements(key_doc -> 'key') AS key_item
     WHERE upper(coalesce(key_item -> 'keyData' ->> 'keyMaterialType', '')) <> 'ASYMMETRIC_PUBLIC'
  ) THEN
    RAISE EXCEPTION 'public_keyset_must_contain_only_asymmetric_public_keys' USING ERRCODE = '22023';
  END IF;

  declared_primary_id := nullif(btrim(key_doc ->> 'primaryKeyId'), '');
  effective_key_id := coalesce(nullif(btrim(p_key_id), ''), declared_primary_id);

  IF effective_key_id IS NULL
     OR (declared_primary_id IS NOT NULL AND effective_key_id <> declared_primary_id)
     OR NOT EXISTS (
       SELECT 1
         FROM jsonb_array_elements(key_doc -> 'key') AS key_item
        WHERE key_item ->> 'keyId' = effective_key_id
          AND upper(coalesce(key_item ->> 'status', '')) = 'ENABLED'
     )
  THEN
    RAISE EXCEPTION 'invalid_tink_primary_key_id' USING ERRCODE = '22023';
  END IF;

  -- public.profiles.updated_at is bigint epoch milliseconds, not timestamptz.
  profile_updated_at_ms := (extract(epoch FROM clock_timestamp()) * 1000)::bigint;

  UPDATE public.profiles AS p
     SET public_key = btrim(p_public_key),
         public_identity_key = btrim(p_public_key),
         updated_at = profile_updated_at_ms
   WHERE p.id = owner_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'owner_profile_not_found' USING ERRCODE = 'P0002';
  END IF;

  -- public.user_public_keys.auth_uid is uuid, profile_id is text, and updated_at is timestamptz.
  INSERT INTO public.user_public_keys (
    profile_id, auth_uid, public_key, key_id, updated_at
  )
  VALUES (
    owner_id, caller_uid, btrim(p_public_key), effective_key_id, clock_timestamp()
  )
  ON CONFLICT (profile_id) DO UPDATE
    SET auth_uid = EXCLUDED.auth_uid,
        public_key = EXCLUDED.public_key,
        key_id = EXCLUDED.key_id,
        updated_at = EXCLUDED.updated_at;

  RETURN jsonb_build_object('status', 'ok');
END;
$function$;

REVOKE ALL ON FUNCTION public.publish_my_tink_public_key(text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.publish_my_tink_public_key(text, text) TO authenticated;

NOTIFY pgrst, 'reload schema';
COMMIT;
