BEGIN;

-- public.profiles.phone_hash was dropped by the retired phone-hash pipeline migration.
-- Keep existing profile-protection behavior without assigning the removed record field;
-- that stale record-field assignment caused SQLSTATE 42703 on authenticated profile updates.
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
      NEW.badge_purchased := false;
      NEW.subscription_status := 'inactive';
      NEW.verified_badge := 'NONE';
      NEW.is_deleted := false;
      NEW.account_status := 'ACTIVE';
    ELSE
      NEW.auth_user_id := OLD.auth_user_id;
      NEW.is_verified := OLD.is_verified;
      NEW.badge_purchased := OLD.badge_purchased;
      NEW.subscription_status := OLD.subscription_status;
      NEW.verified_badge := OLD.verified_badge;
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

COMMIT;
