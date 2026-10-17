BEGIN;
CREATE OR REPLACE FUNCTION public.resolve_public_tink_key(
  p_profile_id text,
  p_phone_e164 text DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $function$
DECLARE
  requested_id text := btrim(coalesce(p_profile_id, ''));
  canonical_profile_id text;
  normalized_phone text := btrim(coalesce(p_phone_e164, ''));
  phone_digits text;
  profile_key text;
  active_key_id text;
  key_value text;
  resolved_key_id text;
BEGIN
  IF NOT public.vibesync_jwt_session_active() OR public.vibesync_jwt_profile_id() IS NULL THEN
    RAISE EXCEPTION 'verified_session_required' USING ERRCODE = '42501';
  END IF;
  IF requested_id = '' THEN RETURN jsonb_build_object('ok', false, 'profile_id', '', 'public_key', '', 'key_id', ''); END IF;
  SELECT p.id INTO canonical_profile_id FROM public.profiles p WHERE lower(p.id::text) = lower(requested_id) LIMIT 1;
  IF canonical_profile_id IS NULL AND requested_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
    SELECT m.profile_id INTO canonical_profile_id FROM public.vibesync_auth_identity_map m WHERE m.auth_uid = requested_id::uuid LIMIT 1;
  END IF;
  IF canonical_profile_id IS NULL THEN
    SELECT m.profile_id INTO canonical_profile_id FROM public.vibesync_auth_identity_map m WHERE lower(m.profile_id) = lower(requested_id) LIMIT 1;
  END IF;
  IF canonical_profile_id IS NULL AND normalized_phone <> '' THEN
    phone_digits := regexp_replace(normalized_phone, '[^0-9]', '', 'g');
    IF normalized_phone !~ '^\+[1-9][0-9]{7,14}$' AND length(phone_digits) BETWEEN 8 AND 15 THEN normalized_phone := '+' || phone_digits; END IF;
    IF normalized_phone ~ '^\+[1-9][0-9]{7,14}$' THEN SELECT idx.profile_id INTO canonical_profile_id FROM public.phone_lookup_index idx WHERE idx.phone_e164 = normalized_phone LIMIT 1; END IF;
  END IF;
  IF canonical_profile_id IS NULL THEN RETURN jsonb_build_object('ok', false, 'profile_id', '', 'public_key', '', 'key_id', ''); END IF;
  SELECT coalesce(nullif(p.public_identity_key, ''), nullif(p.public_key, '')) INTO profile_key FROM public.profiles p WHERE p.id = canonical_profile_id LIMIT 1;
  IF profile_key IS NOT NULL THEN
    BEGIN active_key_id := nullif(profile_key::jsonb ->> 'primaryKeyId', '');
    EXCEPTION WHEN others THEN active_key_id := NULL;
    END;
  END IF;
  SELECT k.public_key, k.key_id INTO key_value, resolved_key_id FROM public.user_public_keys k
   WHERE k.profile_id = canonical_profile_id AND (active_key_id IS NULL OR k.key_id = active_key_id)
   ORDER BY k.updated_at DESC LIMIT 1;
  IF key_value IS NULL THEN
    IF EXISTS (SELECT 1 FROM public.user_public_keys k WHERE k.profile_id = canonical_profile_id) THEN
      RETURN jsonb_build_object('ok', false, 'profile_id', canonical_profile_id, 'public_key', '', 'key_id', '');
    END IF;
    key_value := profile_key;
    resolved_key_id := active_key_id;
  END IF;
  RETURN jsonb_build_object('ok', coalesce(key_value, '') <> '', 'profile_id', canonical_profile_id, 'public_key', coalesce(key_value, ''), 'key_id', coalesce(resolved_key_id, ''));
END;
$function$;
REVOKE ALL ON FUNCTION public.resolve_public_tink_key(text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.resolve_public_tink_key(text, text) TO authenticated;
COMMIT;
NOTIFY pgrst, 'reload schema';
