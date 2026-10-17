-- Secure static Admin Web Portal: role roster, audited RPCs, metadata-only compliance and bounded exports.
-- Staff assignment is provisioned by an owner through a trusted database session, never by browser code.
BEGIN;

ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS last_active_at timestamptz;
CREATE INDEX IF NOT EXISTS idx_profiles_last_active_at
  ON public.profiles(last_active_at DESC) WHERE last_active_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS public.admin_users (
  user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  role text NOT NULL CHECK (role IN ('super_admin','moderator','finance_manager','compliance_officer')),
  email text, display_name text, is_active boolean NOT NULL DEFAULT true,
  created_by uuid REFERENCES auth.users(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_admin_users_role_active ON public.admin_users(role,is_active);
ALTER TABLE public.admin_users ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.admin_users FROM PUBLIC, anon, authenticated;
GRANT SELECT (user_id,role,email,display_name,is_active,created_at) ON public.admin_users TO authenticated;
GRANT ALL ON public.admin_users TO service_role;
DROP POLICY IF EXISTS admin_users_self_read ON public.admin_users;
CREATE POLICY admin_users_self_read ON public.admin_users FOR SELECT TO authenticated
  USING (user_id = (SELECT auth.uid()));

CREATE TABLE IF NOT EXISTS public.admin_profile_flags (
  profile_id text PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE,
  is_spam boolean NOT NULL DEFAULT false, reason text NOT NULL DEFAULT '',
  updated_by uuid REFERENCES auth.users(id) ON DELETE SET NULL, updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.admin_profile_flags ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.admin_profile_flags FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.admin_profile_flags TO service_role;

CREATE TABLE IF NOT EXISTS public.admin_compliance_exports (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  requested_by uuid REFERENCES auth.users(id) ON DELETE SET NULL,
  case_reference text NOT NULL, legal_basis text NOT NULL CHECK (legal_basis IN ('SEC_94_BNSS','MLAT')),
  query_fingerprint text NOT NULL, record_count integer NOT NULL DEFAULT 0,
  export_sha256 text NOT NULL DEFAULT '', generated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_admin_compliance_exports_created ON public.admin_compliance_exports(generated_at DESC);
ALTER TABLE public.admin_compliance_exports ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.admin_compliance_exports FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.admin_compliance_exports TO service_role;

CREATE TABLE IF NOT EXISTS public.admin_broadcast_jobs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  initiated_by uuid REFERENCES auth.users(id) ON DELETE SET NULL,
  inactive_days integer NOT NULL CHECK (inactive_days IN (3,7)),
  status text NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
  eligible_profiles integer NOT NULL DEFAULT 0, tokens_attempted integer NOT NULL DEFAULT 0,
  sent integer NOT NULL DEFAULT 0, failed integer NOT NULL DEFAULT 0,
  error_summary text NOT NULL DEFAULT '', created_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz
);
CREATE INDEX IF NOT EXISTS idx_admin_broadcast_jobs_recent ON public.admin_broadcast_jobs(created_at DESC);
ALTER TABLE public.admin_broadcast_jobs ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.admin_broadcast_jobs FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.admin_broadcast_jobs TO service_role;

CREATE OR REPLACE FUNCTION public.vibesync_admin_has_role(p_roles text[])
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = 'pg_catalog','public'
AS $function$
  SELECT auth.uid() IS NOT NULL AND EXISTS (
    SELECT 1 FROM public.admin_users au
    WHERE au.user_id = auth.uid() AND au.is_active AND au.role = ANY(p_roles)
  );
$function$;
REVOKE ALL ON FUNCTION public.vibesync_admin_has_role(text[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.vibesync_admin_has_role(text[]) TO authenticated, service_role;

-- Preserve the client-side protection for ordinary requests while permitting trusted SECURITY DEFINER
-- RPCs owned by the migration owner to apply their narrowly scoped, role-checked changes.
CREATE OR REPLACE FUNCTION public.protect_profile_fields()
RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path = 'pg_catalog','public'
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
    NEW.phone_hash := NULL;
  END IF;
  RETURN NEW;
END;
$function$;

-- Trusted Android session heartbeat; clients cannot supply another profile identifier.
CREATE OR REPLACE FUNCTION public.vibesync_touch_presence()
RETURNS timestamptz LANGUAGE plpgsql SECURITY DEFINER SET search_path = 'pg_catalog','public'
AS $function$
DECLARE v_profile_id text := public.vibesync_jwt_profile_id(); v_now timestamptz := clock_timestamp();
BEGIN
  IF v_profile_id IS NULL OR NOT public.vibesync_jwt_session_active() THEN
    RAISE EXCEPTION 'verified_vibesync_session_required' USING ERRCODE = '42501';
  END IF;
  UPDATE public.profiles SET last_active_at = v_now
    WHERE id = v_profile_id AND coalesce(is_deleted,false) = false;
  IF NOT FOUND THEN RAISE EXCEPTION 'active_profile_not_found' USING ERRCODE = 'P0002'; END IF;
  RETURN v_now;
END;
$function$;
REVOKE ALL ON FUNCTION public.vibesync_touch_presence() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.vibesync_touch_presence() TO anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION public.vibesync_get_admin_telemetry()
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = 'pg_catalog','public'
AS $function$
DECLARE item record; relation_ref regclass; row_count bigint; totals jsonb := '{}'::jsonb;
  active_day bigint := 0; active_week bigint := 0;
BEGIN
  IF NOT public.vibesync_admin_has_role(ARRAY['super_admin','moderator','finance_manager','compliance_officer']) THEN
    RAISE EXCEPTION 'admin_access_required' USING ERRCODE = '42501';
  END IF;
  FOR item IN SELECT * FROM (VALUES
    ('profiles','public.profiles'),('businesses','public.businesses'),
    ('business_payment_orders','public.business_payment_orders'),
    ('business_wallet_ledger','public.business_wallet_ledger'),
    ('chat_messages','public.chat_messages'),('device_tokens','public.device_tokens'),
    ('admin_audit_logs','public.admin_audit_logs')
  ) AS allowed(metric,relation_name)
  LOOP
    relation_ref := to_regclass(item.relation_name);
    IF relation_ref IS NOT NULL THEN
      EXECUTE format('SELECT count(*) FROM %s',relation_ref) INTO row_count;
      totals := totals || jsonb_build_object(item.metric,row_count);
    END IF;
  END LOOP;
  SELECT count(*) FILTER (WHERE last_active_at >= now()-interval '24 hours'),
         count(*) FILTER (WHERE last_active_at >= now()-interval '7 days')
    INTO active_day,active_week FROM public.profiles
   WHERE last_active_at IS NOT NULL AND coalesce(is_deleted,false)=false;
  RETURN jsonb_build_object('generated_at',clock_timestamp(),'counts',totals,
    'active_24h',coalesce(active_day,0),'active_7d',coalesce(active_week,0),
    'presence_source','verified app-resume heartbeat','webhook_observability','not_instrumented');
END;
$function$;
REVOKE ALL ON FUNCTION public.vibesync_get_admin_telemetry() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.vibesync_get_admin_telemetry() TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.vibesync_admin_verify_profile(p_profile_id text,p_verified boolean)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = 'pg_catalog','public'
AS $function$
DECLARE actor_role text;
BEGIN
  SELECT role INTO actor_role FROM public.admin_users WHERE user_id=auth.uid() AND is_active;
  IF actor_role IS NULL OR actor_role NOT IN ('super_admin','moderator') THEN
    RAISE EXCEPTION 'moderation_role_required' USING ERRCODE = '42501';
  END IF;
  IF p_profile_id IS NULL OR length(trim(p_profile_id))=0 THEN
    RAISE EXCEPTION 'profile_id_required' USING ERRCODE = '22023';
  END IF;
  UPDATE public.profiles SET is_verified=p_verified WHERE id=trim(p_profile_id);
  IF NOT FOUND THEN RAISE EXCEPTION 'profile_not_found' USING ERRCODE = 'P0002'; END IF;
  INSERT INTO public.admin_audit_logs(actor_profile_id,actor_role,action,target_type,target_id,outcome,metadata)
  VALUES(auth.uid()::text,actor_role,'ADMIN_VERIFY_PROFILE','profiles',trim(p_profile_id),'success',
    jsonb_build_object('is_verified',p_verified,'payment_required',false,'paid_tier_changed',false));
  RETURN jsonb_build_object('ok',true,'profile_id',trim(p_profile_id),'is_verified',p_verified,'paid_tier_changed',false);
END;
$function$;
REVOKE ALL ON FUNCTION public.vibesync_admin_verify_profile(text,boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.vibesync_admin_verify_profile(text,boolean) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.vibesync_admin_moderate_profile(
  p_profile_id text,p_action text,p_enabled boolean,p_reason text DEFAULT ''
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = 'pg_catalog','public'
AS $function$
DECLARE actor_role text; status_result text;
BEGIN
  SELECT role INTO actor_role FROM public.admin_users WHERE user_id=auth.uid() AND is_active;
  IF actor_role IS NULL OR actor_role NOT IN ('super_admin','moderator') THEN
    RAISE EXCEPTION 'moderation_role_required' USING ERRCODE = '42501';
  END IF;
  IF p_profile_id IS NULL OR length(trim(p_profile_id))=0 OR p_action NOT IN ('spam','ban') THEN
    RAISE EXCEPTION 'invalid_moderation_request' USING ERRCODE = '22023';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM public.profiles WHERE id=trim(p_profile_id)) THEN
    RAISE EXCEPTION 'profile_not_found' USING ERRCODE = 'P0002';
  END IF;
  IF p_action='ban' THEN
    UPDATE public.profiles SET account_status=CASE WHEN p_enabled THEN 'BANNED' ELSE 'ACTIVE' END
      WHERE id=trim(p_profile_id);
    status_result := CASE WHEN p_enabled THEN 'BANNED' ELSE 'ACTIVE' END;
  ELSE
    INSERT INTO public.admin_profile_flags(profile_id,is_spam,reason,updated_by,updated_at)
    VALUES(trim(p_profile_id),p_enabled,left(coalesce(p_reason,'Admin review'),500),auth.uid(),now())
    ON CONFLICT(profile_id) DO UPDATE SET is_spam=excluded.is_spam,reason=excluded.reason,
      updated_by=excluded.updated_by,updated_at=now();
    status_result := CASE WHEN p_enabled THEN 'SPAM' ELSE 'CLEAR' END;
  END IF;
  INSERT INTO public.admin_audit_logs(actor_profile_id,actor_role,action,target_type,target_id,outcome,metadata)
  VALUES(auth.uid()::text,actor_role,'ADMIN_MODERATE_PROFILE','profiles',trim(p_profile_id),'success',
    jsonb_build_object('moderation_action',p_action,'enabled',p_enabled));
  RETURN jsonb_build_object('ok',true,'profile_id',trim(p_profile_id),'action',p_action,'status',status_result);
END;
$function$;
REVOKE ALL ON FUNCTION public.vibesync_admin_moderate_profile(text,text,boolean,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.vibesync_admin_moderate_profile(text,text,boolean,text) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.vibesync_admin_get_payment_summary()
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = 'pg_catalog','public'
AS $function$
DECLARE paid bigint; pending bigint; failed bigint; expired bigint; gross numeric(14,2); recent jsonb;
BEGIN
  IF NOT public.vibesync_admin_has_role(ARRAY['super_admin','finance_manager']) THEN
    RAISE EXCEPTION 'finance_role_required' USING ERRCODE = '42501';
  END IF;
  SELECT count(*) FILTER(WHERE status='PAID'),count(*) FILTER(WHERE status='PENDING'),
         count(*) FILTER(WHERE status='FAILED'),count(*) FILTER(WHERE status='EXPIRED'),
         coalesce(sum(total_amount_inr) FILTER(WHERE status='PAID'),0)
    INTO paid,pending,failed,expired,gross FROM public.business_payment_orders;
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id',q.id,'provider',q.provider,'purchase_type',q.purchase_type,
    'total_amount_inr',q.total_amount_inr,'status',q.status,'created_at',q.created_at,'paid_at',q.paid_at,
    'provider_order_id',q.provider_order_id,'provider_payment_id',q.provider_payment_id
  ) ORDER BY q.created_at DESC),'[]'::jsonb)
    INTO recent FROM (SELECT id,provider,purchase_type,total_amount_inr,status,created_at,paid_at,
      provider_order_id,provider_payment_id FROM public.business_payment_orders
      ORDER BY created_at DESC LIMIT 100) q;
  RETURN jsonb_build_object('paid_orders',coalesce(paid,0),'pending_orders',coalesce(pending,0),
    'failed_orders',coalesce(failed,0),'expired_orders',coalesce(expired,0),'gross_paid_inr',coalesce(gross,0),
    'recent_orders',recent,'webhook_observability',jsonb_build_object(
      'state','not_instrumented',
      'message','Provider webhook delivery events are not persisted by the current schema. Verify delivery directly in the payment provider console.'));
END;
$function$;
REVOKE ALL ON FUNCTION public.vibesync_admin_get_payment_summary() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.vibesync_admin_get_payment_summary() TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.vibesync_admin_search_lea_metadata(
  p_query text,p_case_reference text,p_legal_basis text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = 'pg_catalog','public'
AS $function$
DECLARE actor_role text; records jsonb; request_id uuid; generated timestamptz:=clock_timestamp(); row_count integer;
BEGIN
  SELECT role INTO actor_role FROM public.admin_users WHERE user_id=auth.uid() AND is_active;
  IF actor_role IS NULL OR actor_role NOT IN ('super_admin','compliance_officer') THEN
    RAISE EXCEPTION 'compliance_role_required' USING ERRCODE = '42501';
  END IF;
  IF p_query IS NULL OR length(trim(p_query))<2 OR length(p_query)>120
     OR p_case_reference IS NULL OR length(trim(p_case_reference))<3 OR length(p_case_reference)>120
     OR p_legal_basis NOT IN ('SEC_94_BNSS','MLAT') THEN
    RAISE EXCEPTION 'invalid_compliance_search' USING ERRCODE = '22023';
  END IF;
  SELECT coalesce(jsonb_agg(to_jsonb(q) ORDER BY q.created_at DESC),'[]'::jsonb)
    INTO records FROM (
      SELECT id,name,city,country,account_status,is_verified,created_at,updated_at
      FROM public.profiles
      WHERE id=trim(p_query) OR position(lower(trim(p_query)) in lower(coalesce(name,'')))>0
      ORDER BY created_at DESC LIMIT 100
    ) q;
  row_count:=jsonb_array_length(records);
  INSERT INTO public.admin_compliance_exports(requested_by,case_reference,legal_basis,query_fingerprint,record_count,generated_at)
  VALUES(auth.uid(),trim(p_case_reference),p_legal_basis,md5(lower(trim(p_query))),row_count,generated)
  RETURNING id INTO request_id;
  INSERT INTO public.admin_audit_logs(actor_profile_id,actor_role,action,target_type,target_id,outcome,metadata)
  VALUES(auth.uid()::text,actor_role,'ADMIN_LEA_METADATA_SEARCH','admin_compliance_exports',request_id::text,'success',
    jsonb_build_object('legal_basis',p_legal_basis,'record_count',row_count,'query_fingerprint',md5(lower(trim(p_query)))));
  RETURN jsonb_build_object('request_id',request_id,'case_reference',trim(p_case_reference),
    'legal_basis',p_legal_basis,'generated_at',generated,'record_count',row_count,'records',records);
END;
$function$;
REVOKE ALL ON FUNCTION public.vibesync_admin_search_lea_metadata(text,text,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.vibesync_admin_search_lea_metadata(text,text,text) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.vibesync_admin_seal_compliance_export(
  p_export_id uuid,p_sha256 text,p_record_count integer
) RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = 'pg_catalog','public'
AS $function$
DECLARE actor_role text;
BEGIN
  SELECT role INTO actor_role FROM public.admin_users WHERE user_id=auth.uid() AND is_active;
  IF actor_role IS NULL OR actor_role NOT IN ('super_admin','compliance_officer') THEN
    RAISE EXCEPTION 'compliance_role_required' USING ERRCODE = '42501';
  END IF;
  IF p_sha256 IS NULL OR p_sha256 !~ '^[0-9a-f]{64}$' OR p_record_count<0 THEN
    RAISE EXCEPTION 'invalid_integrity_seal' USING ERRCODE = '22023';
  END IF;
  UPDATE public.admin_compliance_exports SET export_sha256=p_sha256,record_count=p_record_count
    WHERE id=p_export_id AND requested_by=auth.uid();
  IF NOT FOUND THEN RAISE EXCEPTION 'compliance_export_not_found' USING ERRCODE = 'P0002'; END IF;
  INSERT INTO public.admin_audit_logs(actor_profile_id,actor_role,action,target_type,target_id,outcome,metadata)
  VALUES(auth.uid()::text,actor_role,'ADMIN_LEA_EXPORT_SEALED','admin_compliance_exports',p_export_id::text,'success',
    jsonb_build_object('sha256',p_sha256,'record_count',p_record_count));
  RETURN true;
END;
$function$;
REVOKE ALL ON FUNCTION public.vibesync_admin_seal_compliance_export(uuid,text,integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.vibesync_admin_seal_compliance_export(uuid,text,integer) TO authenticated, service_role;

-- Static table and column allow-list. No arbitrary relation names or client-supplied SQL are executed.
CREATE OR REPLACE FUNCTION public.vibesync_admin_export_table_page(
  p_table text,p_offset integer DEFAULT 0,p_limit integer DEFAULT 1000
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = 'pg_catalog','public'
AS $function$
DECLARE actor_role text; records jsonb:='[]'::jsonb; allowed boolean:=false;
BEGIN
  SELECT role INTO actor_role FROM public.admin_users WHERE user_id=auth.uid() AND is_active;
  IF actor_role IS NULL THEN RAISE EXCEPTION 'admin_access_required' USING ERRCODE = '42501'; END IF;
  IF p_offset<0 OR p_limit<1 OR p_limit>1000 THEN
    RAISE EXCEPTION 'invalid_export_page' USING ERRCODE = '22023';
  END IF;
  allowed:=CASE p_table
    WHEN 'profiles' THEN actor_role IN ('super_admin','compliance_officer')
    WHEN 'businesses' THEN actor_role='super_admin'
    WHEN 'business_payment_orders' THEN actor_role IN ('super_admin','finance_manager')
    WHEN 'business_wallet_ledger' THEN actor_role IN ('super_admin','finance_manager')
    WHEN 'admin_audit_logs' THEN actor_role IN ('super_admin','compliance_officer')
    WHEN 'admin_users' THEN actor_role='super_admin'
    ELSE false END;
  IF NOT allowed THEN RAISE EXCEPTION 'export_table_not_permitted' USING ERRCODE = '42501'; END IF;

  CASE p_table
  WHEN 'profiles' THEN
    SELECT coalesce(jsonb_agg(q.row_data ORDER BY q.created DESC,q.row_id DESC),'[]'::jsonb) INTO records
    FROM (SELECT p.created_at created,p.id::text row_id,
      jsonb_build_object('id',p.id,'name',p.name,'city',p.city,'country',p.country,'occupation',p.occupation,
      'account_status',p.account_status,'is_verified',p.is_verified,'is_deleted',p.is_deleted,
      'created_at',p.created_at,'updated_at',p.updated_at,'last_active_at',p.last_active_at) row_data
      FROM public.profiles p ORDER BY p.created_at DESC,p.id DESC LIMIT p_limit OFFSET p_offset) q;
  WHEN 'businesses' THEN
    SELECT coalesce(jsonb_agg(q.row_data ORDER BY q.created DESC,q.row_id DESC),'[]'::jsonb) INTO records
    FROM (SELECT b.created_at created,b.id::text row_id,
      jsonb_build_object('id',b.id,'owner_profile_id',b.owner_profile_id,'name',b.name,'category',b.category,
      'is_published',b.is_published,'is_verified',b.is_verified,'subscription_status',b.subscription_status,
      'tier_badge',b.tier_badge,'is_public_utility',b.is_public_utility,'created_at',b.created_at,'updated_at',b.updated_at) row_data
      FROM public.businesses b ORDER BY b.created_at DESC,b.id DESC LIMIT p_limit OFFSET p_offset) q;
  WHEN 'business_payment_orders' THEN
    SELECT coalesce(jsonb_agg(q.row_data ORDER BY q.created DESC,q.row_id DESC),'[]'::jsonb) INTO records
    FROM (SELECT o.created_at created,o.id::text row_id,
      jsonb_build_object('id',o.id,'business_id',o.business_id,'owner_profile_id',o.owner_profile_id,
      'purchase_type',o.purchase_type,'points',o.points,'verification_tier',o.verification_tier,
      'base_amount_inr',o.base_amount_inr,'gst_amount_inr',o.gst_amount_inr,'total_amount_inr',o.total_amount_inr,
      'provider',o.provider,'provider_order_id',o.provider_order_id,'provider_payment_id',o.provider_payment_id,
      'status',o.status,'created_at',o.created_at,'paid_at',o.paid_at) row_data
      FROM public.business_payment_orders o ORDER BY o.created_at DESC,o.id DESC LIMIT p_limit OFFSET p_offset) q;
  WHEN 'business_wallet_ledger' THEN
    SELECT coalesce(jsonb_agg(q.row_data ORDER BY q.created DESC,q.row_id DESC),'[]'::jsonb) INTO records
    FROM (SELECT l.created_at created,l.id::text row_id,
      jsonb_build_object('id',l.id,'business_id',l.business_id,'event_type',l.event_type,'points_delta',l.points_delta,
      'amount_inr',l.amount_inr,'status',l.status,'reference_id',l.reference_id,'created_at',l.created_at,'expires_at',l.expires_at) row_data
      FROM public.business_wallet_ledger l ORDER BY l.created_at DESC,l.id DESC LIMIT p_limit OFFSET p_offset) q;
  WHEN 'admin_audit_logs' THEN
    SELECT coalesce(jsonb_agg(q.row_data ORDER BY q.created DESC,q.row_id DESC),'[]'::jsonb) INTO records
    FROM (SELECT a.created_at created,a.id::text row_id,
      jsonb_build_object('id',a.id,'actor_profile_id',a.actor_profile_id,'actor_role',a.actor_role,'action',a.action,
      'target_type',a.target_type,'target_id',a.target_id,'outcome',a.outcome,'metadata',a.metadata,'created_at',a.created_at) row_data
      FROM public.admin_audit_logs a ORDER BY a.created_at DESC,a.id DESC LIMIT p_limit OFFSET p_offset) q;
  WHEN 'admin_users' THEN
    SELECT coalesce(jsonb_agg(q.row_data ORDER BY q.created DESC,q.row_id DESC),'[]'::jsonb) INTO records
    FROM (SELECT a.created_at created,a.user_id::text row_id,
      jsonb_build_object('user_id',a.user_id,'role',a.role,'email',a.email,'display_name',a.display_name,
      'is_active',a.is_active,'created_at',a.created_at) row_data
      FROM public.admin_users a ORDER BY a.created_at DESC,a.user_id DESC LIMIT p_limit OFFSET p_offset) q;
  END CASE;
  RETURN jsonb_build_object('table',p_table,'offset',p_offset,'limit',p_limit,'records',records,
    'has_more',(jsonb_array_length(records)=p_limit));
END;
$function$;
REVOKE ALL ON FUNCTION public.vibesync_admin_export_table_page(text,integer,integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.vibesync_admin_export_table_page(text,integer,integer) TO authenticated, service_role;

NOTIFY pgrst,'reload schema';
COMMIT;
