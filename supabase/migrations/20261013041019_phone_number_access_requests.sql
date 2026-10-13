-- Explicit, session-scoped phone disclosure requests. RLS limits reads and writes to participants.
begin;
create table if not exists public.phone_number_requests (
    id uuid primary key default gen_random_uuid(),
    requester_profile_id text not null references public.profiles(id) on delete cascade,
    target_profile_id text not null references public.profiles(id) on delete cascade,
    requester_name text not null default '',
    status text not null default 'PENDING'
      check (status in ('PENDING','ACCEPTED','BLOCKED')),
    created_at timestamptz not null default now(),
    responded_at timestamptz,
    check (requester_profile_id <> target_profile_id)
);
create index if not exists idx_phone_number_requests_target_pending
    on public.phone_number_requests(target_profile_id, created_at desc)
    where status = 'PENDING';
create unique index if not exists idx_phone_number_requests_one_pending_pair
    on public.phone_number_requests(requester_profile_id, target_profile_id)
    where status = 'PENDING';
alter table public.phone_number_requests enable row level security;
revoke all on public.phone_number_requests from anon, authenticated, public;
grant select, insert on public.phone_number_requests to authenticated;
grant update (status, responded_at) on public.phone_number_requests to authenticated;
drop policy if exists phone_number_requests_select_participants on public.phone_number_requests;
create policy phone_number_requests_select_participants on public.phone_number_requests
  for select to authenticated
  using (
    public.vibesync_jwt_session_active()
    and (requester_profile_id = public.vibesync_jwt_profile_id()
      or target_profile_id = public.vibesync_jwt_profile_id())
  );
drop policy if exists phone_number_requests_insert_requester on public.phone_number_requests;
create policy phone_number_requests_insert_requester on public.phone_number_requests
  for insert to authenticated
  with check (
    public.vibesync_jwt_session_active()
    and requester_profile_id = public.vibesync_jwt_profile_id()
    and status = 'PENDING'
    and not exists (
      select 1 from public.phone_number_requests existing
      where existing.requester_profile_id = public.vibesync_jwt_profile_id()
        and existing.target_profile_id = phone_number_requests.target_profile_id
        and existing.status = 'BLOCKED'
    )
  );
drop policy if exists phone_number_requests_update_target on public.phone_number_requests;
create policy phone_number_requests_update_target on public.phone_number_requests
  for update to authenticated
  using (
    public.vibesync_jwt_session_active()
    and target_profile_id = public.vibesync_jwt_profile_id()
    and status = 'PENDING'
  )
  with check (
    public.vibesync_jwt_session_active()
    and target_profile_id = public.vibesync_jwt_profile_id()
    and status in ('ACCEPTED','BLOCKED')
  );
notify pgrst, 'reload schema';
commit;
