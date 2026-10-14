begin;

-- Keep the public key available under both the legacy profile ID and UUID-v5 auth identity.
alter table public.profiles
  add column if not exists public_key text not null default '',
  add column if not exists public_identity_key text not null default '';

create table if not exists public.user_public_keys (
  profile_id text primary key references public.profiles(id) on delete cascade,
  auth_uid uuid unique,
  public_key text not null,
  updated_at timestamptz not null default now()
);

alter table public.user_public_keys enable row level security;
revoke all on table public.user_public_keys from public, anon, authenticated;

-- Backfill existing published Tink public keys. Private key material is never accepted from clients.
insert into public.user_public_keys(profile_id, auth_uid, public_key, updated_at)
select p.id, m.auth_uid, coalesce(nullif(p.public_key, ''), nullif(p.public_identity_key, '')), now()
  from public.profiles p
  left join public.vibesync_auth_identity_map m on m.profile_id = p.id
 where coalesce(nullif(p.public_key, ''), nullif(p.public_identity_key, '')) <> ''
on conflict (profile_id) do update
  set auth_uid = coalesce(excluded.auth_uid, public.user_public_keys.auth_uid),
      public_key = excluded.public_key,
      updated_at = excluded.updated_at;

create or replace function public.publish_my_tink_public_key(p_public_key text)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $function$
declare
  owner_id text := public.vibesync_jwt_profile_id();
  owner_uid uuid;
  key_doc jsonb;
begin
  if not public.vibesync_jwt_session_active() or owner_id is null or btrim(owner_id) = '' then
    raise exception 'verified_session_required' using errcode = '42501';
  end if;

  if p_public_key is null or length(btrim(p_public_key)) < 40 or length(p_public_key) > 100000 then
    raise exception 'invalid_tink_public_key' using errcode = '22023';
  end if;
  begin
    key_doc := btrim(p_public_key)::jsonb;
  exception when others then
    raise exception 'invalid_tink_public_key_json' using errcode = '22023';
  end;
  if jsonb_typeof(key_doc) <> 'object' or jsonb_typeof(key_doc -> 'key') <> 'array' then
    raise exception 'invalid_tink_public_keyset' using errcode = '22023';
  end if;
  if exists (
    select 1
      from jsonb_array_elements(key_doc -> 'key') as key_item
     where upper(coalesce(key_item -> 'keyData' ->> 'keyMaterialType', '')) in ('SYMMETRIC', 'ASYMMETRIC_PRIVATE')
  ) then
    raise exception 'private_key_material_not_allowed' using errcode = '22023';
  end if;

  select m.auth_uid into owner_uid
    from public.vibesync_auth_identity_map m
   where lower(m.profile_id) = lower(owner_id)
   limit 1;

  update public.profiles
     set public_key = btrim(p_public_key),
         public_identity_key = btrim(p_public_key),
         updated_at = now()
   where id = owner_id;
  if not found then
    raise exception 'owner_profile_not_found' using errcode = 'P0002';
  end if;

  insert into public.user_public_keys(profile_id, auth_uid, public_key, updated_at)
  values (owner_id, owner_uid, btrim(p_public_key), now())
  on conflict (profile_id) do update
    set auth_uid = coalesce(excluded.auth_uid, public.user_public_keys.auth_uid),
        public_key = excluded.public_key,
        updated_at = excluded.updated_at;

  return jsonb_build_object('ok', true, 'profile_id', owner_id, 'updated_at', now());
end;
$function$;

revoke all on function public.publish_my_tink_public_key(text) from public, anon;
grant execute on function public.publish_my_tink_public_key(text) to authenticated;

-- Resolve a UUID-v5 sub claim or a legacy 16-character hex profile ID without granting table
-- access to the private identity map or the phone lookup index. Only the public key is returned.
create or replace function public.resolve_public_tink_key(
  p_profile_id text,
  p_phone_e164 text default null
) returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $function$
declare
  requested_id text := btrim(coalesce(p_profile_id, ''));
  canonical_profile_id text;
  normalized_phone text := btrim(coalesce(p_phone_e164, ''));
  phone_digits text;
  key_value text;
begin
  if not public.vibesync_jwt_session_active() or public.vibesync_jwt_profile_id() is null then
    raise exception 'verified_session_required' using errcode = '42501';
  end if;

  if requested_id = '' then
    return jsonb_build_object('ok', false, 'profile_id', '', 'public_key', '');
  end if;

  -- Direct lookup handles the canonical legacy text ID (normally 16 hexadecimal characters).
  select p.id into canonical_profile_id
    from public.profiles p
   where lower(p.id::text) = lower(requested_id)
   limit 1;

  -- A UUID-v5 auth subject is not profiles.id; map it to the legacy profile identity.
  if canonical_profile_id is null and requested_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    select m.profile_id into canonical_profile_id
      from public.vibesync_auth_identity_map m
     where m.auth_uid = requested_id::uuid
     limit 1;
  end if;

  -- Explicitly recheck the reverse map for case-normalized legacy identifiers before phone fallback.
  if canonical_profile_id is null then
    select m.profile_id into canonical_profile_id
      from public.vibesync_auth_identity_map m
     where lower(m.profile_id) = lower(requested_id)
     limit 1;
  end if;

  -- Last-resort recovery uses the server-only E.164 index; it is not exposed to PostgREST clients.
  if canonical_profile_id is null and normalized_phone <> '' then
    phone_digits := regexp_replace(normalized_phone, '[^0-9]', '', 'g');
    if normalized_phone !~ '^\+[1-9][0-9]{7,14}$' and length(phone_digits) between 8 and 15 then
      normalized_phone := '+' || phone_digits;
    end if;
    if normalized_phone ~ '^\+[1-9][0-9]{7,14}$' then
      select idx.profile_id into canonical_profile_id
        from public.phone_lookup_index idx
       where idx.phone_e164 = normalized_phone
       limit 1;
    end if;
  end if;

  if canonical_profile_id is null then
    return jsonb_build_object('ok', false, 'profile_id', '', 'public_key', '');
  end if;

  select coalesce(
           nullif(k.public_key, ''),
           nullif(p.public_key, ''),
           nullif(p.public_identity_key, '')
         )
    into key_value
    from public.profiles p
    left join public.user_public_keys k on k.profile_id = p.id
   where p.id = canonical_profile_id
   limit 1;

  return jsonb_build_object(
    'ok', coalesce(key_value, '') <> '',
    'profile_id', canonical_profile_id,
    'public_key', coalesce(key_value, '')
  );
end;
$function$;

revoke all on function public.resolve_public_tink_key(text, text) from public, anon;
grant execute on function public.resolve_public_tink_key(text, text) to authenticated;

commit;
