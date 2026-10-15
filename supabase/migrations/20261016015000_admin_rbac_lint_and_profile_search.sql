-- Admin portal follow-up: role-gated profile search and explicit private-table deny policies.
BEGIN;

CREATE INDEX IF NOT EXISTS idx_admin_users_created_by ON public.admin_users(created_by);
CREATE INDEX IF NOT EXISTS idx_admin_profile_flags_updated_by ON public.admin_profile_flags(updated_by);
CREATE INDEX IF NOT EXISTS idx_admin_compliance_exports_requested_by ON public.admin_compliance_exports(requested_by);
CREATE INDEX IF NOT EXISTS idx_admin_broadcast_jobs_initiated_by ON public.admin_broadcast_jobs(initiated_by);

-- These tables are accessed only by narrowly scoped SECURITY DEFINER RPCs or the Edge Function.
-- Explicit deny policies keep them inaccessible through direct browser CRUD even if grants change later.
DROP POLICY IF EXISTS admin_profile_flags_deny_client ON public.admin_profile_flags;
CREATE POLICY admin_profile_flags_deny_client ON public.admin_profile_flags
  FOR ALL TO anon, authenticated USING (false) WITH CHECK (false);
DROP POLICY IF EXISTS admin_compliance_exports_deny_client ON public.admin_compliance_exports;
CREATE POLICY admin_compliance_exports_deny_client ON public.admin_compliance_exports
  FOR ALL TO anon, authenticated USING (false) WITH CHECK (false);
DROP POLICY IF EXISTS admin_broadcast_jobs_deny_client ON public.admin_broadcast_jobs;
CREATE POLICY admin_broadcast_jobs_deny_client ON public.admin_broadcast_jobs
  FOR ALL TO anon, authenticated USING (false) WITH CHECK (false);

CREATE OR REPLACE FUNCTION public.vibesync_admin_search_profiles(p_query text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = 'pg_catalog','public'
AS $function$
DECLARE actor_role text; matched_profiles jsonb;
BEGIN
  SELECT role INTO actor_role
    FROM public.admin_users
   WHERE user_id = auth.uid() AND is_active;
  IF actor_role IS NULL OR actor_role NOT IN ('super_admin','moderator') THEN
    RAISE EXCEPTION 'moderation_role_required' USING ERRCODE = '42501';
  END IF;
  IF p_query IS NULL OR length(trim(p_query)) < 2 OR length(p_query) > 80 THEN
    RAISE EXCEPTION 'profile_search_query_invalid' USING ERRCODE = '22023';
  END IF;

  SELECT coalesce(jsonb_agg(to_jsonb(matches) ORDER BY matches.name, matches.id),'[]'::jsonb)
    INTO matched_profiles
    FROM (
      SELECT id, name, city, country, is_verified, account_status, is_deleted, updated_at
        FROM public.profiles
       WHERE id = trim(p_query)
          OR position(lower(trim(p_query)) in lower(coalesce(name,''))) > 0
       ORDER BY name, id
       LIMIT 50
    ) matches;

  INSERT INTO public.admin_audit_logs(actor_profile_id,actor_role,action,target_type,target_id,outcome,metadata)
  VALUES(auth.uid()::text,actor_role,'ADMIN_PROFILE_SEARCH','profiles','', 'success',
    jsonb_build_object('query_length',length(trim(p_query)),'returned_count',jsonb_array_length(matched_profiles)));
  RETURN jsonb_build_object('records',matched_profiles,'returned_count',jsonb_array_length(matched_profiles));
END;
$function$;
REVOKE ALL ON FUNCTION public.vibesync_admin_search_profiles(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.vibesync_admin_search_profiles(text) TO authenticated, service_role;

NOTIFY pgrst,'reload schema';
COMMIT;
