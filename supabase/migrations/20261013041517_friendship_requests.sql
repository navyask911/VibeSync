-- Cross-device sent/received friendship requests. Only request participants can read their rows.
begin;
create table if not exists public.friendship_requests (
    id text primary key,
    sender_id text not null references public.profiles(id) on delete cascade,
    sender_name text not null default '',
    sender_age integer not null default 24,
    sender_occupation text not null default '',
    sender_city text not null default '',
    sender_country text not null default '',
    sender_country_flag text not null default '🇺🇸',
    sender_bio text not null default '',
    sender_avatar_emoji text not null default '☕',
    sender_color_start bigint not null default 4294925922,
    sender_color_end bigint not null default 4294941030,
    sender_interests text not null default '',
    receiver_id text not null references public.profiles(id) on delete cascade,
    receiver_name text not null default '',
    status text not null default 'PENDING'
      check (status in ('PENDING','ACCEPTED','DECLINED')),
    timestamp bigint not null default ((extract(epoch from now()) * 1000)::bigint),
    accepted_timestamp bigint,
    is_swipe_down_initiated boolean not null default true,
    check (sender_id <> receiver_id)
);
create index if not exists idx_friendship_requests_receiver_status_time
    on public.friendship_requests(receiver_id, status, timestamp desc);
create index if not exists idx_friendship_requests_sender_status_time
    on public.friendship_requests(sender_id, status, timestamp desc);
alter table public.friendship_requests enable row level security;
revoke all on public.friendship_requests from anon, authenticated, public;
grant select, insert, delete on public.friendship_requests to authenticated;
grant update (status, accepted_timestamp) on public.friendship_requests to authenticated;
drop policy if exists friendship_requests_select_participants on public.friendship_requests;
create policy friendship_requests_select_participants on public.friendship_requests
  for select to authenticated
  using (
    public.vibesync_jwt_session_active()
    and (sender_id = public.vibesync_jwt_profile_id()
      or receiver_id = public.vibesync_jwt_profile_id())
  );
drop policy if exists friendship_requests_insert_sender on public.friendship_requests;
create policy friendship_requests_insert_sender on public.friendship_requests
  for insert to authenticated
  with check (
    public.vibesync_jwt_session_active()
    and sender_id = public.vibesync_jwt_profile_id()
    and status = 'PENDING'
    and sender_id <> receiver_id
  );
drop policy if exists friendship_requests_update_receiver on public.friendship_requests;
create policy friendship_requests_update_receiver on public.friendship_requests
  for update to authenticated
  using (
    public.vibesync_jwt_session_active()
    and receiver_id = public.vibesync_jwt_profile_id()
    and status = 'PENDING'
  )
  with check (
    public.vibesync_jwt_session_active()
    and receiver_id = public.vibesync_jwt_profile_id()
    and status in ('ACCEPTED','DECLINED')
  );
drop policy if exists friendship_requests_delete_sender on public.friendship_requests;
create policy friendship_requests_delete_sender on public.friendship_requests
  for delete to authenticated
  using (
    public.vibesync_jwt_session_active()
    and sender_id = public.vibesync_jwt_profile_id()
    and status = 'PENDING'
  );
notify pgrst, 'reload schema';
commit;
