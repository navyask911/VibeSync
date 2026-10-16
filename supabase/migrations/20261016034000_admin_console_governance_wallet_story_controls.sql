BEGIN;

-- Support both the standard Supabase Auth identity used by admin-web and the separate verified
-- VibeSync session used by the Android app. The fallback is fail-closed.
CREATE OR REPLACE FUNCTION public.is_vibesync_super_admin()
RETURNS boolean LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
  v_auth_uid uuid := auth.uid();
  v_profile_id text;
  v_admins oid := pg_catalog.to_regclass('public.super_admins');
  v_allowed boolean := false;
BEGIN
  IF v_auth_uid IS NOT NULL AND EXISTS (
    SELECT 1 FROM public.admin_users au
    WHERE au.user_id=v_auth_uid AND au.is_active IS TRUE AND au.role='super_admin'
  ) THEN RETURN true; END IF;
  IF NOT coalesce(public.vibesync_jwt_session_active(), false) THEN RETURN false; END IF;
  v_profile_id := public.vibesync_jwt_profile_id();
  IF nullif(v_profile_id,'') IS NULL OR v_admins IS NULL THEN RETURN false; END IF;
  IF EXISTS (SELECT 1 FROM pg_catalog.pg_attribute WHERE attrelid=v_admins AND attname='profile_id' AND NOT attisdropped) THEN
    EXECUTE 'SELECT EXISTS(SELECT 1 FROM public.super_admins WHERE profile_id::text=$1)' INTO v_allowed USING v_profile_id;
  ELSIF EXISTS (SELECT 1 FROM pg_catalog.pg_attribute WHERE attrelid=v_admins AND attname='user_id' AND NOT attisdropped) THEN
    EXECUTE 'SELECT EXISTS(SELECT 1 FROM public.super_admins WHERE user_id::text=$1)' INTO v_allowed USING v_profile_id;
  END IF;
  RETURN coalesce(v_allowed,false);
END;
$function$;
REVOKE ALL ON FUNCTION public.is_vibesync_super_admin() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.is_vibesync_super_admin() TO authenticated, service_role;

-- Every admin-web RPC is callable only through the server-side service-role gateway. The actor is
-- independently checked against the active admin_users roster before any operation is executed.
CREATE OR REPLACE FUNCTION public.vibesync_admin_gateway_authorized(p_actor_id uuid, p_allowed_roles text[])
RETURNS boolean LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_claims jsonb; v_role text;
BEGIN
  v_claims := coalesce(nullif(pg_catalog.current_setting('request.jwt.claims',true),''),'{}')::jsonb;
  IF coalesce(v_claims->>'role','') <> 'service_role' OR p_actor_id IS NULL THEN RETURN false; END IF;
  SELECT au.role INTO v_role FROM public.admin_users au
   WHERE au.user_id=p_actor_id AND au.is_active IS TRUE;
  RETURN coalesce(v_role = ANY(coalesce(p_allowed_roles,ARRAY[]::text[])),false);
END;
$function$;
REVOKE ALL ON FUNCTION public.vibesync_admin_gateway_authorized(uuid,text[]) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.vibesync_admin_gateway_authorized(uuid,text[]) TO service_role;

-- Add super-admin reads without dropping ordinary owner/public-directory policies.
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
GRANT SELECT ON public.profiles TO authenticated;
DROP POLICY IF EXISTS profiles_admin_super_select ON public.profiles;
CREATE POLICY profiles_admin_super_select ON public.profiles FOR SELECT TO authenticated USING (public.is_vibesync_super_admin());

ALTER TABLE public.businesses ENABLE ROW LEVEL SECURITY;
GRANT SELECT ON public.businesses TO authenticated;
DROP POLICY IF EXISTS businesses_admin_super_select ON public.businesses;
CREATE POLICY businesses_admin_super_select ON public.businesses FOR SELECT TO authenticated USING (public.is_vibesync_super_admin());

ALTER TABLE public.business_payment_orders ENABLE ROW LEVEL SECURITY;
GRANT SELECT ON public.business_payment_orders TO authenticated;
DROP POLICY IF EXISTS business_payment_orders_admin_select ON public.business_payment_orders;
CREATE POLICY business_payment_orders_admin_select ON public.business_payment_orders FOR SELECT TO authenticated USING (public.is_vibesync_super_admin());

ALTER TABLE public.business_wallets ENABLE ROW LEVEL SECURITY;
GRANT SELECT ON public.business_wallets TO authenticated;
DROP POLICY IF EXISTS business_wallets_admin_select ON public.business_wallets;
CREATE POLICY business_wallets_admin_select ON public.business_wallets FOR SELECT TO authenticated USING (public.is_vibesync_super_admin());

ALTER TABLE public.business_wallet_ledger ENABLE ROW LEVEL SECURITY;
GRANT SELECT ON public.business_wallet_ledger TO authenticated;
DROP POLICY IF EXISTS business_wallet_ledger_admin_select ON public.business_wallet_ledger;
CREATE POLICY business_wallet_ledger_admin_select ON public.business_wallet_ledger FOR SELECT TO authenticated USING (public.is_vibesync_super_admin());

ALTER TABLE public.business_wallet_payment_orders ENABLE ROW LEVEL SECURITY;
GRANT SELECT ON public.business_wallet_payment_orders TO authenticated;
DROP POLICY IF EXISTS business_wallet_payment_orders_admin_select ON public.business_wallet_payment_orders;
CREATE POLICY business_wallet_payment_orders_admin_select ON public.business_wallet_payment_orders FOR SELECT TO authenticated USING (public.is_vibesync_super_admin());

ALTER TABLE public.business_analytics_events ENABLE ROW LEVEL SECURITY;
GRANT SELECT ON public.business_analytics_events TO authenticated;
DROP POLICY IF EXISTS business_analytics_events_admin_select ON public.business_analytics_events;
CREATE POLICY business_analytics_events_admin_select ON public.business_analytics_events FOR SELECT TO authenticated
  USING (public.is_vibesync_super_admin());

ALTER TABLE public.business_followers ENABLE ROW LEVEL SECURITY;
GRANT SELECT ON public.business_followers TO authenticated;
DROP POLICY IF EXISTS business_followers_admin_select ON public.business_followers;
CREATE POLICY business_followers_admin_select ON public.business_followers FOR SELECT TO authenticated
  USING (public.is_vibesync_super_admin());

ALTER TABLE public.business_broadcasts ENABLE ROW LEVEL SECURITY;
GRANT SELECT ON public.business_broadcasts TO authenticated;
DROP POLICY IF EXISTS business_broadcasts_admin_select ON public.business_broadcasts;
CREATE POLICY business_broadcasts_admin_select ON public.business_broadcasts FOR SELECT TO authenticated
  USING (public.is_vibesync_super_admin());

ALTER TABLE public.status_stories ENABLE ROW LEVEL SECURITY;
GRANT SELECT ON public.status_stories TO authenticated;
DROP POLICY IF EXISTS status_stories_admin_super_select ON public.status_stories;
CREATE POLICY status_stories_admin_super_select ON public.status_stories FOR SELECT TO authenticated
  USING (public.is_vibesync_super_admin());

ALTER TABLE public.admin_audit_logs ENABLE ROW LEVEL SECURITY;
GRANT SELECT ON public.admin_audit_logs TO authenticated;
DROP POLICY IF EXISTS admin_audit_logs_superadmin_read ON public.admin_audit_logs;
CREATE POLICY admin_audit_logs_superadmin_read ON public.admin_audit_logs FOR SELECT TO authenticated USING (public.is_vibesync_super_admin());

ALTER TABLE public.admin_users ENABLE ROW LEVEL SECURITY;
GRANT SELECT ON public.admin_users TO authenticated;
DROP POLICY IF EXISTS admin_users_superadmin_read ON public.admin_users;
CREATE POLICY admin_users_superadmin_read ON public.admin_users FOR SELECT TO authenticated USING (public.is_vibesync_super_admin());

-- Story moderation hides entries through RLS and records the administrator; media rows are retained.
ALTER TABLE public.status_stories
  ADD COLUMN IF NOT EXISTS moderation_status text NOT NULL DEFAULT 'ACTIVE',
  ADD COLUMN IF NOT EXISTS moderated_by uuid,
  ADD COLUMN IF NOT EXISTS moderated_at timestamptz,
  ADD COLUMN IF NOT EXISTS moderation_reason text NOT NULL DEFAULT '';
DO $function$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_constraint
    WHERE conrelid='public.status_stories'::regclass AND conname='status_stories_moderation_status_check') THEN
    ALTER TABLE public.status_stories ADD CONSTRAINT status_stories_moderation_status_check
      CHECK (moderation_status IN ('ACTIVE','REMOVED'));
  END IF;
END;
$function$;
DROP POLICY IF EXISTS status_stories_select_active_visible ON public.status_stories;
CREATE POLICY status_stories_select_active_visible ON public.status_stories FOR SELECT TO authenticated
USING (
  public.vibesync_jwt_session_active()
  AND coalesce(moderation_status,'ACTIVE')='ACTIVE'
  AND "createdAt" >= ((extract(epoch FROM now())*1000)::bigint-86400000)
  AND "expiresAt" > (extract(epoch FROM now())*1000)::bigint
  AND (
    "userId"=public.vibesync_jwt_profile_id()
    OR upper(trim(coalesce("privacy",'FRIENDS_AND_MATCHES'))) IN ('EVERYONE','ALL_NETWORK','PUBLIC','ALL')
    OR public.vibesync_jwt_profile_id()=ANY(coalesce("allowedViewerProfileIds",'{}'::text[]))
  )
);

-- Operational review does not mutate the payment provider's settlement status. Only the verified
-- payment-webhook RPC may mark an order PAID.
ALTER TABLE public.business_payment_orders
  ADD COLUMN IF NOT EXISTS admin_review_status text NOT NULL DEFAULT 'UNREVIEWED',
  ADD COLUMN IF NOT EXISTS admin_review_note text NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS admin_reviewed_by uuid,
  ADD COLUMN IF NOT EXISTS admin_reviewed_at timestamptz;
DO $function$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_constraint
    WHERE conrelid='public.business_payment_orders'::regclass AND conname='business_payment_orders_admin_review_status_check') THEN
    ALTER TABLE public.business_payment_orders ADD CONSTRAINT business_payment_orders_admin_review_status_check
      CHECK (admin_review_status IN ('UNREVIEWED','NEEDS_INFO','REJECTED','PROVIDER_RECHECK'));
  END IF;
END;
$function$;

-- Replace the stale 3-argument overload (which checked auth.uid() directly) with the four-argument
-- gateway signature used by admin-portal-api. Schema-drift-safe JSON projection prevents missing
-- columns from breaking exports on installations created from an earlier migration version.
DROP FUNCTION IF EXISTS public.vibesync_admin_export_table_page(text,integer,integer);
CREATE OR REPLACE FUNCTION public.vibesync_admin_export_table_page(
  p_actor_id uuid, p_table text, p_offset integer DEFAULT 0, p_limit integer DEFAULT 1000
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_records jsonb := '[]'::jsonb;
BEGIN
  IF NOT public.vibesync_admin_gateway_authorized(p_actor_id,
    CASE p_table
      WHEN 'profiles' THEN ARRAY['super_admin','compliance_officer']::text[]
      WHEN 'businesses' THEN ARRAY['super_admin']::text[]
      WHEN 'business_wallets' THEN ARRAY['super_admin','finance_manager']::text[]
      WHEN 'business_followers' THEN ARRAY['super_admin']::text[]
      WHEN 'business_wallet_payment_orders' THEN ARRAY['super_admin','finance_manager']::text[]
      WHEN 'business_payment_orders' THEN ARRAY['super_admin','finance_manager']::text[]
      WHEN 'business_wallet_ledger' THEN ARRAY['super_admin','finance_manager']::text[]
      WHEN 'business_broadcasts' THEN ARRAY['super_admin']::text[]
      WHEN 'business_analytics_events' THEN ARRAY['super_admin','compliance_officer']::text[]
      WHEN 'admin_audit_logs' THEN ARRAY['super_admin','compliance_officer']::text[]
      WHEN 'admin_users' THEN ARRAY['super_admin']::text[]
      ELSE ARRAY[]::text[] END) THEN
    RAISE EXCEPTION 'admin_export_not_authorized' USING ERRCODE='42501';
  END IF;
  IF p_offset IS NULL OR p_offset<0 OR p_limit IS NULL OR p_limit<1 OR p_limit>1000 THEN
    RAISE EXCEPTION 'invalid_export_page' USING ERRCODE='22023';
  END IF;

  CASE p_table
  WHEN 'profiles' THEN
    SELECT coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
      'id',d->'id','name',d->'name','city',d->'city','country',d->'country','occupation',d->'occupation',
      'account_status',d->'account_status','is_verified',d->'is_verified','is_deleted',d->'is_deleted',
      'created_at',d->'created_at','updated_at',d->'updated_at','last_active_at',d->'last_active_at'
    )),'[]'::jsonb) INTO v_records
    FROM (SELECT pg_catalog.to_jsonb(p) d FROM public.profiles p
      ORDER BY p.id DESC LIMIT p_limit OFFSET p_offset) page;
  WHEN 'businesses' THEN
    SELECT coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
      'id',d->'id','owner_profile_id',d->'owner_profile_id','name',d->'name','category',d->'category',
      'address',d->'address','city',d->'city','phone_number',d->'phone_number','website_url',d->'website_url',
      'social_handle',d->'social_handle','opening_hours',d->'opening_hours','active_offer_summary',d->'active_offer_summary',
      'is_published',d->'is_published','is_verified',d->'is_verified','subscription_status',d->'subscription_status',
      'tier_badge',d->'tier_badge','is_public_utility',d->'is_public_utility',
      'created_at_epoch_ms',d->'created_at_epoch_ms','updated_at',d->'updated_at'
    )),'[]'::jsonb) INTO v_records
    FROM (SELECT pg_catalog.to_jsonb(b) d FROM public.businesses b
      ORDER BY b.id DESC LIMIT p_limit OFFSET p_offset) page;
  WHEN 'business_followers' THEN
    SELECT coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
      'business_id',d->'business_id','profile_id',d->'profile_id','interest_tags',d->'interest_tags','followed_at',d->'followed_at'
    )),'[]'::jsonb) INTO v_records
    FROM (SELECT pg_catalog.to_jsonb(f) d FROM public.business_followers f
      ORDER BY f.business_id DESC,f.profile_id DESC LIMIT p_limit OFFSET p_offset) page;
  WHEN 'business_wallets' THEN
    SELECT coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
      'business_id',d->'business_id','points_balance',d->'points_balance','updated_at',d->'updated_at'
    )),'[]'::jsonb) INTO v_records
    FROM (SELECT pg_catalog.to_jsonb(w) d FROM public.business_wallets w
      ORDER BY w.business_id DESC LIMIT p_limit OFFSET p_offset) page;
  WHEN 'business_wallet_payment_orders' THEN
    SELECT coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
      'id',d->'id','business_id',d->'business_id','owner_profile_id',d->'owner_profile_id',
      'points',d->'points','base_amount_inr',d->'base_amount_inr','gst_amount_inr',d->'gst_amount_inr',
      'total_amount_inr',d->'total_amount_inr','provider',d->'provider','provider_order_id',d->'provider_order_id',
      'provider_payment_id',d->'provider_payment_id','status',d->'status','created_at',d->'created_at','paid_at',d->'paid_at'
    )),'[]'::jsonb) INTO v_records
    FROM (SELECT pg_catalog.to_jsonb(o) d FROM public.business_wallet_payment_orders o
      ORDER BY o.id DESC LIMIT p_limit OFFSET p_offset) page;
  WHEN 'business_broadcasts' THEN
    SELECT coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
      'id',d->'id','business_id',d->'business_id','owner_profile_id',d->'owner_profile_id',
      'title',d->'title','message',d->'message','banner_url',d->'banner_url',
      'cta_label',d->'cta_label','cta_url',d->'cta_url','target_type',d->'target_type',
      'radius_km',d->'radius_km','interest_tags',d->'interest_tags','audience_size',d->'audience_size',
      'points_charged',d->'points_charged','status',d->'status','dispatch_error',d->'dispatch_error',
      'created_at',d->'created_at','dispatched_at',d->'dispatched_at'
    )),'[]'::jsonb) INTO v_records
    FROM (SELECT pg_catalog.to_jsonb(bc) d FROM public.business_broadcasts bc
      ORDER BY bc.id DESC LIMIT p_limit OFFSET p_offset) page;
  WHEN 'business_analytics_events' THEN
    SELECT coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
      'id',d->'id','business_id',d->'business_id','event_type',d->'event_type','created_at',d->'created_at'
    )),'[]'::jsonb) INTO v_records
    FROM (SELECT pg_catalog.to_jsonb(e) d FROM public.business_analytics_events e
      ORDER BY e.id DESC LIMIT p_limit OFFSET p_offset) page;
  WHEN 'business_payment_orders' THEN
    SELECT coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
      'id',d->'id','business_id',d->'business_id','owner_profile_id',d->'owner_profile_id',
      'purchase_type',d->'purchase_type','points',d->'points','verification_tier',d->'verification_tier',
      'base_amount_inr',d->'base_amount_inr','gst_amount_inr',d->'gst_amount_inr','total_amount_inr',d->'total_amount_inr',
      'provider',d->'provider','provider_order_id',d->'provider_order_id','provider_payment_id',d->'provider_payment_id',
      'status',d->'status','admin_review_status',d->'admin_review_status','admin_review_note',d->'admin_review_note',
      'created_at',d->'created_at','paid_at',d->'paid_at'
    )),'[]'::jsonb) INTO v_records
    FROM (SELECT pg_catalog.to_jsonb(o) d FROM public.business_payment_orders o
      ORDER BY o.id DESC LIMIT p_limit OFFSET p_offset) page;
  WHEN 'business_wallet_ledger' THEN
    SELECT coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
      'id',d->'id','business_id',d->'business_id','event_type',d->'event_type','points_delta',d->'points_delta',
      'amount_inr',d->'amount_inr','status',d->'status','reference_id',d->'reference_id',
      'created_at',d->'created_at','expires_at',d->'expires_at'
    )),'[]'::jsonb) INTO v_records
    FROM (SELECT pg_catalog.to_jsonb(l) d FROM public.business_wallet_ledger l
      ORDER BY l.id DESC LIMIT p_limit OFFSET p_offset) page;
  WHEN 'admin_audit_logs' THEN
    SELECT coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
      'id',d->'id','actor_profile_id',d->'actor_profile_id','actor_role',d->'actor_role','action',d->'action',
      'target_type',d->'target_type','target_id',d->'target_id','outcome',d->'outcome','metadata',d->'metadata','created_at',d->'created_at'
    )),'[]'::jsonb) INTO v_records
    FROM (SELECT pg_catalog.to_jsonb(a) d FROM public.admin_audit_logs a
      ORDER BY a.id DESC LIMIT p_limit OFFSET p_offset) page;
  WHEN 'admin_users' THEN
    SELECT coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
      'user_id',d->'user_id','role',d->'role','email',d->'email','display_name',d->'display_name',
      'is_active',d->'is_active','created_at',d->'created_at'
    )),'[]'::jsonb) INTO v_records
    FROM (SELECT pg_catalog.to_jsonb(a) d FROM public.admin_users a
      ORDER BY a.user_id DESC LIMIT p_limit OFFSET p_offset) page;
  ELSE
    RAISE EXCEPTION 'export_table_not_permitted' USING ERRCODE='42501';
  END CASE;
  RETURN pg_catalog.jsonb_build_object('table',p_table,'offset',p_offset,'limit',p_limit,
    'records',v_records,'has_more',pg_catalog.jsonb_array_length(v_records)=p_limit);
END;
$function$;
REVOKE ALL ON FUNCTION public.vibesync_admin_export_table_page(uuid,text,integer,integer) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.vibesync_admin_export_table_page(uuid,text,integer,integer) TO service_role;

CREATE OR REPLACE FUNCTION public.vibesync_admin_search_businesses(p_actor_id uuid,p_query text DEFAULT '')
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $function$
DECLARE v_query text:=pg_catalog.lower(pg_catalog.btrim(coalesce(p_query,''))); v_records jsonb;
BEGIN
  IF NOT public.vibesync_admin_gateway_authorized(p_actor_id,ARRAY['super_admin']::text[]) THEN
    RAISE EXCEPTION 'super_admin_required' USING ERRCODE='42501';
  END IF;
  SELECT coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
    'id',q.d->'id','name',q.d->'name','category',q.d->'category','city',q.d->'city',
    'is_verified',q.d->'is_verified','is_published',q.d->'is_published','wallet_points',q.d->'wallet_points',
    'subscription_status',q.d->'subscription_status','tier_badge',q.d->'tier_badge','created_at_epoch_ms',q.d->'created_at_epoch_ms'
  )),'[]'::jsonb) INTO v_records
  FROM (SELECT pg_catalog.to_jsonb(b) d FROM public.businesses b
    WHERE v_query='' OR b.id=p_query OR position(v_query IN pg_catalog.lower(coalesce(b.name,'')))>0
       OR position(v_query IN pg_catalog.lower(coalesce(b.category,'')))>0
    ORDER BY b.name,b.id LIMIT 100) q;
  RETURN pg_catalog.jsonb_build_object('records',v_records);
END;
$function$;
REVOKE ALL ON FUNCTION public.vibesync_admin_search_businesses(uuid,text) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.vibesync_admin_search_businesses(uuid,text) TO service_role;

CREATE OR REPLACE FUNCTION public.vibesync_admin_set_business_control(
  p_actor_id uuid,p_business_id text,p_action text,p_enabled boolean,p_reason text DEFAULT ''
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $function$
DECLARE v_role text; v_data jsonb;
BEGIN
  IF NOT public.vibesync_admin_gateway_authorized(p_actor_id,ARRAY['super_admin']::text[]) THEN
    RAISE EXCEPTION 'super_admin_required' USING ERRCODE='42501';
  END IF;
  IF nullif(pg_catalog.btrim(p_business_id),'') IS NULL OR p_action NOT IN ('VERIFY','PUBLISH') OR p_enabled IS NULL THEN
    RAISE EXCEPTION 'invalid_business_control_request' USING ERRCODE='22023';
  END IF;
  SELECT au.role INTO v_role FROM public.admin_users au WHERE au.user_id=p_actor_id AND au.is_active IS TRUE;
  IF p_action='VERIFY' THEN
    UPDATE public.businesses SET is_verified=p_enabled,updated_at=pg_catalog.now() WHERE id=pg_catalog.btrim(p_business_id);
  ELSE
    UPDATE public.businesses SET is_published=p_enabled,updated_at=pg_catalog.now() WHERE id=pg_catalog.btrim(p_business_id);
  END IF;
  IF NOT FOUND THEN RAISE EXCEPTION 'business_not_found' USING ERRCODE='P0002'; END IF;
  SELECT pg_catalog.to_jsonb(b) INTO v_data FROM public.businesses b WHERE b.id=pg_catalog.btrim(p_business_id);
  INSERT INTO public.admin_audit_logs(actor_profile_id,actor_role,action,target_type,target_id,outcome,metadata)
  VALUES(p_actor_id::text,v_role,'ADMIN_BUSINESS_CONTROL','businesses',p_business_id,'success',
    pg_catalog.jsonb_build_object('action',p_action,'enabled',p_enabled,'reason',pg_catalog.left(coalesce(p_reason,''),500)));
  RETURN pg_catalog.jsonb_build_object('ok',true,'business_id',p_business_id,'action',p_action,'enabled',p_enabled,
    'is_verified',v_data->'is_verified','is_published',v_data->'is_published');
END;
$function$;
REVOKE ALL ON FUNCTION public.vibesync_admin_set_business_control(uuid,text,text,boolean,text) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.vibesync_admin_set_business_control(uuid,text,text,boolean,text) TO service_role;

CREATE OR REPLACE FUNCTION public.vibesync_admin_adjust_business_wallet(
  p_actor_id uuid,p_business_id text,p_points_delta integer,p_reason text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $function$
DECLARE v_role text; v_balance integer; v_next integer; v_reference text:='admin_adjustment:'||pg_catalog.gen_random_uuid()::text;
BEGIN
  IF NOT public.vibesync_admin_gateway_authorized(p_actor_id,ARRAY['super_admin']::text[]) THEN
    RAISE EXCEPTION 'super_admin_required' USING ERRCODE='42501';
  END IF;
  IF nullif(pg_catalog.btrim(p_business_id),'') IS NULL OR p_points_delta IS NULL
     OR p_points_delta=0 OR pg_catalog.abs(p_points_delta)>1000000
     OR nullif(pg_catalog.btrim(coalesce(p_reason,'')),'') IS NULL OR pg_catalog.length(p_reason)>500 THEN
    RAISE EXCEPTION 'invalid_wallet_adjustment' USING ERRCODE='22023';
  END IF;
  SELECT au.role INTO v_role FROM public.admin_users au WHERE au.user_id=p_actor_id AND au.is_active IS TRUE;
  IF NOT EXISTS(SELECT 1 FROM public.businesses b WHERE b.id=pg_catalog.btrim(p_business_id)) THEN
    RAISE EXCEPTION 'business_not_found' USING ERRCODE='P0002';
  END IF;
  INSERT INTO public.business_wallets(business_id,points_balance)
    SELECT b.id,greatest(coalesce(b.wallet_points,0),0) FROM public.businesses b
     WHERE b.id=pg_catalog.btrim(p_business_id) ON CONFLICT(business_id) DO NOTHING;
  SELECT w.points_balance INTO v_balance FROM public.business_wallets w
   WHERE w.business_id=pg_catalog.btrim(p_business_id) FOR UPDATE;
  v_next:=coalesce(v_balance,0)+p_points_delta;
  IF v_next<0 THEN RAISE EXCEPTION 'wallet_balance_cannot_be_negative' USING ERRCODE='23514'; END IF;
  UPDATE public.business_wallets SET points_balance=v_next,updated_at=pg_catalog.now() WHERE business_id=pg_catalog.btrim(p_business_id);
  UPDATE public.businesses SET wallet_points=v_next,updated_at=pg_catalog.now() WHERE id=pg_catalog.btrim(p_business_id);
  INSERT INTO public.business_wallet_ledger(business_id,event_type,points_delta,amount_inr,status,reference_id)
  VALUES(pg_catalog.btrim(p_business_id),'ADJUSTMENT',p_points_delta,0,'CONFIRMED',v_reference);
  INSERT INTO public.admin_audit_logs(actor_profile_id,actor_role,action,target_type,target_id,outcome,metadata)
  VALUES(p_actor_id::text,v_role,'ADMIN_BUSINESS_WALLET_ADJUSTMENT','businesses',p_business_id,'success',
    pg_catalog.jsonb_build_object('points_delta',p_points_delta,'new_balance',v_next,'reason',pg_catalog.left(p_reason,500),'ledger_reference',v_reference));
  RETURN pg_catalog.jsonb_build_object('ok',true,'business_id',p_business_id,'points_delta',p_points_delta,'balance',v_next,'ledger_reference',v_reference);
END;
$function$;
REVOKE ALL ON FUNCTION public.vibesync_admin_adjust_business_wallet(uuid,text,integer,text) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.vibesync_admin_adjust_business_wallet(uuid,text,integer,text) TO service_role;

CREATE OR REPLACE FUNCTION public.vibesync_admin_review_payment_order(
  p_actor_id uuid,p_order_id uuid,p_decision text,p_reason text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $function$
DECLARE v_role text; v_payment_status text;
BEGIN
  IF NOT public.vibesync_admin_gateway_authorized(p_actor_id,ARRAY['super_admin','finance_manager']::text[]) THEN
    RAISE EXCEPTION 'finance_role_required' USING ERRCODE='42501';
  END IF;
  IF p_order_id IS NULL OR p_decision NOT IN ('PROVIDER_RECHECK','NEEDS_INFO','REJECTED')
     OR nullif(pg_catalog.btrim(coalesce(p_reason,'')),'') IS NULL OR pg_catalog.length(p_reason)>500 THEN
    RAISE EXCEPTION 'invalid_payment_review' USING ERRCODE='22023';
  END IF;
  SELECT au.role INTO v_role FROM public.admin_users au WHERE au.user_id=p_actor_id AND au.is_active IS TRUE;
  UPDATE public.business_payment_orders SET admin_review_status=p_decision,admin_review_note=pg_catalog.left(pg_catalog.btrim(p_reason),500),
    admin_reviewed_by=p_actor_id,admin_reviewed_at=pg_catalog.clock_timestamp() WHERE id=p_order_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'payment_order_not_found' USING ERRCODE='P0002'; END IF;
  SELECT o.status INTO v_payment_status FROM public.business_payment_orders o WHERE o.id=p_order_id;
  INSERT INTO public.admin_audit_logs(actor_profile_id,actor_role,action,target_type,target_id,outcome,metadata)
  VALUES(p_actor_id::text,v_role,'ADMIN_PAYMENT_ORDER_REVIEW','business_payment_orders',p_order_id::text,'success',
    pg_catalog.jsonb_build_object('decision',p_decision,'reason',pg_catalog.left(p_reason,500),'payment_status_unchanged',true));
  RETURN pg_catalog.jsonb_build_object('ok',true,'order_id',p_order_id,'admin_review_status',p_decision,'payment_status',v_payment_status);
END;
$function$;
REVOKE ALL ON FUNCTION public.vibesync_admin_review_payment_order(uuid,uuid,text,text) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.vibesync_admin_review_payment_order(uuid,uuid,text,text) TO service_role;

CREATE OR REPLACE FUNCTION public.vibesync_admin_list_status_stories(p_actor_id uuid,p_limit integer DEFAULT 60)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $function$
DECLARE v_records jsonb;
BEGIN
  IF NOT public.vibesync_admin_gateway_authorized(p_actor_id,ARRAY['super_admin','moderator']::text[]) THEN
    RAISE EXCEPTION 'moderation_role_required' USING ERRCODE='42501';
  END IF;
  IF p_limit IS NULL OR p_limit<1 OR p_limit>100 THEN RAISE EXCEPTION 'invalid_story_limit' USING ERRCODE='22023'; END IF;
  SELECT coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
    'id',s.id,'userId',s."userId",'userName',s."userName",'mediaType',s."mediaType",
    'captionText',pg_catalog.left(coalesce(s."captionText",''),180),'createdAt',s."createdAt",
    'expiresAt',s."expiresAt",'moderation_status',s.moderation_status,
    'moderated_at',s.moderated_at,'moderation_reason',s.moderation_reason
  )),'[]'::jsonb) INTO v_records FROM (
    SELECT * FROM public.status_stories ORDER BY "createdAt" DESC LIMIT p_limit
  ) s;
  RETURN pg_catalog.jsonb_build_object('records',v_records);
END;
$function$;
REVOKE ALL ON FUNCTION public.vibesync_admin_list_status_stories(uuid,integer) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.vibesync_admin_list_status_stories(uuid,integer) TO service_role;

CREATE OR REPLACE FUNCTION public.vibesync_admin_moderate_status_story(
  p_actor_id uuid,p_story_id text,p_action text,p_reason text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $function$
DECLARE v_role text; v_status text;
BEGIN
  IF NOT public.vibesync_admin_gateway_authorized(p_actor_id,ARRAY['super_admin','moderator']::text[]) THEN
    RAISE EXCEPTION 'moderation_role_required' USING ERRCODE='42501';
  END IF;
  IF nullif(pg_catalog.btrim(coalesce(p_story_id,'')),'') IS NULL OR p_action NOT IN ('HIDE','RESTORE')
     OR nullif(pg_catalog.btrim(coalesce(p_reason,'')),'') IS NULL OR pg_catalog.length(p_reason)>500 THEN
    RAISE EXCEPTION 'invalid_story_moderation_request' USING ERRCODE='22023';
  END IF;
  SELECT au.role INTO v_role FROM public.admin_users au WHERE au.user_id=p_actor_id AND au.is_active IS TRUE;
  v_status:=CASE p_action WHEN 'HIDE' THEN 'REMOVED' ELSE 'ACTIVE' END;
  UPDATE public.status_stories SET moderation_status=v_status,moderated_by=p_actor_id,
    moderated_at=pg_catalog.clock_timestamp(),moderation_reason=pg_catalog.left(pg_catalog.btrim(p_reason),500)
   WHERE id=pg_catalog.btrim(p_story_id);
  IF NOT FOUND THEN RAISE EXCEPTION 'status_story_not_found' USING ERRCODE='P0002'; END IF;
  INSERT INTO public.admin_audit_logs(actor_profile_id,actor_role,action,target_type,target_id,outcome,metadata)
  VALUES(p_actor_id::text,v_role,'ADMIN_STATUS_STORY_MODERATION','status_stories',p_story_id,'success',
    pg_catalog.jsonb_build_object('action',p_action,'moderation_status',v_status,'reason',pg_catalog.left(p_reason,500)));
  RETURN pg_catalog.jsonb_build_object('ok',true,'story_id',p_story_id,'moderation_status',v_status);
END;
$function$;
REVOKE ALL ON FUNCTION public.vibesync_admin_moderate_status_story(uuid,text,text,text) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.vibesync_admin_moderate_status_story(uuid,text,text,text) TO service_role;


-- Recreate the finance summary through the same service-role gateway and expose review metadata
-- so the admin dashboard reflects decisions without misrepresenting payment settlement.
CREATE OR REPLACE FUNCTION public.vibesync_admin_get_payment_summary(p_actor_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $function$
DECLARE
  paid bigint; pending bigint; failed bigint; expired bigint; gross numeric(14,2);
  recent jsonb; daily_series jsonb:='[]'::jsonb; gateway_series jsonb:='[]'::jsonb;
BEGIN
  IF NOT public.vibesync_admin_gateway_authorized(p_actor_id,ARRAY['super_admin','finance_manager']::text[]) THEN
    RAISE EXCEPTION 'finance_role_required' USING ERRCODE='42501';
  END IF;
  SELECT count(*) FILTER(WHERE status='PAID'),count(*) FILTER(WHERE status='PENDING'),
         count(*) FILTER(WHERE status='FAILED'),count(*) FILTER(WHERE status='EXPIRED'),
         coalesce(sum(total_amount_inr) FILTER(WHERE status='PAID'),0)
    INTO paid,pending,failed,expired,gross FROM public.business_payment_orders;
  SELECT coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
    'id',q.id,'business_id',q.business_id,'provider',q.provider,'purchase_type',q.purchase_type,
    'total_amount_inr',q.total_amount_inr,'status',q.status,'admin_review_status',q.admin_review_status,
    'admin_review_note',q.admin_review_note,'created_at',q.created_at,'paid_at',q.paid_at,
    'provider_order_id',q.provider_order_id,'provider_payment_id',q.provider_payment_id
  ) ORDER BY q.created_at DESC),'[]'::jsonb)
    INTO recent FROM (
      SELECT id,business_id,provider,purchase_type,total_amount_inr,status,admin_review_status,admin_review_note,
        created_at,paid_at,provider_order_id,provider_payment_id
      FROM public.business_payment_orders ORDER BY created_at DESC LIMIT 100
    ) q;
  SELECT coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
    'date',pg_catalog.to_char(day_bucket,'YYYY-MM-DD'),'total',total_orders,
    'succeeded',successful_orders,'failed',failed_orders) ORDER BY day_bucket),'[]'::jsonb)
    INTO daily_series FROM (
      SELECT days.day_bucket,count(o.id) total_orders,
        count(o.id) FILTER(WHERE upper(coalesce(o.status,''))='PAID') successful_orders,
        count(o.id) FILTER(WHERE upper(coalesce(o.status,''))='FAILED') failed_orders
      FROM pg_catalog.generate_series(
        pg_catalog.date_trunc('day',pg_catalog.now())-interval '13 days',
        pg_catalog.date_trunc('day',pg_catalog.now()),interval '1 day'
      ) AS days(day_bucket)
      LEFT JOIN public.business_payment_orders o ON o.created_at>=days.day_bucket AND o.created_at<days.day_bucket+interval '1 day'
      GROUP BY days.day_bucket
    ) d;
  SELECT coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
    'provider',provider,'succeeded',succeeded,'failed',failed) ORDER BY provider),'[]'::jsonb)
    INTO gateway_series FROM (
      SELECT coalesce(nullif(trim(provider),''),'Unknown') provider,
        count(*) FILTER(WHERE upper(coalesce(status,''))='PAID') succeeded,
        count(*) FILTER(WHERE upper(coalesce(status,''))='FAILED') failed
      FROM public.business_payment_orders
      WHERE created_at>=pg_catalog.date_trunc('day',pg_catalog.now())-interval '13 days'
      GROUP BY coalesce(nullif(trim(provider),''),'Unknown')
    ) gateways;
  RETURN pg_catalog.jsonb_build_object(
    'paid_orders',coalesce(paid,0),'pending_orders',coalesce(pending,0),
    'failed_orders',coalesce(failed,0),'expired_orders',coalesce(expired,0),
    'gross_paid_inr',coalesce(gross,0),'recent_orders',recent,'daily_transactions',daily_series,
    'gateway_outcomes',gateway_series,
    'webhook_observability',pg_catalog.jsonb_build_object(
      'state','not_instrumented',
      'message','Provider webhook delivery events are not persisted by the current schema. Verify delivery directly in the payment provider console.'
    )
  );
END;
$function$;
REVOKE ALL ON FUNCTION public.vibesync_admin_get_payment_summary(uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.vibesync_admin_get_payment_summary(uuid) TO service_role;


CREATE OR REPLACE FUNCTION public.vibesync_admin_update_business_settings(
  p_actor_id uuid,p_business_id text,p_opening_hours text,p_active_offer_summary text,p_reason text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $function$
DECLARE v_role text; v_business jsonb;
BEGIN
  IF NOT public.vibesync_admin_gateway_authorized(p_actor_id,ARRAY['super_admin']::text[]) THEN
    RAISE EXCEPTION 'super_admin_required' USING ERRCODE='42501';
  END IF;
  IF nullif(pg_catalog.btrim(p_business_id),'') IS NULL
     OR p_opening_hours IS NULL OR pg_catalog.length(p_opening_hours)>1000
     OR p_active_offer_summary IS NULL OR pg_catalog.length(p_active_offer_summary)>500
     OR nullif(pg_catalog.btrim(coalesce(p_reason,'')),'') IS NULL OR pg_catalog.length(p_reason)>500 THEN
    RAISE EXCEPTION 'invalid_business_settings' USING ERRCODE='22023';
  END IF;
  SELECT au.role INTO v_role FROM public.admin_users au
    WHERE au.user_id=p_actor_id AND au.is_active IS TRUE;
  UPDATE public.businesses
     SET opening_hours=pg_catalog.btrim(p_opening_hours),
         active_offer_summary=pg_catalog.btrim(p_active_offer_summary),
         updated_at=pg_catalog.now()
   WHERE id=pg_catalog.btrim(p_business_id);
  IF NOT FOUND THEN RAISE EXCEPTION 'business_not_found' USING ERRCODE='P0002'; END IF;
  SELECT pg_catalog.to_jsonb(b) INTO v_business FROM public.businesses b WHERE b.id=pg_catalog.btrim(p_business_id);
  INSERT INTO public.admin_audit_logs(actor_profile_id,actor_role,action,target_type,target_id,outcome,metadata)
  VALUES(p_actor_id::text,v_role,'ADMIN_BUSINESS_OPERATIONAL_SETTINGS','businesses',p_business_id,'success',
    pg_catalog.jsonb_build_object('opening_hours_changed',true,'active_offer_summary_changed',true,'reason',pg_catalog.left(p_reason,500)));
  RETURN pg_catalog.jsonb_build_object('ok',true,'business_id',p_business_id,
    'opening_hours',v_business->'opening_hours','active_offer_summary',v_business->'active_offer_summary');
END;
$function$;
REVOKE ALL ON FUNCTION public.vibesync_admin_update_business_settings(uuid,text,text,text,text) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.vibesync_admin_update_business_settings(uuid,text,text,text,text) TO service_role;

CREATE OR REPLACE FUNCTION public.vibesync_admin_business_analytics_summary(p_actor_id uuid)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=''
AS $function$
DECLARE v_businesses bigint; v_published bigint; v_followers bigint; v_wallet_points bigint;
  v_broadcasts_dispatched bigint; v_broadcasts_total bigint; v_views bigint; v_impressions bigint; v_inquiries bigint;
BEGIN
  IF NOT public.vibesync_admin_gateway_authorized(p_actor_id,ARRAY['super_admin']::text[]) THEN
    RAISE EXCEPTION 'super_admin_required' USING ERRCODE='42501';
  END IF;
  SELECT count(*),count(*) FILTER(WHERE is_published IS TRUE),
    coalesce(sum(wallet_points),0)::bigint INTO v_businesses,v_published,v_wallet_points
    FROM public.businesses;
  SELECT count(*) INTO v_followers FROM public.business_followers;
  SELECT count(*) FILTER(WHERE status='DISPATCHED'),count(*) INTO v_broadcasts_dispatched,v_broadcasts_total
    FROM public.business_broadcasts;
  SELECT count(*) FILTER(WHERE event_type='VIEW'),
         count(*) FILTER(WHERE event_type='IMPRESSION'),
         count(*) FILTER(WHERE event_type='CHAT_INQUIRY')
    INTO v_views,v_impressions,v_inquiries
    FROM public.business_analytics_events
   WHERE created_at >= pg_catalog.now()-interval '30 days';
  RETURN pg_catalog.jsonb_build_object('business_count',coalesce(v_businesses,0),
    'published_businesses',coalesce(v_published,0),'followers',coalesce(v_followers,0),
    'wallet_points',coalesce(v_wallet_points,0),'broadcasts_dispatched',coalesce(v_broadcasts_dispatched,0),
    'broadcasts_total',coalesce(v_broadcasts_total,0),
    'profile_views_30d',coalesce(v_views,0),'impressions_30d',coalesce(v_impressions,0),
    'inquiries_30d',coalesce(v_inquiries,0));
END;
$function$;
REVOKE ALL ON FUNCTION public.vibesync_admin_business_analytics_summary(uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.vibesync_admin_business_analytics_summary(uuid) TO service_role;

-- Contact matches include the server-owned verification flag. Missing legacy values fail closed.
-- Recreate both functions because PostgreSQL cannot change a RETURNS TABLE shape with CREATE OR REPLACE.
DROP FUNCTION IF EXISTS public.match_contacts_by_phone_numbers(text[]);
DROP FUNCTION IF EXISTS private.match_contacts_by_phone_numbers_secure(text[]);

CREATE OR REPLACE FUNCTION private.match_contacts_by_phone_numbers_secure(p_phone_numbers text[])
RETURNS TABLE (
  phone_e164 text,
  id text,
  name text,
  profile_photo_url text,
  avatar_emoji text,
  is_verified boolean
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  caller_profile_id text := public.vibesync_current_profile_id();
BEGIN
  IF caller_profile_id IS NULL OR btrim(caller_profile_id) = '' THEN
    RAISE EXCEPTION 'verified VibeSync session required' USING ERRCODE='28000';
  END IF;
  IF coalesce(array_length(p_phone_numbers,1),0)>1000 THEN
    RAISE EXCEPTION 'too many phone numbers' USING ERRCODE='22023';
  END IF;
  RETURN QUERY
  WITH supplied AS (
    SELECT DISTINCT CASE
      WHEN btrim(u.raw_phone) LIKE '+%' AND length(d.digits) BETWEEN 8 AND 15 AND left(d.digits,1)<>'0' THEN '+'||d.digits
      WHEN d.digits ~ '^0[0-9]{10}' AND length(d.digits)=11 THEN '+91'||right(d.digits,10)
      WHEN d.digits ~ '^[0-9]{10}' AND length(d.digits)=10 THEN '+91'||d.digits
      WHEN d.digits ~ '^00[1-9][0-9]{7,14}' AND length(d.digits) BETWEEN 10 AND 17 THEN '+'||substr(d.digits,3)
      WHEN d.digits ~ '^[1-9][0-9]{7,14}' AND length(d.digits) BETWEEN 8 AND 15 THEN '+'||d.digits
      ELSE NULL
    END AS normalized_phone
    FROM unnest(coalesce(p_phone_numbers,array[]::text[])) AS u(raw_phone)
    CROSS JOIN LATERAL (
      SELECT regexp_replace(btrim(u.raw_phone),'[^0-9]','','g') AS digits
    ) AS d
    WHERE u.raw_phone IS NOT NULL AND btrim(u.raw_phone)<>''
  )
  SELECT idx.phone_e164,
         p.id::text,
         coalesce(p.name,'VibeSync Member')::text,
         coalesce(p.avatar_url,'')::text,
         coalesce(p.avatar_emoji,'✨')::text,
         coalesce(p.is_verified,false)::boolean
    FROM supplied s
    JOIN public.phone_lookup_index idx ON idx.phone_e164=s.normalized_phone
    JOIN public.profiles p ON p.id::text=idx.profile_id
   WHERE s.normalized_phone IS NOT NULL
     AND idx.profile_id<>caller_profile_id
     AND coalesce(p.is_deleted,false)=false
     AND coalesce(p.account_status,'ACTIVE')='ACTIVE'
   LIMIT 1000;
END;
$function$;
REVOKE ALL ON FUNCTION private.match_contacts_by_phone_numbers_secure(text[]) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION private.match_contacts_by_phone_numbers_secure(text[]) TO anon;

CREATE OR REPLACE FUNCTION public.match_contacts_by_phone_numbers(phone_numbers text[])
RETURNS TABLE (
  phone_e164 text,
  id text,
  name text,
  profile_photo_url text,
  avatar_emoji text,
  is_verified boolean
)
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog
AS $function$
  SELECT * FROM private.match_contacts_by_phone_numbers_secure(phone_numbers);
$function$;
REVOKE ALL ON FUNCTION public.match_contacts_by_phone_numbers(text[]) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.match_contacts_by_phone_numbers(text[]) TO anon;
GRANT USAGE ON SCHEMA private TO anon;

ALTER TABLE public.profiles ALTER COLUMN is_verified SET DEFAULT false;
CREATE INDEX IF NOT EXISTS idx_business_analytics_events_created_type
  ON public.business_analytics_events(created_at DESC, event_type);

NOTIFY pgrst,'reload schema';
COMMIT;
