BEGIN;

-- Profile discovery is server-owned, separate from Android READ_CONTACTS.
ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS is_discoverable boolean NOT NULL DEFAULT true;

CREATE OR REPLACE FUNCTION public.upsert_my_profile(payload jsonb)
 RETURNS boolean
 LANGUAGE plpgsql
 SET search_path TO 'public', 'pg_temp'
AS $function$
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
    is_discoverable = CASE
      WHEN payload ? 'is_discoverable'
       AND jsonb_typeof(payload->'is_discoverable') = 'boolean'
      THEN (payload->>'is_discoverable')::boolean
      ELSE is_discoverable
    END,
    latitude = case
      when coalesce(payload->>'latitude','') ~ '^-?[0-9]+([.][0-9]+)?$' then
        case
          when (payload->>'latitude')::double precision between -90 and 90
          then (payload->>'latitude')::double precision
          else latitude
        end
      else latitude
    end,
    longitude = case
      when coalesce(payload->>'longitude','') ~ '^-?[0-9]+([.][0-9]+)?$' then
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
$function$;

CREATE OR REPLACE FUNCTION private.match_contacts_by_phone_numbers_secure(p_phone_numbers text[])
 RETURNS TABLE(phone_e164 text, id text, name text, profile_photo_url text, avatar_emoji text, is_verified boolean)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog'
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
     AND coalesce(p.is_discoverable,true)=true
   LIMIT 1000;
END;
$function$;

NOTIFY pgrst, 'reload schema';
COMMIT;
