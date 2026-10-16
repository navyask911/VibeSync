BEGIN;

-- Live schema verified on the linked project:
--   profiles.updated_at           bigint (epoch milliseconds)
--   profiles.public_key           text
--   profiles.public_identity_key  text
--   user_public_keys.profile_id   text
--   user_public_keys.auth_uid     uuid
--   user_public_keys.public_key   text
--   user_public_keys.key_id       text
--   user_public_keys.updated_at   timestamptz
ALTER TABLE public.user_public_keys
  ADD COLUMN IF NOT EXISTS key_id text;

-- PostgREST must have exactly one unambiguous function with this name.
DO $drop$
DECLARE
  r record;
BEGIN
  FOR r IN
    SELECT p.oid::regprocedure AS sig
      FROM pg_proc AS p
     WHERE p.proname = 'publish_my_tink_public_key'
       AND p.pronamespace = 'public'::regnamespace
  LOOP
    EXECUTE 'DROP FUNCTION ' || r.sig;
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
SET search_path = public, pg_temp
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

  -- Preserve each identity column's actual type at the boundary: UUID to UUID,
  -- legacy profile ID text to text.
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

  -- Public-key publication must not accept symmetric or private key material.
  IF EXISTS (
    SELECT 1
      FROM jsonb_array_elements(key_doc -> 'key') AS key_item
     WHERE upper(coalesce(key_item -> 'keyData' ->> 'keyMaterialType', ''))
       <> 'ASYMMETRIC_PUBLIC'
  ) THEN
    RAISE EXCEPTION 'public_keyset_must_contain_only_asymmetric_public_keys'
      USING ERRCODE = '22023';
  END IF;

  declared_primary_id := nullif(btrim(key_doc ->> 'primaryKeyId'), '');
  effective_key_id := coalesce(nullif(btrim(p_key_id), ''), declared_primary_id);

  IF declared_primary_id IS NULL
     OR effective_key_id IS NULL
     OR effective_key_id <> declared_primary_id
     OR NOT EXISTS (
       SELECT 1
         FROM jsonb_array_elements(key_doc -> 'key') AS key_item
        WHERE key_item ->> 'keyId' = effective_key_id
          AND upper(coalesce(key_item ->> 'status', '')) = 'ENABLED'
     )
  THEN
    RAISE EXCEPTION 'invalid_tink_primary_key_id' USING ERRCODE = '22023';
  END IF;

  -- profiles.updated_at is bigint epoch milliseconds. Do not assign now() (timestamptz).
  profile_updated_at_ms := (extract(epoch FROM clock_timestamp()) * 1000)::bigint;

  UPDATE public.profiles AS p
     SET public_key = btrim(p_public_key),
         public_identity_key = btrim(p_public_key),
         updated_at = profile_updated_at_ms
   WHERE p.id = owner_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'owner_profile_not_found' USING ERRCODE = 'P0002';
  END IF;

  -- user_public_keys uses text, uuid, text, text and timestamptz respectively.
  INSERT INTO public.user_public_keys (
    profile_id,
    auth_uid,
    public_key,
    key_id,
    updated_at
  )
  VALUES (
    owner_id,
    caller_uid,
    btrim(p_public_key),
    effective_key_id,
    clock_timestamp()
  )
  ON CONFLICT (profile_id) DO UPDATE
    SET auth_uid = EXCLUDED.auth_uid,
        public_key = EXCLUDED.public_key,
        key_id = EXCLUDED.key_id,
        updated_at = EXCLUDED.updated_at;

  RETURN jsonb_build_object('status', 'ok', 'key_id', effective_key_id);
END;
$function$;

REVOKE ALL ON FUNCTION public.publish_my_tink_public_key(text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.publish_my_tink_public_key(text, text) TO authenticated;

COMMIT;
NOTIFY pgrst, 'reload schema';
