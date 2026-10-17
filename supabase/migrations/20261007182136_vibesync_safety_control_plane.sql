begin;

create table if not exists public.user_blocks (
  blocker_user_id uuid not null references auth.users(id) on delete cascade,
  blocked_profile_id text not null,
  reason text not null default 'USER_BLOCK',
  created_at timestamptz not null default now(),
  primary key (blocker_user_id, blocked_profile_id)
);
alter table public.user_blocks enable row level security;
drop policy if exists user_blocks_select_own on public.user_blocks;
drop policy if exists user_blocks_insert_own on public.user_blocks;
drop policy if exists user_blocks_delete_own on public.user_blocks;
create policy user_blocks_select_own on public.user_blocks for select to authenticated using (blocker_user_id=(select auth.uid()));
create policy user_blocks_insert_own on public.user_blocks for insert to authenticated with check (blocker_user_id=(select auth.uid()));
create policy user_blocks_delete_own on public.user_blocks for delete to authenticated using (blocker_user_id=(select auth.uid()));
revoke all on public.user_blocks from anon, public;
grant select,insert,delete on public.user_blocks to authenticated;

create table if not exists public.safety_reports (
  report_id uuid primary key default gen_random_uuid(),
  reporter_user_id uuid not null references auth.users(id) on delete cascade,
  reported_profile_id text not null,
  reason text not null,
  details text not null default '',
  status text not null default 'PENDING' check (status in ('PENDING','UNDER_REVIEW','ACTIONED','DISMISSED','PRESERVED')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists idx_safety_reports_reported_status on public.safety_reports(reported_profile_id,status,created_at desc);
create index if not exists idx_safety_reports_reporter on public.safety_reports(reporter_user_id,created_at desc);
alter table public.safety_reports enable row level security;
drop policy if exists safety_reports_insert_own on public.safety_reports;
drop policy if exists safety_reports_select_own on public.safety_reports;
create policy safety_reports_insert_own on public.safety_reports for insert to authenticated with check (reporter_user_id=(select auth.uid()));
create policy safety_reports_select_own on public.safety_reports for select to authenticated using (reporter_user_id=(select auth.uid()));
revoke all on public.safety_reports from anon, public;
grant insert,select on public.safety_reports to authenticated;

create table if not exists public.security_audit_events (
  event_id uuid primary key default gen_random_uuid(),
  actor_user_id uuid references auth.users(id) on delete set null,
  event_type text not null,
  subject_id text,
  request_id text,
  severity text not null default 'INFO' check (severity in ('INFO','WARNING','HIGH','CRITICAL')),
  payload_digest text,
  created_at timestamptz not null default now()
);
create index if not exists idx_security_audit_created on public.security_audit_events(created_at desc);
create index if not exists idx_security_audit_subject on public.security_audit_events(subject_id,created_at desc);
alter table public.security_audit_events enable row level security;
revoke all on public.security_audit_events from anon, authenticated, public;

create table if not exists public.lawful_requests (
  request_id uuid primary key default gen_random_uuid(),
  jurisdiction text not null,
  authority_name text not null,
  authority_contact text not null,
  legal_basis text not null,
  case_reference text not null,
  subject_identifier text not null,
  requested_categories text[] not null default '{}',
  requested_start timestamptz,
  requested_end timestamptz,
  preservation_required boolean not null default false,
  status text not null default 'RECEIVED' check (status in ('RECEIVED','VERIFYING','PRESERVED','APPROVED','PARTIAL','DENIED','FULFILLED','CLOSED')),
  reviewer_user_id uuid references auth.users(id) on delete set null,
  decision_notes text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists idx_lawful_requests_status on public.lawful_requests(status,created_at desc);
alter table public.lawful_requests enable row level security;
revoke all on public.lawful_requests from anon, authenticated, public;

create table if not exists public.preservation_holds (
  hold_id uuid primary key default gen_random_uuid(),
  request_id uuid references public.lawful_requests(request_id) on delete cascade,
  subject_identifier text not null,
  categories text[] not null default '{}',
  starts_at timestamptz not null default now(),
  expires_at timestamptz,
  active boolean not null default true,
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now()
);
create index if not exists idx_preservation_holds_active on public.preservation_holds(subject_identifier,active,expires_at);
alter table public.preservation_holds enable row level security;
revoke all on public.preservation_holds from anon, authenticated, public;

create or replace function public.block_user(p_blocked_profile_id text,p_reason text default 'USER_BLOCK')
returns boolean language plpgsql security definer set search_path=pg_catalog,public as $$
begin
  if (select auth.uid()) is null then raise exception 'authentication required'; end if;
  insert into public.user_blocks(blocker_user_id,blocked_profile_id,reason)
  values((select auth.uid()),p_blocked_profile_id,left(coalesce(p_reason,'USER_BLOCK'),120))
  on conflict (blocker_user_id,blocked_profile_id) do update set reason=excluded.reason;
  return true;
end; $$;
revoke all on function public.block_user(text,text) from public, anon;
grant execute on function public.block_user(text,text) to authenticated;

create or replace function public.unblock_user(p_blocked_profile_id text)
returns boolean language plpgsql security definer set search_path=pg_catalog,public as $$
begin
  if (select auth.uid()) is null then raise exception 'authentication required'; end if;
  delete from public.user_blocks where blocker_user_id=(select auth.uid()) and blocked_profile_id=p_blocked_profile_id;
  return true;
end; $$;
revoke all on function public.unblock_user(text) from public, anon;
grant execute on function public.unblock_user(text) to authenticated;

create or replace function public.submit_safety_report(p_reported_profile_id text,p_reason text,p_details text default '')
returns uuid language plpgsql security definer set search_path=pg_catalog,public as $$
declare rid uuid;
begin
  if (select auth.uid()) is null then raise exception 'authentication required'; end if;
  if coalesce(p_reported_profile_id,'')='' or coalesce(p_reason,'')='' then raise exception 'report target and reason required'; end if;
  insert into public.safety_reports(reporter_user_id,reported_profile_id,reason,details)
  values((select auth.uid()),p_reported_profile_id,left(p_reason,120),left(coalesce(p_details,''),4000))
  returning report_id into rid;
  insert into public.security_audit_events(actor_user_id,event_type,subject_id,severity)
  values((select auth.uid()),'SAFETY_REPORT_CREATED',p_reported_profile_id,'WARNING');
  return rid;
end; $$;
revoke all on function public.submit_safety_report(text,text,text) from public, anon;
grant execute on function public.submit_safety_report(text,text,text) to authenticated;

create or replace function public.staff_set_profile_moderation(p_profile_id text,p_action text,p_reason text default '')
returns boolean language plpgsql security definer set search_path=pg_catalog,public as $$
declare target_auth uuid;
begin
  if not public.is_platform_staff() then raise exception 'platform staff authorization required'; end if;
  select auth_user_id into target_auth from public.profiles where id=p_profile_id limit 1;
  if target_auth is null then raise exception 'profile not found'; end if;
  if p_action='BAN' then
    update public.profiles set account_status='BANNED',updated_at=(extract(epoch from now())*1000)::bigint where id=p_profile_id;
  elsif p_action='UNBAN' then
    update public.profiles set account_status='ACTIVE',updated_at=(extract(epoch from now())*1000)::bigint where id=p_profile_id;
  elsif p_action='DELETE' then
    update public.profiles set is_deleted=true,account_status='DELETED',updated_at=(extract(epoch from now())*1000)::bigint where id=p_profile_id;
  else raise exception 'unsupported moderation action'; end if;
  insert into public.security_audit_events(actor_user_id,event_type,subject_id,severity,payload_digest)
  values((select auth.uid()),'STAFF_MODERATION_'||p_action,p_profile_id,'HIGH',encode(digest(coalesce(p_reason,''),'sha256'),'hex'));
  return true;
end; $$;
revoke all on function public.staff_set_profile_moderation(text,text,text) from public, anon;
grant execute on function public.staff_set_profile_moderation(text,text,text) to authenticated;

commit;