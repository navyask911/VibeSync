begin;

-- Preserve the original privacy guard while also protecting paid verification entitlements.
create or replace function public.protect_profile_fields()
returns trigger
language plpgsql
security invoker
set search_path = pg_catalog, public
as $function$
begin
  if auth.role() = 'authenticated' and current_user = 'authenticated' then
    if TG_OP = 'INSERT' then
      NEW.auth_user_id := auth.uid();
      NEW.is_verified := false;
      NEW.badge_purchased := false;
      NEW.subscription_status := 'inactive';
      NEW.verified_badge := 'NONE';
      NEW.is_deleted := false;
      NEW.account_status := 'ACTIVE';
    else
      NEW.auth_user_id := OLD.auth_user_id;
      NEW.is_verified := OLD.is_verified;
      NEW.badge_purchased := OLD.badge_purchased;
      NEW.subscription_status := OLD.subscription_status;
      NEW.verified_badge := OLD.verified_badge;
      NEW.is_deleted := OLD.is_deleted;
      NEW.account_status := OLD.account_status;
    end if;
    NEW.phone_number := NULL;
    NEW.phone_number_e164 := NULL;
    NEW.clean_phone := NULL;
    NEW.phone_hash := NULL;
  end if;
  return NEW;
end;
$function$;

notify pgrst, 'reload schema';
commit;
