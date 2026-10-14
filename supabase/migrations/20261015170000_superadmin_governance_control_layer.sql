-- Superadmin governance, directory paywall, provider-verified payment activation, audits and staff delegation.
-- Forward-only and idempotent for the reconciled Supabase migration pipeline.
BEGIN;

-- 1) Subscription and trust tier schema.
ALTER TABLE public.profiles
  ADD COLUMN IF NOT EXISTS tier_badge text NOT NULL DEFAULT 'none',
  ADD COLUMN IF NOT EXISTS subscription_status text NOT NULL DEFAULT 'inactive',
  ADD COLUMN IF NOT EXISTS subscription_expires_at timestamptz,
  ADD COLUMN IF NOT EXISTS is_public_utility boolean NOT NULL DEFAULT false;

ALTER TABLE public.businesses
  ADD COLUMN IF NOT EXISTS tier_badge text NOT NULL DEFAULT 'none',
  ADD COLUMN IF NOT EXISTS subscription_status text NOT NULL DEFAULT 'inactive',
  ADD COLUMN IF NOT EXISTS subscription_expires_at timestamptz,
  ADD COLUMN IF NOT EXISTS is_public_utility boolean NOT NULL DEFAULT false;

ALTER TABLE public.business_payment_orders
  ADD COLUMN IF NOT EXISTS subscription_days integer NOT NULL DEFAULT 365;

UPDATE public.profiles SET tier_badge = 'none'
 WHERE tier_badge IS NULL OR tier_badge NOT IN ('none','crown_blue','crown_silver','crown_gold','gray');
UPDATE public.businesses SET tier_badge = 'none'
 WHERE tier_badge IS NULL OR tier_badge NOT IN ('none','crown_blue','crown_silver','crown_gold','gray');

UPDATE public.profiles SET subscription_status = lower(coalesce(subscription_status,'inactive'));
UPDATE public.businesses SET subscription_status = lower(coalesce(subscription_status,'inactive'));
UPDATE public.profiles SET subscription_status = 'inactive'
 WHERE subscription_status NOT IN ('inactive','active','cancelled','expired')
    OR (subscription_status = 'active' AND subscription_expires_at IS NULL);
UPDATE public.businesses SET subscription_status = 'inactive'
 WHERE subscription_status NOT IN ('inactive','active','cancelled','expired')
    OR (subscription_status = 'active' AND subscription_expires_at IS NULL);
UPDATE public.profiles SET is_public_utility = false WHERE is_public_utility IS NULL;
UPDATE public.businesses SET is_public_utility = false WHERE is_public_utility IS NULL;
UPDATE public.business_payment_orders SET subscription_days = 365 WHERE subscription_days IS NULL;

ALTER TABLE public.profiles
  ALTER COLUMN tier_badge SET DEFAULT 'none',
  ALTER COLUMN tier_badge SET NOT NULL,
  ALTER COLUMN subscription_status SET DEFAULT 'inactive',
  ALTER COLUMN subscription_status SET NOT NULL,
  ALTER COLUMN is_public_utility SET DEFAULT false,
  ALTER COLUMN is_public_utility SET NOT NULL;
ALTER TABLE public.businesses
  ALTER COLUMN tier_badge SET DEFAULT 'none',
  ALTER COLUMN tier_badge SET NOT NULL,
  ALTER COLUMN subscription_status SET DEFAULT 'inactive',
  ALTER COLUMN subscription_status SET NOT NULL,
  ALTER COLUMN is_public_utility SET DEFAULT false,
  ALTER COLUMN is_public_utility SET NOT NULL;
ALTER TABLE public.business_payment_orders
  ALTER COLUMN subscription_days SET DEFAULT 365,
  ALTER COLUMN subscription_days SET NOT NULL;

-- Previously designated government entities receive the free utility category.
UPDATE public.profiles
 SET tier_badge='gray', is_public_utility=true, subscription_status='inactive', subscription_expires_at=NULL
 WHERE coalesce(is_government_entity,false)=true;

DO $constraints$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='profiles_tier_badge_check' AND conrelid='public.profiles'::regclass) THEN
    ALTER TABLE public.profiles ADD CONSTRAINT profiles_tier_badge_check
      CHECK (tier_badge IN ('none','crown_blue','crown_silver','crown_gold','gray'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='businesses_tier_badge_check' AND conrelid='public.businesses'::regclass) THEN
    ALTER TABLE public.businesses ADD CONSTRAINT businesses_tier_badge_check
      CHECK (tier_badge IN ('none','crown_blue','crown_silver','crown_gold','gray'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='profiles_subscription_status_check' AND conrelid='public.profiles'::regclass) THEN
    ALTER TABLE public.profiles ADD CONSTRAINT profiles_subscription_status_check
      CHECK (subscription_status IN ('inactive','active','cancelled','expired'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='businesses_subscription_status_check' AND conrelid='public.businesses'::regclass) THEN
    ALTER TABLE public.businesses ADD CONSTRAINT businesses_subscription_status_check
      CHECK (subscription_status IN ('inactive','active','cancelled','expired'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='profiles_active_subscription_expiry_check' AND conrelid='public.profiles'::regclass) THEN
    ALTER TABLE public.profiles ADD CONSTRAINT profiles_active_subscription_expiry_check
      CHECK (subscription_status <> 'active' OR subscription_expires_at IS NOT NULL);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='businesses_active_subscription_expiry_check' AND conrelid='public.businesses'::regclass) THEN
    ALTER TABLE public.businesses ADD CONSTRAINT businesses_active_subscription_expiry_check
      CHECK (subscription_status <> 'active' OR subscription_expires_at IS NOT NULL);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='business_payment_orders_subscription_days_check' AND conrelid='public.business_payment_orders'::regclass) THEN
    ALTER TABLE public.business_payment_orders ADD CONSTRAINT business_payment_orders_subscription_days_check
      CHECK (subscription_days BETWEEN 1 AND 3650);
  END IF;
END
$constraints$;

COMMENT ON COLUMN public.profiles.tier_badge IS 'none=unverified; crown_blue=verified individuals/public figures/creators; crown_silver=verified storefronts/trade merchants; crown_gold=premium enterprises; gray=public utility.';
COMMENT ON COLUMN public.businesses.tier_badge IS 'none=unverified; crown_blue=verified creators; crown_silver=verified storefronts/trade merchants; crown_gold=premium enterprises; gray=government/civic utility.';

-- 2) Require the server-issued claim, app role, mapped profile, live session and stored identity.
CREATE OR REPLACE FUNCTION public.is_vibesync_super_admin()
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path='pg_catalog','public'
AS $function$
 SELECT coalesce((nullif(current_setting('request.jwt.claims',true),'')::jsonb->>'is_superadmin')='true',false)
  AND coalesce((nullif(current_setting('request.jwt.claims',true),'')::jsonb #>> '{app_metadata,role}')='superadmin',false)
  AND public.vibesync_jwt_session_active()
  AND EXISTS (
    SELECT 1 FROM public.super_admins sa
    WHERE nullif(sa.profile_id,'')=public.vibesync_jwt_profile_id()
       OR nullif(sa.user_id,'')=public.vibesync_jwt_profile_id()
       OR sa.phone=nullif((nullif(current_setting('request.jwt.claims',true),'')::jsonb)->>'phone','')
  );
$function$;
REVOKE ALL ON FUNCTION public.is_vibesync_super_admin() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.is_vibesync_super_admin() TO authenticated, service_role;
-- Read-only helpers must be executable during anonymous RLS policy evaluation; they resolve false/null without a mapped session.
GRANT EXECUTE ON FUNCTION public.vibesync_jwt_profile_id() TO anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.vibesync_jwt_session_active() TO anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION public.custom_access_token_hook(event jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path='pg_catalog','public'
AS $function$
DECLARE
  claims jsonb := coalesce(event->'claims','{}'::jsonb);
  app_metadata jsonb := coalesce(event->'claims'->'app_metadata','{}'::jsonb);
  claim_user_id text := nullif(event->>'user_id','');
  claim_phone text := nullif(event->'claims'->>'phone','');
  make_superadmin boolean := false;
BEGIN
  SELECT EXISTS (
    SELECT 1 FROM public.super_admins sa
    WHERE (claim_phone IS NOT NULL AND sa.phone=claim_phone)
       OR (claim_user_id IS NOT NULL AND (sa.profile_id=claim_user_id OR sa.user_id=claim_user_id))
  ) INTO make_superadmin;
  IF make_superadmin THEN
    app_metadata := app_metadata || jsonb_build_object('role','superadmin','is_superadmin',true);
  ELSE
    app_metadata := app_metadata || jsonb_build_object('role','member','is_superadmin',false);
  END IF;
  claims := jsonb_set(claims,'{app_metadata}',app_metadata,true);
  claims := jsonb_set(claims,'{is_superadmin}',to_jsonb(make_superadmin),true);
  RETURN jsonb_set(event,'{claims}',claims,true);
END;
$function$;
REVOKE ALL ON FUNCTION public.custom_access_token_hook(jsonb) FROM PUBLIC, anon, authenticated;
GRANT USAGE ON SCHEMA public TO supabase_auth_admin;
GRANT EXECUTE ON FUNCTION public.custom_access_token_hook(jsonb) TO supabase_auth_admin;
COMMENT ON FUNCTION public.custom_access_token_hook(jsonb) IS
 'Configure Auth Hooks > Custom Access Token to pg-functions://postgres/custom_access_token_hook. Truecaller custom JWTs receive matching claims in verify-truecaller.';

-- 3) Audit log with server-only writes and a superadmin-only read path.
CREATE TABLE IF NOT EXISTS public.admin_audit_logs (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 actor_profile_id text,
 actor_role text NOT NULL,
 action text NOT NULL,
 target_type text NOT NULL DEFAULT '',
 target_id text NOT NULL DEFAULT '',
 outcome text NOT NULL DEFAULT 'success',
 metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_admin_audit_logs_created_at ON public.admin_audit_logs(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_admin_audit_logs_actor_created ON public.admin_audit_logs(actor_profile_id,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_admin_audit_logs_target ON public.admin_audit_logs(target_type,target_id,created_at DESC);
ALTER TABLE public.admin_audit_logs ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.admin_audit_logs FROM anon, authenticated;
GRANT SELECT ON public.admin_audit_logs TO authenticated;
GRANT ALL ON public.admin_audit_logs TO service_role;
DROP POLICY IF EXISTS admin_audit_logs_superadmin_read ON public.admin_audit_logs;
CREATE POLICY admin_audit_logs_superadmin_read ON public.admin_audit_logs FOR SELECT TO authenticated
 USING (public.is_vibesync_super_admin());

-- Hide the phone/identity roster from ordinary authenticated callers.
ALTER TABLE public.super_admins ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Allow read access to authenticated" ON public.super_admins;
DROP POLICY IF EXISTS super_admins_verified_claim_read ON public.super_admins;
REVOKE ALL ON public.super_admins FROM anon, authenticated;
GRANT SELECT ON public.super_admins TO authenticated;
GRANT ALL ON public.super_admins TO service_role;
CREATE POLICY super_admins_verified_claim_read ON public.super_admins FOR SELECT TO authenticated
 USING (public.is_vibesync_super_admin());

-- 4) Delegated assignments: only superadmins create/reassign/delete; staff may inspect/update assigned work.
CREATE TABLE IF NOT EXISTS public.staff_assignments (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 staff_profile_id text NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
 assigned_by_profile_id text NOT NULL REFERENCES public.profiles(id) ON DELETE RESTRICT,
 task_type text NOT NULL CHECK (task_type IN ('support','field_verification','listing_review','safety_review')),
 target_profile_id text REFERENCES public.profiles(id) ON DELETE SET NULL,
 target_business_id text REFERENCES public.businesses(id) ON DELETE SET NULL,
 status text NOT NULL DEFAULT 'assigned' CHECK (status IN ('assigned','in_progress','completed','cancelled')),
 notes text NOT NULL DEFAULT '',
 due_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT staff_assignments_exactly_one_target CHECK (num_nonnulls(target_profile_id,target_business_id)=1)
);
CREATE INDEX IF NOT EXISTS idx_staff_assignments_staff_status_due ON public.staff_assignments(staff_profile_id,status,due_at);
CREATE INDEX IF NOT EXISTS idx_staff_assignments_target_profile ON public.staff_assignments(target_profile_id) WHERE target_profile_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_staff_assignments_target_business ON public.staff_assignments(target_business_id) WHERE target_business_id IS NOT NULL;
ALTER TABLE public.staff_assignments ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.staff_assignments FROM anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.staff_assignments TO authenticated;
GRANT ALL ON public.staff_assignments TO service_role;
DROP POLICY IF EXISTS staff_assignments_superadmin_all ON public.staff_assignments;
CREATE POLICY staff_assignments_superadmin_all ON public.staff_assignments FOR ALL TO authenticated
 USING (public.is_vibesync_super_admin()) WITH CHECK (public.is_vibesync_super_admin());
DROP POLICY IF EXISTS staff_assignments_assignee_read ON public.staff_assignments;
CREATE POLICY staff_assignments_assignee_read ON public.staff_assignments FOR SELECT TO authenticated
 USING (staff_profile_id=public.vibesync_jwt_profile_id() AND public.vibesync_jwt_session_active());
DROP POLICY IF EXISTS staff_assignments_assignee_update ON public.staff_assignments;
CREATE POLICY staff_assignments_assignee_update ON public.staff_assignments FOR UPDATE TO authenticated
 USING (staff_profile_id=public.vibesync_jwt_profile_id() AND public.vibesync_jwt_session_active())
 WITH CHECK (staff_profile_id=public.vibesync_jwt_profile_id() AND public.vibesync_jwt_session_active());

CREATE OR REPLACE FUNCTION public.guard_staff_assignment_update()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path='pg_catalog','public'
AS $function$
BEGIN
 NEW.updated_at:=now();
 IF public.is_vibesync_super_admin() THEN RETURN NEW; END IF;
 IF NOT public.vibesync_jwt_session_active()
   OR OLD.staff_profile_id<>public.vibesync_jwt_profile_id()
   OR NEW.staff_profile_id<>OLD.staff_profile_id
   OR NEW.assigned_by_profile_id<>OLD.assigned_by_profile_id
   OR NEW.task_type<>OLD.task_type
   OR NEW.target_profile_id IS DISTINCT FROM OLD.target_profile_id
   OR NEW.target_business_id IS DISTINCT FROM OLD.target_business_id
   OR NEW.due_at IS DISTINCT FROM OLD.due_at
   OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
   RAISE EXCEPTION 'staff_assignment_change_not_authorized' USING ERRCODE='42501';
 END IF;
 RETURN NEW;
END;
$function$;
DROP TRIGGER IF EXISTS trg_guard_staff_assignment_update ON public.staff_assignments;
CREATE TRIGGER trg_guard_staff_assignment_update BEFORE UPDATE ON public.staff_assignments
 FOR EACH ROW EXECUTE FUNCTION public.guard_staff_assignment_update();

CREATE OR REPLACE FUNCTION public.audit_governance_mutation()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path='pg_catalog','public'
AS $function$
DECLARE row_json jsonb; actor text; actor_role text; target_id text; should_log boolean:=false;
BEGIN
 IF TG_OP='DELETE' THEN row_json:=to_jsonb(OLD); ELSE row_json:=to_jsonb(NEW); END IF;
 actor:=public.vibesync_jwt_profile_id();
 actor_role:=coalesce(nullif((nullif(current_setting('request.jwt.claims',true),'')::jsonb #>> '{app_metadata,role}'),''),
   CASE WHEN public.is_vibesync_super_admin() THEN 'superadmin' ELSE 'staff' END);
 target_id:=coalesce(row_json->>'id',row_json->>'profile_id',row_json->>'user_id','');
 should_log:=public.is_vibesync_super_admin()
   OR (TG_TABLE_NAME='staff_assignments' AND public.vibesync_jwt_session_active() AND row_json->>'staff_profile_id'=actor);
 IF should_log THEN
  INSERT INTO public.admin_audit_logs(actor_profile_id,actor_role,action,target_type,target_id,outcome,metadata)
  VALUES(actor,actor_role,'GOVERNANCE_'||upper(TG_TABLE_NAME)||'_'||TG_OP,TG_TABLE_NAME,target_id,'success',
    jsonb_build_object('row_after_or_before',row_json));
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END;
$function$;
DROP TRIGGER IF EXISTS trg_audit_staff_assignments ON public.staff_assignments;
CREATE TRIGGER trg_audit_staff_assignments AFTER INSERT OR UPDATE OR DELETE ON public.staff_assignments
 FOR EACH ROW EXECUTE FUNCTION public.audit_governance_mutation();
DROP TRIGGER IF EXISTS trg_audit_business_governance ON public.businesses;
CREATE TRIGGER trg_audit_business_governance AFTER UPDATE ON public.businesses
 FOR EACH ROW WHEN (OLD.tier_badge IS DISTINCT FROM NEW.tier_badge OR OLD.subscription_status IS DISTINCT FROM NEW.subscription_status
  OR OLD.subscription_expires_at IS DISTINCT FROM NEW.subscription_expires_at OR OLD.is_public_utility IS DISTINCT FROM NEW.is_public_utility
  OR OLD.is_published IS DISTINCT FROM NEW.is_published)
 EXECUTE FUNCTION public.audit_governance_mutation();
DROP TRIGGER IF EXISTS trg_audit_profile_governance ON public.profiles;
CREATE TRIGGER trg_audit_profile_governance AFTER UPDATE ON public.profiles
 FOR EACH ROW WHEN (OLD.tier_badge IS DISTINCT FROM NEW.tier_badge OR OLD.subscription_status IS DISTINCT FROM NEW.subscription_status
  OR OLD.subscription_expires_at IS DISTINCT FROM NEW.subscription_expires_at OR OLD.is_public_utility IS DISTINCT FROM NEW.is_public_utility
  OR OLD.is_government_entity IS DISTINCT FROM NEW.is_government_entity)
 EXECUTE FUNCTION public.audit_governance_mutation();

-- Protect tier, publication, verification and account-governance fields from profile-owner self-promotion.
CREATE OR REPLACE FUNCTION public.guard_directory_governance_fields()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path='pg_catalog','public'
AS $function$
DECLARE token_role text;
BEGIN
 token_role:=coalesce((nullif(current_setting('request.jwt.claims',true),'')::jsonb->>'role'),'');
 IF token_role='service_role' OR public.is_vibesync_super_admin() THEN RETURN NEW; END IF;
 IF TG_TABLE_NAME='profiles' THEN
  IF NEW.tier_badge IS DISTINCT FROM OLD.tier_badge
   OR NEW.subscription_status IS DISTINCT FROM OLD.subscription_status
   OR NEW.subscription_expires_at IS DISTINCT FROM OLD.subscription_expires_at
   OR NEW.is_public_utility IS DISTINCT FROM OLD.is_public_utility
   OR NEW.is_government_entity IS DISTINCT FROM OLD.is_government_entity
   OR NEW.verified_badge IS DISTINCT FROM OLD.verified_badge
   OR NEW.is_verified IS DISTINCT FROM OLD.is_verified
   OR NEW.is_deleted IS DISTINCT FROM OLD.is_deleted
   OR NEW.account_status IS DISTINCT FROM OLD.account_status THEN
   RAISE EXCEPTION 'directory_governance_fields_are_server_managed' USING ERRCODE='42501';
  END IF;
 ELSIF TG_TABLE_NAME='businesses' THEN
  IF NEW.tier_badge IS DISTINCT FROM OLD.tier_badge
   OR NEW.subscription_status IS DISTINCT FROM OLD.subscription_status
   OR NEW.subscription_expires_at IS DISTINCT FROM OLD.subscription_expires_at
   OR NEW.is_public_utility IS DISTINCT FROM OLD.is_public_utility
   OR NEW.is_published IS DISTINCT FROM OLD.is_published
   OR NEW.is_listing_paid IS DISTINCT FROM OLD.is_listing_paid
   OR NEW.listing_payment_txn_id IS DISTINCT FROM OLD.listing_payment_txn_id
   OR NEW.verification_tier IS DISTINCT FROM OLD.verification_tier
   OR NEW.is_verified IS DISTINCT FROM OLD.is_verified
   OR NEW.wallet_points IS DISTINCT FROM OLD.wallet_points THEN
   RAISE EXCEPTION 'business_directory_governance_fields_are_server_managed' USING ERRCODE='42501';
  END IF;
 END IF;
 RETURN NEW;
END;
$function$;
DROP TRIGGER IF EXISTS trg_guard_profile_directory_governance ON public.profiles;
CREATE TRIGGER trg_guard_profile_directory_governance BEFORE UPDATE ON public.profiles
 FOR EACH ROW EXECUTE FUNCTION public.guard_directory_governance_fields();
DROP TRIGGER IF EXISTS trg_guard_business_directory_governance ON public.businesses;
CREATE TRIGGER trg_guard_business_directory_governance BEFORE UPDATE ON public.businesses
 FOR EACH ROW EXECUTE FUNCTION public.guard_directory_governance_fields();

-- 5) Public directory/paywall predicates on underlying tables plus security-invoker views.
DROP POLICY IF EXISTS "Allow users to read profiles" ON public.profiles;
DROP POLICY IF EXISTS profiles_public_directory_paywall ON public.profiles;
CREATE POLICY profiles_public_directory_paywall ON public.profiles FOR SELECT TO anon, authenticated
 USING (
  (id=public.vibesync_jwt_profile_id() AND public.vibesync_jwt_session_active())
  OR (NOT coalesce(is_deleted,false) AND coalesce(account_status,'ACTIVE')='ACTIVE'
   AND ((subscription_status='active' AND subscription_expires_at>now()
      AND tier_badge IN ('crown_gold','crown_silver','crown_blue'))
    OR (is_public_utility=true AND tier_badge='gray')))
 );

DROP POLICY IF EXISTS "Allow users to update own profile" ON public.profiles;
CREATE POLICY "Allow users to update own profile" ON public.profiles FOR UPDATE TO authenticated
 USING (id=public.vibesync_jwt_profile_id() AND public.vibesync_jwt_session_active())
 WITH CHECK (id=public.vibesync_jwt_profile_id() AND public.vibesync_jwt_session_active());
DROP POLICY IF EXISTS "Users can update their own fcm token" ON public.profiles;
CREATE POLICY "Users can update their own fcm token" ON public.profiles FOR UPDATE TO authenticated
 USING (id=public.vibesync_jwt_profile_id() AND public.vibesync_jwt_session_active())
 WITH CHECK (id=public.vibesync_jwt_profile_id() AND public.vibesync_jwt_session_active());

DROP POLICY IF EXISTS businesses_public_published ON public.businesses;
DROP POLICY IF EXISTS businesses_public_directory_paywall ON public.businesses;
CREATE POLICY businesses_public_directory_paywall ON public.businesses FOR SELECT TO anon, authenticated
 USING (
  (is_published=true AND subscription_status='active' AND subscription_expires_at>now()
    AND tier_badge IN ('crown_gold','crown_silver','crown_blue'))
  OR (is_public_utility=true AND tier_badge='gray')
  OR (owner_profile_id=public.vibesync_jwt_profile_id() AND public.vibesync_jwt_session_active())
 );

CREATE OR REPLACE VIEW public.vibesync_profiles_public WITH (security_invoker=true) AS
SELECT id,name,bio,age,gender,city,country,country_flag,place,occupation,qualification,avatar_emoji,avatar_url,
 marital_status,account_status,is_verified,is_deleted,updated_at,public_identity_key,
 CASE WHEN location_visibility='EVERYONE' AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180
   AND NOT (latitude=0 AND longitude=0) THEN round(latitude::numeric,3)::double precision ELSE NULL::double precision END AS latitude,
 CASE WHEN location_visibility='EVERYONE' AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180
   AND NOT (latitude=0 AND longitude=0) THEN round(longitude::numeric,3)::double precision ELSE NULL::double precision END AS longitude
FROM public.profiles
WHERE coalesce(is_deleted,false)=false AND coalesce(account_status,'ACTIVE')='ACTIVE'
 AND ((subscription_status='active' AND subscription_expires_at>now()
      AND tier_badge IN ('crown_gold','crown_silver','crown_blue'))
   OR (is_public_utility=true AND tier_badge='gray'));

CREATE OR REPLACE VIEW public.hosted_business_directory WITH (security_invoker=true) AS
SELECT id,owner_profile_id,name,tagline,category,description,address,city,latitude,longitude,rating,review_count,
 banner_url,logo_emoji,phone_number,website_url,social_handle,is_verified,follower_count,active_offer_summary,
 verification_tier,tier_badge,subscription_status,subscription_expires_at,is_public_utility,is_published,created_at_epoch_ms,updated_at
FROM public.businesses
WHERE (is_published=true AND subscription_status='active' AND subscription_expires_at>now()
  AND tier_badge IN ('crown_gold','crown_silver','crown_blue'))
 OR (is_public_utility=true AND tier_badge='gray');

CREATE OR REPLACE VIEW public.hosted_public_directory WITH (security_invoker=true) AS
SELECT 'profile'::text AS entity_type,p.id AS entity_id,p.id AS owner_profile_id,p.name,
 nullif(p.occupation,'') AS category,p.bio AS description,p.city,p.avatar_url AS image_url,p.latitude,p.longitude,
 p.tier_badge,p.is_public_utility,p.subscription_expires_at
FROM public.profiles p
WHERE coalesce(p.is_deleted,false)=false AND coalesce(p.account_status,'ACTIVE')='ACTIVE'
 AND ((p.subscription_status='active' AND p.subscription_expires_at>now()
      AND p.tier_badge IN ('crown_gold','crown_silver','crown_blue'))
   OR (p.is_public_utility=true AND p.tier_badge='gray'))
UNION ALL
SELECT 'business'::text AS entity_type,b.id AS entity_id,b.owner_profile_id,b.name,b.category,b.description,b.city,
 nullif(b.banner_url,'') AS image_url,b.latitude,b.longitude,b.tier_badge,b.is_public_utility,b.subscription_expires_at
FROM public.businesses b
WHERE (b.is_published=true AND b.subscription_status='active' AND b.subscription_expires_at>now()
  AND b.tier_badge IN ('crown_gold','crown_silver','crown_blue'))
 OR (b.is_public_utility=true AND b.tier_badge='gray');

GRANT SELECT ON public.vibesync_profiles_public TO anon, authenticated;
GRANT SELECT ON public.hosted_business_directory TO anon, authenticated;
GRANT SELECT ON public.hosted_public_directory TO anon, authenticated;

-- Only superadmins may assign a paid crown subscription or a free gray public-utility tier.
CREATE OR REPLACE FUNCTION public.admin_assign_business_directory_tier(
 p_business_id text, p_tier_badge text, p_is_public_utility boolean DEFAULT false,
 p_subscription_expires_at timestamptz DEFAULT NULL
)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='pg_catalog','public'
AS $function$
BEGIN
 IF NOT public.vibesync_jwt_session_active() OR NOT public.is_vibesync_super_admin() THEN
  RAISE EXCEPTION 'super_admin_required' USING ERRCODE='42501';
 END IF;
 IF p_tier_badge NOT IN ('none','crown_blue','crown_silver','crown_gold','gray') THEN
  RAISE EXCEPTION 'invalid_tier_badge' USING ERRCODE='22023';
 END IF;
 IF p_tier_badge='gray' AND p_is_public_utility IS DISTINCT FROM true THEN
  RAISE EXCEPTION 'gray_tier_requires_public_utility' USING ERRCODE='22023';
 END IF;
 IF p_tier_badge IN ('crown_blue','crown_silver','crown_gold')
    AND (p_subscription_expires_at IS NULL OR p_subscription_expires_at<=now()) THEN
  RAISE EXCEPTION 'paid_tier_requires_future_expiry' USING ERRCODE='22023';
 END IF;
 UPDATE public.businesses SET tier_badge=p_tier_badge,
  is_public_utility=(p_tier_badge='gray' AND p_is_public_utility),
  subscription_status=CASE WHEN p_tier_badge IN ('crown_blue','crown_silver','crown_gold') THEN 'active' ELSE 'inactive' END,
  subscription_expires_at=CASE WHEN p_tier_badge IN ('crown_blue','crown_silver','crown_gold') THEN p_subscription_expires_at ELSE NULL END,
  is_published=CASE WHEN p_tier_badge='gray' THEN true ELSE is_published END,
  is_verified=CASE WHEN p_tier_badge IN ('crown_blue','crown_silver','crown_gold') THEN true ELSE is_verified END,
  updated_at=now()
 WHERE id=p_business_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'business_not_found' USING ERRCODE='P0002'; END IF;
END;
$function$;
REVOKE ALL ON FUNCTION public.admin_assign_business_directory_tier(text,text,boolean,timestamptz) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_assign_business_directory_tier(text,text,boolean,timestamptz) TO authenticated, service_role;

-- 6) Atomic, service-role-only settlement. The Edge Function verifies Razorpay's HMAC signature
-- first; this RPC independently checks captured status, INR amount and the stored provider order.
CREATE OR REPLACE FUNCTION public.process_payment_activation(
 p_provider_order_id text,p_provider_payment_id text,p_amount_paise bigint,p_currency text,p_payment_status text
)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='pg_catalog','public'
AS $function$
DECLARE
 o public.business_payment_orders%rowtype;
 settled jsonb;
 next_tier text;
 next_expiry timestamptz;
 prior_listing_payment text;
BEGIN
 IF coalesce((nullif(current_setting('request.jwt.claims',true),'')::jsonb->>'role'),'') <> 'service_role' THEN
  RAISE EXCEPTION 'trusted_payment_webhook_required' USING ERRCODE='42501';
 END IF;
 IF nullif(trim(p_provider_order_id),'') IS NULL OR nullif(trim(p_provider_payment_id),'') IS NULL THEN
  RAISE EXCEPTION 'payment_identifiers_required' USING ERRCODE='22023';
 END IF;
 IF p_amount_paise IS NULL OR lower(coalesce(p_payment_status,''))<>'captured'
   OR upper(coalesce(p_currency,''))<>'INR' OR p_amount_paise<=0 THEN
  RAISE EXCEPTION 'payment_not_captured_or_currency_invalid' USING ERRCODE='22023';
 END IF;
 SELECT * INTO o FROM public.business_payment_orders WHERE provider_order_id=p_provider_order_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'payment_order_not_found' USING ERRCODE='P0002'; END IF;
 IF o.provider<>'RAZORPAY' OR round(o.total_amount_inr*100)::bigint<>p_amount_paise THEN
  RAISE EXCEPTION 'payment_order_amount_or_provider_mismatch' USING ERRCODE='22023';
 END IF;
 IF o.status='PAID' AND o.provider_payment_id IS DISTINCT FROM p_provider_payment_id THEN
  RAISE EXCEPTION 'order_already_paid_with_another_payment' USING ERRCODE='23505';
 END IF;
 IF o.purchase_type='WALLET_RECHARGE' AND o.status='PAID' THEN
  RETURN jsonb_build_object('ok',true,'already_paid',true,'transaction_id',p_provider_payment_id);
 END IF;
 IF o.purchase_type='VERIFICATION_BADGE' THEN
  next_tier := CASE o.verification_tier WHEN 'GOLD' THEN 'crown_gold' WHEN 'SILVER' THEN 'crown_silver'
    WHEN 'BLUE_TICK' THEN 'crown_blue' ELSE NULL END;
  IF next_tier IS NULL THEN RAISE EXCEPTION 'unsupported_paid_tier' USING ERRCODE='22023'; END IF;
  SELECT listing_payment_txn_id INTO prior_listing_payment FROM public.businesses WHERE id=o.business_id;
  IF o.status='PAID' AND prior_listing_payment=p_provider_payment_id THEN
    RETURN jsonb_build_object('ok',true,'already_paid',true,'already_activated',true,
      'transaction_id',p_provider_payment_id,'tier_badge',next_tier);
  END IF;
 END IF;
 IF o.status='PENDING' THEN
  settled:=public.complete_business_payment_order(o.id,p_provider_payment_id);
 ELSIF o.status='PAID' THEN
  settled:=jsonb_build_object('ok',true,'already_paid',true);
 ELSE
  RAISE EXCEPTION 'payment_order_not_pending' USING ERRCODE='23514';
 END IF;

 IF o.purchase_type='VERIFICATION_BADGE' THEN
  next_expiry:=now()+make_interval(days=>o.subscription_days);
  UPDATE public.businesses SET tier_badge=next_tier,subscription_status='active',
   subscription_expires_at=next_expiry,is_public_utility=false,is_published=true,
   is_listing_paid=true,listing_payment_txn_id=p_provider_payment_id,is_verified=true,updated_at=now()
  WHERE id=o.business_id AND owner_profile_id=o.owner_profile_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'business_owner_mismatch' USING ERRCODE='42501'; END IF;
  IF next_tier='crown_blue' THEN
   UPDATE public.profiles SET tier_badge='crown_blue',subscription_status='active',
    subscription_expires_at=next_expiry,is_public_utility=false WHERE id=o.owner_profile_id;
  END IF;
 END IF;

 INSERT INTO public.admin_audit_logs(actor_profile_id,actor_role,action,target_type,target_id,outcome,metadata)
 VALUES(o.owner_profile_id,'payment_webhook','PAYMENT_ACTIVATION','business',o.business_id,'success',
  jsonb_build_object('order_id',o.id,'provider_order_id',p_provider_order_id,'provider_payment_id',p_provider_payment_id,
    'purchase_type',o.purchase_type,'verification_tier',o.verification_tier,'tier_badge',next_tier,
    'subscription_expires_at',next_expiry));
 RETURN coalesce(settled,'{}'::jsonb)||jsonb_build_object('ok',true,'tier_badge',next_tier,'subscription_expires_at',next_expiry);
END;
$function$;
REVOKE ALL ON FUNCTION public.process_payment_activation(text,text,bigint,text,text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.process_payment_activation(text,text,bigint,text,text) TO service_role;
-- Never expose a raw "mark paid" RPC to app clients; only trusted backend settlement may invoke it.
REVOKE ALL ON FUNCTION public.complete_business_payment_order(uuid,text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.complete_business_payment_order(uuid,text) TO service_role;

-- 7) Root/admin RPC grants and in-function claim checks.
REVOKE ALL ON FUNCTION public.admin_assign_verification_badge(text,public.verification_badge_tier) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_assign_verification_badge(text,public.verification_badge_tier) TO authenticated, service_role;
REVOKE ALL ON FUNCTION public.admin_review_verification_request(uuid,text,text,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_review_verification_request(uuid,text,text,text) TO authenticated, service_role;
REVOKE ALL ON FUNCTION public.admin_update_curated_landmark(text,text,text,text,text,double precision,double precision,public.curated_landmark_category) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_update_curated_landmark(text,text,text,text,text,double precision,double precision,public.curated_landmark_category) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.admin_assign_verification_badge(p_profile_id text,p_badge_tier public.verification_badge_tier)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='pg_catalog','public'
AS $function$
BEGIN
 IF NOT public.vibesync_jwt_session_active() OR NOT public.is_vibesync_super_admin() THEN
  RAISE EXCEPTION 'super_admin_required' USING ERRCODE='42501';
 END IF;
 UPDATE public.profiles SET verified_badge=p_badge_tier,
  is_government_entity=(p_badge_tier='GREY_GOV'),
  tier_badge=CASE p_badge_tier WHEN 'BLUE' THEN 'crown_blue' WHEN 'SILVER' THEN 'crown_silver'
   WHEN 'GOLD' THEN 'crown_gold' WHEN 'GREY_GOV' THEN 'gray' ELSE 'none' END,
  is_public_utility=(p_badge_tier='GREY_GOV'),
  subscription_status=CASE WHEN p_badge_tier IN ('BLUE','SILVER','GOLD') THEN 'active' ELSE 'inactive' END,
  subscription_expires_at=CASE WHEN p_badge_tier IN ('BLUE','SILVER','GOLD') THEN now()+interval '365 days' ELSE NULL END
 WHERE id=p_profile_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'profile_not_found' USING ERRCODE='P0002'; END IF;
END;
$function$;
REVOKE ALL ON FUNCTION public.admin_assign_verification_badge(text,public.verification_badge_tier) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_assign_verification_badge(text,public.verification_badge_tier) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.admin_review_verification_request(
 p_request_id uuid,p_verification_status text,p_payment_status text DEFAULT NULL::text,p_admin_notes text DEFAULT NULL::text
)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='pg_catalog','public'
AS $function$
DECLARE target_user_id text; target_badge public.verification_badge_tier; effective_payment text;
BEGIN
 IF NOT public.vibesync_jwt_session_active() OR NOT public.is_vibesync_super_admin() THEN
  RAISE EXCEPTION 'super_admin_required' USING ERRCODE='42501';
 END IF;
 IF p_verification_status NOT IN ('SUBMITTED','UNDER_REVIEW','APPROVED','REJECTED') THEN
  RAISE EXCEPTION 'invalid_verification_status' USING ERRCODE='22023';
 END IF;
 IF p_payment_status IS NOT NULL AND p_payment_status NOT IN ('PENDING','PAID','FAILED','REFUNDED') THEN
  RAISE EXCEPTION 'invalid_payment_status' USING ERRCODE='22023';
 END IF;
 UPDATE public.verification_requests SET verification_status=p_verification_status,
  payment_status=coalesce(p_payment_status,payment_status),admin_notes=coalesce(p_admin_notes,admin_notes),updated_at=now()
 WHERE id=p_request_id RETURNING user_id,badge_tier,payment_status INTO target_user_id,target_badge,effective_payment;
 IF NOT FOUND THEN RAISE EXCEPTION 'verification_request_not_found' USING ERRCODE='P0002'; END IF;
 IF p_verification_status='APPROVED' AND effective_payment='PAID' THEN
  PERFORM public.admin_assign_verification_badge(target_user_id,target_badge);
 END IF;
END;
$function$;
REVOKE ALL ON FUNCTION public.admin_review_verification_request(uuid,text,text,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_review_verification_request(uuid,text,text,text) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.staff_set_profile_moderation(p_profile_id text,p_action text,p_reason text DEFAULT ''::text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path='pg_catalog','public'
AS $function$
DECLARE target_auth uuid;
BEGIN
 IF NOT public.vibesync_jwt_session_active() OR NOT public.is_vibesync_super_admin() THEN
  RAISE EXCEPTION 'super_admin_required' USING ERRCODE='42501';
 END IF;
 SELECT auth_user_id INTO target_auth FROM public.profiles WHERE id=p_profile_id LIMIT 1;
 IF target_auth IS NULL THEN RAISE EXCEPTION 'profile_not_found' USING ERRCODE='P0002'; END IF;
 IF p_action='BAN' THEN
  UPDATE public.profiles SET account_status='BANNED',updated_at=(extract(epoch FROM now())*1000)::bigint WHERE id=p_profile_id;
 ELSIF p_action='UNBAN' THEN
  UPDATE public.profiles SET account_status='ACTIVE',updated_at=(extract(epoch FROM now())*1000)::bigint WHERE id=p_profile_id;
 ELSIF p_action='DELETE' THEN
  UPDATE public.profiles SET is_deleted=true,account_status='DELETED',updated_at=(extract(epoch FROM now())*1000)::bigint WHERE id=p_profile_id;
 ELSE
  RAISE EXCEPTION 'unsupported_moderation_action' USING ERRCODE='22023';
 END IF;
 INSERT INTO public.security_audit_events(actor_user_id,event_type,subject_id,severity,payload_digest)
 VALUES((SELECT auth.uid()),'SUPERADMIN_MODERATION_'||p_action,p_profile_id,'HIGH',
   encode(digest(coalesce(p_reason,''),'sha256'),'hex'));
 RETURN true;
END;
$function$;
REVOKE ALL ON FUNCTION public.staff_set_profile_moderation(text,text,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.staff_set_profile_moderation(text,text,text) TO authenticated, service_role;
REVOKE ALL ON FUNCTION public.is_platform_staff() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.is_platform_staff() TO authenticated, service_role;

NOTIFY pgrst, 'reload schema';
COMMIT;
