-- Allow signed VibeSync custom JWTs (PostgREST role=authenticated) to reach the
-- contact matching entry point. The helper still validates the active VibeSync session,
-- so this does not grant access to the raw phone index or bypass session verification.
BEGIN;

GRANT USAGE ON SCHEMA private TO authenticated;
GRANT EXECUTE ON FUNCTION private.match_contacts_by_phone_numbers_secure(text[]) TO authenticated;
GRANT EXECUTE ON FUNCTION public.match_contacts_by_phone_numbers(text[]) TO authenticated;

NOTIFY pgrst, 'reload schema';
COMMIT;
