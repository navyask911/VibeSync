-- Follow-up hardening: preserve legacy phone redaction and align operational export with business schema.
BEGIN;

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
    SELECT coalesce(jsonb_agg(q.row_data ORDER BY q.created_epoch_ms DESC,q.row_id DESC),'[]'::jsonb) INTO records
    FROM (SELECT b.created_at_epoch_ms created_epoch_ms,b.id::text row_id,
      jsonb_build_object('id',b.id,'owner_profile_id',b.owner_profile_id,'name',b.name,'category',b.category,
      'is_published',b.is_published,'is_verified',b.is_verified,'subscription_status',b.subscription_status,
      'tier_badge',b.tier_badge,'is_public_utility',b.is_public_utility,
      'created_at_epoch_ms',b.created_at_epoch_ms,'updated_at',b.updated_at) row_data
      FROM public.businesses b ORDER BY b.created_at_epoch_ms DESC,b.id DESC LIMIT p_limit OFFSET p_offset) q;
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
