BEGIN;

-- The phone-hash contact pipeline was retired and public.profiles.phone_hash is gone.
-- Do not dereference NEW.phone_hash: the trigger fires on INSERT/UPDATE and would abort
-- unrelated profile syncs with SQLSTATE 42703.
CREATE OR REPLACE FUNCTION public.protect_profile_fields()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $function$
BEGIN
  IF auth.role() = 'authenticated' AND current_user = 'authenticated' THEN
    IF TG_OP = 'INSERT' THEN
      NEW.auth_user_id := auth.uid();
      NEW.is_verified := false;
      NEW.is_deleted := false;
      NEW.account_status := 'ACTIVE';
    ELSE
      NEW.auth_user_id := OLD.auth_user_id;
      NEW.is_verified := OLD.is_verified;
      NEW.is_deleted := OLD.is_deleted;
      NEW.account_status := OLD.account_status;
    END IF;
    NEW.phone_number := NULL;
    NEW.phone_number_e164 := NULL;
    NEW.clean_phone := NULL;
  END IF;
  RETURN NEW;
END;
$function$;

-- The old blind phone-hash endpoint belongs to the retired contact pipeline and must not
-- remain callable as SECURITY DEFINER from the exposed public schema.
DROP FUNCTION IF EXISTS public.match_contacts(text[]);

-- Public Tink key material is readable to authenticated members, but write access is
-- deliberately kept behind the validating, session-bound publish RPC.
ALTER TABLE public.user_public_keys ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.user_public_keys FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE public.user_public_keys TO authenticated;

DROP POLICY IF EXISTS user_public_keys_authenticated_read ON public.user_public_keys;
CREATE POLICY user_public_keys_authenticated_read
  ON public.user_public_keys
  FOR SELECT
  TO authenticated
  USING (true);

-- The live RPCs had drifted to SECURITY INVOKER. These narrowly scoped routines validate
-- the verified VibeSync session and derive the caller's profile on the server, so they can
-- safely write/read the dual-store key records without granting clients direct write access.
ALTER FUNCTION public.publish_my_tink_public_key(text) SECURITY DEFINER;
ALTER FUNCTION public.publish_my_tink_public_key(text) SET search_path = pg_catalog, public;
REVOKE ALL ON FUNCTION public.publish_my_tink_public_key(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.publish_my_tink_public_key(text) TO authenticated;

ALTER FUNCTION public.resolve_public_tink_key(text, text) SECURITY DEFINER;
ALTER FUNCTION public.resolve_public_tink_key(text, text) SET search_path = pg_catalog, public;
REVOKE ALL ON FUNCTION public.resolve_public_tink_key(text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.resolve_public_tink_key(text, text) TO authenticated;

-- The owner policy constrains token rows to the active VibeSync profile. Give the role
-- the table privileges required by that policy; row-level security remains the boundary.
ALTER TABLE public.device_tokens ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS device_tokens_owner_access ON public.device_tokens;
CREATE POLICY device_tokens_owner_access
  ON public.device_tokens
  FOR ALL
  TO authenticated
  USING (profile_id = public.vibesync_current_profile_id())
  WITH CHECK (profile_id = public.vibesync_current_profile_id());

GRANT SELECT, INSERT, UPDATE ON TABLE public.device_tokens TO authenticated;
REVOKE INSERT, UPDATE, DELETE ON TABLE public.device_tokens FROM PUBLIC, anon;

-- PostgREST does not permit CREATE OR REPLACE to rename an existing input parameter.
-- Drop the old signature first so Android's { "p_fcm_token": "..." } body matches exactly.
DROP FUNCTION IF EXISTS public.register_fcm_token(text);

CREATE FUNCTION public.register_fcm_token(p_fcm_token text)
RETURNS void
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $function$
BEGIN
  IF p_fcm_token IS NULL OR length(btrim(p_fcm_token)) < 20 THEN
    RAISE EXCEPTION 'invalid fcm token' USING ERRCODE = '22023';
  END IF;

  IF coalesce(public.register_device_token(btrim(p_fcm_token), 'android'), false) IS NOT TRUE THEN
    RAISE EXCEPTION 'FCM token registration failed';
  END IF;
END;
$function$;

REVOKE ALL ON FUNCTION public.register_fcm_token(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.register_fcm_token(text) TO authenticated;
REVOKE ALL ON FUNCTION public.register_device_token(text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.register_device_token(text, text) TO authenticated;

NOTIFY pgrst, 'reload schema';
COMMIT;
