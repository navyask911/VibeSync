begin;

alter table public.profiles
  add column if not exists badge_purchased boolean not null default false;
alter table public.profiles alter column is_verified set default false;
update public.profiles set is_verified = false where is_verified is null;
alter table public.profiles alter column is_verified set not null;

-- Authenticated users cannot self-assign verification, subscription or paid badge fields.
create or replace function public.protect_profile_fields()
returns trigger
language plpgsql
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
  end if;
  return NEW;
end;
$function$;

-- Preserve the existing filtered/rounded public view and append entitlement columns in-order.
create or replace view public.vibesync_profiles_public as
select
  id, name, bio, age, gender, city, country, country_flag, place, occupation,
  qualification, avatar_emoji, avatar_url, marital_status, account_status,
  is_verified, is_deleted, updated_at, public_identity_key,
  case
    when location_visibility = 'EVERYONE'
      and latitude between -90 and 90 and longitude between -180 and 180
      and not (latitude = 0 and longitude = 0)
      then round(latitude::numeric, 3)::double precision
    else null
  end as latitude,
  case
    when location_visibility = 'EVERYONE'
      and latitude between -90 and 90 and longitude between -180 and 180
      and not (latitude = 0 and longitude = 0)
      then round(longitude::numeric, 3)::double precision
    else null
  end as longitude,
  subscription_status,
  badge_purchased
from public.profiles
where coalesce(is_deleted, false) = false
  and coalesce(account_status, 'ACTIVE') = 'ACTIVE'
  and (
    (subscription_status = 'active'
      and subscription_expires_at > now()
      and tier_badge = any(array['crown_gold', 'crown_silver', 'crown_blue']))
    or (is_public_utility = true and tier_badge = 'gray')
  );

revoke all on public.profiles from anon;
grant select on public.vibesync_profiles_public to anon, authenticated;

-- Canonical messages are stored in encrypted public.chat_messages. FCM is only a server-side wake-up.
drop trigger if exists trg_chat_messages_fcm_edge_webhook on public.chat_messages;
drop trigger if exists trg_chat_messages_firebase_webhook on public.chat_messages;
create trigger trg_chat_messages_fcm_edge_webhook
after insert on public.chat_messages
for each row
when (
  coalesce(new.is_encrypted, false) = true
  and coalesce(new.ciphertext, '') like 'TINK_ENC:%'
  and coalesce(new.dispatch_status, 'PENDING') = 'PENDING'
  and coalesce(new.receiver_id, '') <> ''
  and upper(coalesce(new.media_type, 'TEXT')) <> 'DIAGNOSTIC'
)
execute function public.forward_chat_row_change_to_fcm_edge();

notify pgrst, 'reload schema';
commit;
