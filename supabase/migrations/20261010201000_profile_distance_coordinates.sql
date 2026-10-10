-- Coordinate-based approximate distance. Raw coordinates are returned as rounded values
-- only when a member explicitly opts into location_visibility='EVERYONE'.
begin;

alter table public.profiles
  add column if not exists latitude double precision not null default 0,
  add column if not exists longitude double precision not null default 0,
  add column if not exists location_visibility text not null default 'NO_ONE';

do $$
begin
  alter table public.profiles
    add constraint profiles_location_visibility_check
    check (location_visibility in ('EVERYONE','PHONE_CONTACTS','FRIENDS_ONLY','SELECTED_PERSONS','NO_ONE'));
exception when duplicate_object then null;
end $$;

create or replace function public.upsert_my_profile(payload jsonb)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  current_profile_id text := public.vibesync_current_profile_id();
  changed_count integer;
begin
  if current_profile_id is null then
    raise exception 'vibesync session required';
  end if;

  update public.profiles
  set
    name = left(coalesce(nullif(payload->>'name',''), name), 60),
    bio = left(coalesce(nullif(payload->>'bio',''), bio), 160),
    age = greatest(13, least(coalesce((payload->>'age')::integer, age), 120)),
    gender = coalesce(nullif(payload->>'gender',''), gender),
    city = coalesce(nullif(payload->>'city',''), city),
    country = coalesce(nullif(payload->>'country',''), country),
    country_flag = coalesce(nullif(payload->>'country_flag',''), country_flag),
    place = coalesce(payload->>'place', place),
    occupation = coalesce(nullif(payload->>'occupation',''), occupation),
    qualification = coalesce(nullif(payload->>'qualification',''), qualification),
    marital_status = coalesce(nullif(payload->>'marital_status',''), marital_status),
    avatar_emoji = coalesce(nullif(payload->>'avatar_emoji',''), avatar_emoji),
    avatar_url = coalesce(payload->>'avatar_url', avatar_url),
    public_identity_key = coalesce(nullif(payload->>'public_identity_key',''), public_identity_key),
    latitude = case
      when coalesce(payload->>'latitude','') ~ '^-?[0-9]+([.][0-9]+)?
    longitude = case
      when coalesce(payload->>'longitude','') ~ '^-?[0-9]+([.][0-9]+)?
    location_visibility = case
      when payload->>'location_visibility' in ('EVERYONE','PHONE_CONTACTS','FRIENDS_ONLY','SELECTED_PERSONS','NO_ONE')
      then payload->>'location_visibility'
      else location_visibility
    end,
    updated_at = (extract(epoch from now()) * 1000)::bigint
  where id = current_profile_id
    and coalesce(is_deleted,false) = false;

  get diagnostics changed_count = row_count;
  return changed_count > 0;
end;
$$;

revoke all on function public.upsert_my_profile(jsonb) from public;
grant execute on function public.upsert_my_profile(jsonb) to anon, authenticated;

-- Preserve all existing public-profile columns/order and append approximate coordinates.
create or replace view public.vibesync_profiles_public as
select
  id, name, bio, age, gender, city, country, country_flag, place, occupation,
  qualification, avatar_emoji, avatar_url, marital_status, account_status,
  is_verified, is_deleted, updated_at, public_identity_key,
  case
    when location_visibility = 'EVERYONE'
      and latitude between -90 and 90
      and longitude between -180 and 180
      and not (latitude = 0 and longitude = 0)
    then round(latitude::numeric, 3)::double precision
    else null
  end as latitude,
  case
    when location_visibility = 'EVERYONE'
      and latitude between -90 and 90
      and longitude between -180 and 180
      and not (latitude = 0 and longitude = 0)
    then round(longitude::numeric, 3)::double precision
    else null
  end as longitude
from public.profiles
where coalesce(is_deleted,false) = false
  and coalesce(account_status,'ACTIVE') = 'ACTIVE';

revoke all on public.profiles from anon;
grant select on public.vibesync_profiles_public to anon;

commit;
 then
        case
          when (payload->>'latitude')::double precision between -90 and 90
          then (payload->>'latitude')::double precision
          else latitude
        end
      else latitude
    end,
    longitude = case
      when coalesce(payload->>'longitude','') ~ '^-?[0-9]+([.][0-9]+)?$'
        and (payload->>'longitude')::double precision between -180 and 180
      then (payload->>'longitude')::double precision
      else longitude
    end,
    location_visibility = case
      when payload->>'location_visibility' in ('EVERYONE','PHONE_CONTACTS','FRIENDS_ONLY','SELECTED_PERSONS','NO_ONE')
      then payload->>'location_visibility'
      else location_visibility
    end,
    updated_at = (extract(epoch from now()) * 1000)::bigint
  where id = current_profile_id
    and coalesce(is_deleted,false) = false;

  get diagnostics changed_count = row_count;
  return changed_count > 0;
end;
$$;

revoke all on function public.upsert_my_profile(jsonb) from public;
grant execute on function public.upsert_my_profile(jsonb) to anon, authenticated;

-- Preserve all existing public-profile columns/order and append approximate coordinates.
create or replace view public.vibesync_profiles_public as
select
  id, name, bio, age, gender, city, country, country_flag, place, occupation,
  qualification, avatar_emoji, avatar_url, marital_status, account_status,
  is_verified, is_deleted, updated_at, public_identity_key,
  case
    when location_visibility = 'EVERYONE'
      and latitude between -90 and 90
      and longitude between -180 and 180
      and not (latitude = 0 and longitude = 0)
    then round(latitude::numeric, 3)::double precision
    else null
  end as latitude,
  case
    when location_visibility = 'EVERYONE'
      and latitude between -90 and 90
      and longitude between -180 and 180
      and not (latitude = 0 and longitude = 0)
    then round(longitude::numeric, 3)::double precision
    else null
  end as longitude
from public.profiles
where coalesce(is_deleted,false) = false
  and coalesce(account_status,'ACTIVE') = 'ACTIVE';

revoke all on public.profiles from anon;
grant select on public.vibesync_profiles_public to anon;

commit;
 then
        case
          when (payload->>'longitude')::double precision between -180 and 180
          then (payload->>'longitude')::double precision
          else longitude
        end
      else longitude
    end,
    location_visibility = case
      when payload->>'location_visibility' in ('EVERYONE','PHONE_CONTACTS','FRIENDS_ONLY','SELECTED_PERSONS','NO_ONE')
      then payload->>'location_visibility'
      else location_visibility
    end,
    updated_at = (extract(epoch from now()) * 1000)::bigint
  where id = current_profile_id
    and coalesce(is_deleted,false) = false;

  get diagnostics changed_count = row_count;
  return changed_count > 0;
end;
$$;

revoke all on function public.upsert_my_profile(jsonb) from public;
grant execute on function public.upsert_my_profile(jsonb) to anon, authenticated;

-- Preserve all existing public-profile columns/order and append approximate coordinates.
create or replace view public.vibesync_profiles_public as
select
  id, name, bio, age, gender, city, country, country_flag, place, occupation,
  qualification, avatar_emoji, avatar_url, marital_status, account_status,
  is_verified, is_deleted, updated_at, public_identity_key,
  case
    when location_visibility = 'EVERYONE'
      and latitude between -90 and 90
      and longitude between -180 and 180
      and not (latitude = 0 and longitude = 0)
    then round(latitude::numeric, 3)::double precision
    else null
  end as latitude,
  case
    when location_visibility = 'EVERYONE'
      and latitude between -90 and 90
      and longitude between -180 and 180
      and not (latitude = 0 and longitude = 0)
    then round(longitude::numeric, 3)::double precision
    else null
  end as longitude
from public.profiles
where coalesce(is_deleted,false) = false
  and coalesce(account_status,'ACTIVE') = 'ACTIVE';

revoke all on public.profiles from anon;
grant select on public.vibesync_profiles_public to anon;

commit;
 then
        case
          when (payload->>'latitude')::double precision between -90 and 90
          then (payload->>'latitude')::double precision
          else latitude
        end
      else latitude
    end,
    longitude = case
      when coalesce(payload->>'longitude','') ~ '^-?[0-9]+([.][0-9]+)?$'
        and (payload->>'longitude')::double precision between -180 and 180
      then (payload->>'longitude')::double precision
      else longitude
    end,
    location_visibility = case
      when payload->>'location_visibility' in ('EVERYONE','PHONE_CONTACTS','FRIENDS_ONLY','SELECTED_PERSONS','NO_ONE')
      then payload->>'location_visibility'
      else location_visibility
    end,
    updated_at = (extract(epoch from now()) * 1000)::bigint
  where id = current_profile_id
    and coalesce(is_deleted,false) = false;

  get diagnostics changed_count = row_count;
  return changed_count > 0;
end;
$$;

revoke all on function public.upsert_my_profile(jsonb) from public;
grant execute on function public.upsert_my_profile(jsonb) to anon, authenticated;

-- Preserve all existing public-profile columns/order and append approximate coordinates.
create or replace view public.vibesync_profiles_public as
select
  id, name, bio, age, gender, city, country, country_flag, place, occupation,
  qualification, avatar_emoji, avatar_url, marital_status, account_status,
  is_verified, is_deleted, updated_at, public_identity_key,
  case
    when location_visibility = 'EVERYONE'
      and latitude between -90 and 90
      and longitude between -180 and 180
      and not (latitude = 0 and longitude = 0)
    then round(latitude::numeric, 3)::double precision
    else null
  end as latitude,
  case
    when location_visibility = 'EVERYONE'
      and latitude between -90 and 90
      and longitude between -180 and 180
      and not (latitude = 0 and longitude = 0)
    then round(longitude::numeric, 3)::double precision
    else null
  end as longitude
from public.profiles
where coalesce(is_deleted,false) = false
  and coalesce(account_status,'ACTIVE') = 'ACTIVE';

revoke all on public.profiles from anon;
grant select on public.vibesync_profiles_public to anon;

commit;
