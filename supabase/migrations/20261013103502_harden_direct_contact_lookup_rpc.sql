-- Keep the PostgREST-facing RPC as SECURITY INVOKER; put privileged index access
-- in a private, non-exposed schema and verify the VibeSync custom session there.
create schema if not exists private;
revoke all on schema private from public;
grant usage on schema private to anon;

create or replace function private.match_contacts_by_phone_numbers_secure(p_phone_numbers text[])
returns table (
    phone_e164 text,
    id text,
    name text,
    profile_photo_url text,
    avatar_emoji text
)
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    caller_profile_id text := public.vibesync_current_profile_id();
begin
    if caller_profile_id is null or btrim(caller_profile_id) = '' then
        raise exception 'verified VibeSync session required' using errcode = '28000';
    end if;
    if coalesce(array_length(p_phone_numbers, 1), 0) > 1000 then
        raise exception 'too many phone numbers' using errcode = '22023';
    end if;

    return query
    with supplied as (
        select distinct
            case
                when btrim(u.raw_phone) like '+%'
                     and length(d.digits) between 8 and 15
                     and left(d.digits, 1) <> '0' then '+' || d.digits
                when d.digits ~ '^0[0-9]{10}$' then '+91' || right(d.digits, 10)
                when d.digits ~ '^[0-9]{10}$' then '+91' || d.digits
                when d.digits ~ '^00[1-9][0-9]{7,14}$' then '+' || substr(d.digits, 3)
                when d.digits ~ '^[1-9][0-9]{7,14}$' then '+' || d.digits
                else null
            end as normalized_phone
        from unnest(coalesce(p_phone_numbers, array[]::text[])) as u(raw_phone)
        cross join lateral (
            select regexp_replace(btrim(u.raw_phone), '[^0-9]', '', 'g') as digits
        ) as d
        where u.raw_phone is not null and btrim(u.raw_phone) <> ''
    )
    select idx.phone_e164,
           p.id::text,
           coalesce(p.name, 'VibeSync Member')::text,
           coalesce(p.avatar_url, '')::text,
           coalesce(p.avatar_emoji, '✨')::text
    from supplied s
    join public.phone_lookup_index idx on idx.phone_e164 = s.normalized_phone
    join public.profiles p on p.id::text = idx.profile_id
    where s.normalized_phone is not null
      and idx.profile_id <> caller_profile_id
      and coalesce(p.is_deleted, false) = false
      and coalesce(p.account_status, 'ACTIVE') = 'ACTIVE'
    limit 1000;
end;
$$;

revoke all on function private.match_contacts_by_phone_numbers_secure(text[]) from public, anon, authenticated;
grant execute on function private.match_contacts_by_phone_numbers_secure(text[]) to anon;

create or replace function public.match_contacts_by_phone_numbers(phone_numbers text[])
returns table (
    phone_e164 text,
    id text,
    name text,
    profile_photo_url text,
    avatar_emoji text
)
language sql
security invoker
set search_path = pg_catalog
as $$
    select *
    from private.match_contacts_by_phone_numbers_secure(phone_numbers);
$$;

revoke all on function public.match_contacts_by_phone_numbers(text[]) from public, anon, authenticated;
grant execute on function public.match_contacts_by_phone_numbers(text[]) to anon;
