-- Direct E.164 contact matching. The raw phone index is only available to privileged server code.
create table if not exists public.phone_lookup_index (
    profile_id text primary key references public.profiles(id) on delete cascade,
    phone_e164 text not null,
    updated_at timestamptz not null default now(),
    constraint phone_lookup_index_e164_format
      check (phone_e164 ~ '^[+][1-9][0-9]{7,14}$')
);

create unique index if not exists idx_phone_lookup_index_phone_e164
    on public.phone_lookup_index using btree (phone_e164);
create index if not exists idx_phone_lookup_index_profile_id
    on public.phone_lookup_index using btree (profile_id);

alter table public.phone_lookup_index enable row level security;
revoke all on table public.phone_lookup_index from public, anon, authenticated;
grant all on table public.phone_lookup_index to service_role;

-- Backfill valid E.164 records. If legacy profiles share a number, keep one deterministic owner.
with candidates as (
    select
        p.id::text as profile_id,
        case
            when p.phone_number_e164 ~ '^[+][1-9][0-9]{7,14}$' then p.phone_number_e164
            when p.phone_number ~ '^[+][1-9][0-9]{7,14}$' then p.phone_number
            when p.clean_phone ~ '^[+][1-9][0-9]{7,14}$' then p.clean_phone
            else null
        end as phone_e164
    from public.profiles p
    where coalesce(p.is_deleted, false) = false
      and coalesce(p.account_status, 'ACTIVE') = 'ACTIVE'
),
unique_owners as (
    select distinct on (phone_e164) profile_id, phone_e164
    from candidates
    where phone_e164 is not null
    order by phone_e164, profile_id
)
insert into public.phone_lookup_index(profile_id, phone_e164, updated_at)
select profile_id, phone_e164, now()
from unique_owners
on conflict (profile_id) do update
set phone_e164 = excluded.phone_e164, updated_at = now();

-- The verify-truecaller Edge Function maintains this index using its service-role client
-- only after Truecaller confirms the phone number. Client writes to public.profiles cannot set it.

-- Authenticated clients submit E.164 numbers only in the HTTPS POST body.
-- Results expose canonical profile IDs, not client-derived contact hashes.
create or replace function public.match_contacts_by_phone_numbers(phone_numbers text[])
returns table (
    phone_e164 text,
    id text,
    name text,
    profile_photo_url text,
    avatar_emoji text
)
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    caller_profile_id text := public.vibesync_current_profile_id();
begin
    if caller_profile_id is null or btrim(caller_profile_id) = '' then
        raise exception 'verified VibeSync session required' using errcode = '28000';
    end if;
    if coalesce(array_length(phone_numbers, 1), 0) > 1000 then
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
        from unnest(coalesce(phone_numbers, array[]::text[])) as u(raw_phone)
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

revoke all on function public.match_contacts_by_phone_numbers(text[]) from public, anon, authenticated;
grant execute on function public.match_contacts_by_phone_numbers(text[]) to anon;

-- Retire legacy client access to blind/hash lookup procedures.
do $$
begin
    if to_regprocedure('public.match_contacts(text[])') is not null then
        execute 'revoke all on function public.match_contacts(text[]) from public, anon, authenticated';
    end if;
    if to_regprocedure('public.resolve_profile_id_by_phone_hash(text)') is not null then
        execute 'revoke all on function public.resolve_profile_id_by_phone_hash(text) from public, anon, authenticated';
    end if;
    if to_regprocedure('public.get_public_identity_key_by_phone_hash(text)') is not null then
        execute 'revoke all on function public.get_public_identity_key_by_phone_hash(text) from public, anon, authenticated';
    end if;
end;
$$;
