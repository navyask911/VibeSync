-- Verification badges and super-admin authorization. VibeSync profile IDs are text hashes.
begin;
do $$
begin
  create type public.verification_badge_tier as enum ('NONE','BLUE','SILVER','GOLD','GREY_GOV');
exception when duplicate_object then null;
end $$;

alter table public.profiles
  add column if not exists verified_badge public.verification_badge_tier not null default 'NONE',
  add column if not exists is_government_entity boolean not null default false;

create table if not exists public.super_admins (
  profile_id text primary key references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  created_by text
);

-- Older production schema stores super-admin identities as (user_id, phone) rather than profile_id.
-- Keep the migration compatible with both deployed shapes; the lookup below checks all available columns.
alter table public.super_admins add column if not exists profile_id text;
alter table public.super_admins add column if not exists user_id text;
alter table public.super_admins add column if not exists phone text;

create table if not exists public.verification_requests (
  id uuid primary key default gen_random_uuid(),
  user_id text not null references public.profiles(id) on delete cascade,
  badge_tier public.verification_badge_tier not null check (badge_tier in ('BLUE','SILVER','GOLD')),
  applicant_email text not null check (length(trim(applicant_email)) between 5 and 320),
  documents_url text[] not null default '{}'::text[],
  payment_status text not null default 'PENDING' check (payment_status in ('PENDING','PAID','FAILED','REFUNDED')),
  verification_status text not null default 'SUBMITTED' check (verification_status in ('SUBMITTED','UNDER_REVIEW','APPROVED','REJECTED')),
  admin_notes text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists idx_verification_requests_user_created on public.verification_requests(user_id, created_at desc);
create index if not exists idx_verification_requests_status_created on public.verification_requests(verification_status, payment_status, created_at desc);

alter table public.super_admins enable row level security;
alter table public.verification_requests enable row level security;
revoke all on table public.super_admins from anon, authenticated, public;
revoke all on table public.verification_requests from anon, authenticated, public;
grant select (verified_badge, is_government_entity) on public.profiles to authenticated;
grant select on public.verification_requests to authenticated;
grant insert (user_id, badge_tier, applicant_email, documents_url) on public.verification_requests to authenticated;

create or replace function public.is_vibesync_super_admin()
returns boolean language sql stable security definer set search_path = pg_catalog, public
as $$
  select exists (
    select 1
    from public.super_admins sa
    where (nullif(sa.profile_id, '') = public.vibesync_jwt_profile_id())
       or (nullif(sa.user_id, '') = public.vibesync_jwt_profile_id())
       or (
         nullif(sa.phone, '') is not null
         and sa.phone = nullif(
           (nullif(current_setting('request.jwt.claims', true), '')::jsonb)->>'phone',
           ''
         )
       )
  );
$$;
revoke all on function public.is_vibesync_super_admin() from public, anon;
grant execute on function public.is_vibesync_super_admin() to authenticated;

drop policy if exists verification_requests_select_own_or_admin on public.verification_requests;
create policy verification_requests_select_own_or_admin on public.verification_requests
for select to authenticated using (
  (public.vibesync_jwt_session_active() and user_id = public.vibesync_jwt_profile_id())
  or public.is_vibesync_super_admin()
);
drop policy if exists verification_requests_insert_own_purchase_tier on public.verification_requests;
create policy verification_requests_insert_own_purchase_tier on public.verification_requests
for insert to authenticated with check (
  public.vibesync_jwt_session_active()
  and user_id = public.vibesync_jwt_profile_id()
  and badge_tier in ('BLUE','SILVER','GOLD')
);

create or replace function public.admin_assign_verification_badge(
  p_profile_id text, p_badge_tier public.verification_badge_tier
) returns void language plpgsql security definer set search_path = pg_catalog, public
as $$
begin
  if not public.vibesync_jwt_session_active() or not public.is_vibesync_super_admin() then
    raise exception 'super_admin_required' using errcode = '42501';
  end if;
  update public.profiles set verified_badge = p_badge_tier,
    is_government_entity = (p_badge_tier = 'GREY_GOV') where id = p_profile_id;
  if not found then raise exception 'profile_not_found' using errcode = 'P0002'; end if;
end;
$$;
revoke all on function public.admin_assign_verification_badge(text, public.verification_badge_tier) from public, anon;
grant execute on function public.admin_assign_verification_badge(text, public.verification_badge_tier) to authenticated;

create or replace function public.admin_review_verification_request(
  p_request_id uuid, p_verification_status text, p_payment_status text default null, p_admin_notes text default null
) returns void language plpgsql security definer set search_path = pg_catalog, public
as $$
declare target_user_id text; target_badge public.verification_badge_tier; effective_payment text;
begin
  if not public.vibesync_jwt_session_active() or not public.is_vibesync_super_admin() then
    raise exception 'super_admin_required' using errcode = '42501';
  end if;
  if p_verification_status not in ('SUBMITTED','UNDER_REVIEW','APPROVED','REJECTED') then
    raise exception 'invalid_verification_status' using errcode = '22023';
  end if;
  if p_payment_status is not null and p_payment_status not in ('PENDING','PAID','FAILED','REFUNDED') then
    raise exception 'invalid_payment_status' using errcode = '22023';
  end if;
  update public.verification_requests set
    verification_status = p_verification_status,
    payment_status = coalesce(p_payment_status, payment_status),
    admin_notes = coalesce(p_admin_notes, admin_notes),
    updated_at = now()
  where id = p_request_id
  returning user_id, badge_tier, payment_status into target_user_id, target_badge, effective_payment;
  if not found then raise exception 'verification_request_not_found' using errcode = 'P0002'; end if;
  if p_verification_status = 'APPROVED' and effective_payment = 'PAID' then
    perform public.admin_assign_verification_badge(target_user_id, target_badge);
  end if;
end;
$$;
revoke all on function public.admin_review_verification_request(uuid, text, text, text) from public, anon;
grant execute on function public.admin_review_verification_request(uuid, text, text, text) to authenticated;

-- Seed only after checking the true verified VibeSync profile ID in Supabase SQL editor.
-- INSERT INTO public.super_admins(profile_id) VALUES ('<verified-profile-id>');
commit;
