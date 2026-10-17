-- Follow-up hardening for anon RLS helper evaluation and privacy-minimizing governance audits.
BEGIN;

-- RLS policies intentionally call this for anon as well as authenticated users.
-- SECURITY DEFINER lets the helper invoke the private UUID-to-profile mapper without granting
-- the mapper itself to anon; the returned profile ID is still gated by auth.uid() and the signed claim.
CREATE OR REPLACE FUNCTION public.vibesync_jwt_profile_id()
RETURNS text
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = 'pg_catalog', 'public'
AS $function$
  SELECT public.vibesync_profile_id_from_auth_uid()
  WHERE public.vibesync_profile_id_from_auth_uid() =
    nullif(
      (nullif(current_setting('request.jwt.claims', true), '')::jsonb)->>'profile_id',
      ''
    );
$function$;

REVOKE ALL ON FUNCTION public.vibesync_jwt_profile_id() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.vibesync_jwt_profile_id() TO anon, authenticated, service_role;

-- Audit logs store actor/action/target and changed field names only. Avoid copying full profile
-- rows (phone, avatar, public keys, etc.) into a second persistent record.
CREATE OR REPLACE FUNCTION public.audit_governance_mutation()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = 'pg_catalog', 'public'
AS $function$
DECLARE
  before_doc jsonb := '{}'::jsonb;
  after_doc jsonb := '{}'::jsonb;
  row_doc jsonb := '{}'::jsonb;
  actor text;
  actor_role text;
  target_id text;
  changed_fields text[] := ARRAY[]::text[];
  should_log boolean := false;
BEGIN
  IF TG_OP <> 'INSERT' THEN before_doc := to_jsonb(OLD); END IF;
  IF TG_OP <> 'DELETE' THEN after_doc := to_jsonb(NEW); END IF;
  row_doc := CASE WHEN TG_OP = 'DELETE' THEN before_doc ELSE after_doc END;

  SELECT coalesce(array_agg(field_name ORDER BY field_name), ARRAY[]::text[])
    INTO changed_fields
  FROM (
    SELECT coalesce(old_row.key, new_row.key) AS field_name
    FROM jsonb_each(before_doc) AS old_row(key, value)
    FULL OUTER JOIN jsonb_each(after_doc) AS new_row(key, value) USING (key)
    WHERE old_row.value IS DISTINCT FROM new_row.value
  ) changes;

  actor := public.vibesync_jwt_profile_id();
  actor_role := coalesce(
    nullif((nullif(current_setting('request.jwt.claims', true), '')::jsonb #>> '{app_metadata,role}'), ''),
    CASE WHEN public.is_vibesync_super_admin() THEN 'superadmin' ELSE 'staff' END
  );
  target_id := coalesce(row_doc->>'id', row_doc->>'profile_id', row_doc->>'user_id', '');
  should_log := public.is_vibesync_super_admin()
    OR (
      TG_TABLE_NAME = 'staff_assignments'
      AND public.vibesync_jwt_session_active()
      AND row_doc->>'staff_profile_id' = actor
    );

  IF should_log THEN
    INSERT INTO public.admin_audit_logs (
      actor_profile_id, actor_role, action, target_type, target_id, outcome, metadata
    ) VALUES (
      actor, actor_role, 'GOVERNANCE_' || upper(TG_TABLE_NAME) || '_' || TG_OP,
      TG_TABLE_NAME, target_id, 'success',
      jsonb_build_object('changed_fields', to_jsonb(changed_fields))
    );
  END IF;

  IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END;
$function$;

-- Request workflow transitions and curated landmark edits were not previously included in the audit triggers.
DROP TRIGGER IF EXISTS trg_audit_verification_requests ON public.verification_requests;
CREATE TRIGGER trg_audit_verification_requests
  AFTER INSERT OR UPDATE OR DELETE ON public.verification_requests
  FOR EACH ROW EXECUTE FUNCTION public.audit_governance_mutation();

DROP TRIGGER IF EXISTS trg_audit_business_governance ON public.businesses;
CREATE TRIGGER trg_audit_business_governance
  AFTER UPDATE ON public.businesses
  FOR EACH ROW
  WHEN (
    OLD.tier_badge IS DISTINCT FROM NEW.tier_badge
    OR OLD.subscription_status IS DISTINCT FROM NEW.subscription_status
    OR OLD.subscription_expires_at IS DISTINCT FROM NEW.subscription_expires_at
    OR OLD.is_public_utility IS DISTINCT FROM NEW.is_public_utility
    OR OLD.is_published IS DISTINCT FROM NEW.is_published
    OR (OLD.is_curated_landmark IS TRUE AND (
      OLD.name IS DISTINCT FROM NEW.name
      OR OLD.description IS DISTINCT FROM NEW.description
      OR OLD.banner_url IS DISTINCT FROM NEW.banner_url
      OR OLD.opening_hours IS DISTINCT FROM NEW.opening_hours
      OR OLD.latitude IS DISTINCT FROM NEW.latitude
      OR OLD.longitude IS DISTINCT FROM NEW.longitude
      OR OLD.landmark_category IS DISTINCT FROM NEW.landmark_category
      OR OLD.category IS DISTINCT FROM NEW.category
    ))
  )
  EXECUTE FUNCTION public.audit_governance_mutation();

NOTIFY pgrst, 'reload schema';
COMMIT;
