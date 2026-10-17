-- UUID-v5 subjects for custom JWTs, identity mapping, and participant-scoped call RLS.
-- This deliberately does not create auth.users rows; verified Truecaller sessions remain app-issued.
begin;

create table if not exists public.vibesync_auth_identity_map (
  auth_uid uuid primary key,
  profile_id text not null unique references public.profiles(id) on delete cascade,
  updated_at timestamptz not null default now()
);

create index if not exists vibesync_auth_identity_map_profile_id_idx
  on public.vibesync_auth_identity_map(profile_id);

alter table public.vibesync_auth_identity_map enable row level security;
revoke all on table public.vibesync_auth_identity_map from public, anon, authenticated;
grant all on table public.vibesync_auth_identity_map to service_role;

create or replace function public.vibesync_profile_id_from_auth_uid()
returns text
language sql
stable
security definer
set search_path = ''
as $$
  select m.profile_id
  from public.vibesync_auth_identity_map as m
  where m.auth_uid = (select auth.uid())
  limit 1;
$$;

revoke all on function public.vibesync_profile_id_from_auth_uid() from public, anon;
grant execute on function public.vibesync_profile_id_from_auth_uid() to authenticated, service_role;

-- The new sub is a UUID, while existing application tables deliberately keep profiles.id as text.
-- Prefer the separately signed profile_id claim and fall back to the private UUID -> profile map.
create or replace function public.vibesync_jwt_profile_id()
returns text
language sql
stable
set search_path = 'pg_catalog', 'public'
as $$
  select coalesce(
    nullif(
      (nullif(current_setting('request.jwt.claims', true), '')::jsonb)->>'profile_id',
      ''
    ),
    public.vibesync_profile_id_from_auth_uid()
  );
$$;

revoke all on function public.vibesync_jwt_profile_id() from public, anon;
grant execute on function public.vibesync_jwt_profile_id() to authenticated, service_role;

-- The same signed token must identify the current active VibeSync session.
create or replace function public.vibesync_jwt_session_active()
returns boolean
language sql
stable
security definer
set search_path = 'pg_catalog', 'public'
as $$
  select exists (
    select 1
    from public.vibesync_sessions as s
    where s.jwt_id = coalesce(
      nullif(current_setting('request.jwt.claim.jti', true), ''),
      nullif((nullif(current_setting('request.jwt.claims', true), '')::jsonb)->>'jti', '')
    )
      and s.profile_id = public.vibesync_jwt_profile_id()
      and s.expires_at > now()
  );
$$;

revoke all on function public.vibesync_jwt_session_active() from public, anon;
grant execute on function public.vibesync_jwt_session_active() to authenticated, service_role;

-- All call topics contain text profile IDs, not UUIDs. Resolve the authenticated UUID through the
-- private map before checking pair participants or the recipient's inbox topic.
drop policy if exists "vibesync_call_participants_receive_broadcast" on realtime.messages;
create policy "vibesync_call_participants_receive_broadcast"
on realtime.messages for select to authenticated
using (
  extension = 'broadcast'
  and split_part(realtime.topic(), ':', 1) = 'call'
  and (select auth.uid()) is not null
  and public.vibesync_jwt_session_active()
  and public.vibesync_profile_id_from_auth_uid() is not null
  and (
    split_part(realtime.topic(), ':', 2) = public.vibesync_profile_id_from_auth_uid()
    or split_part(realtime.topic(), ':', 3) = public.vibesync_profile_id_from_auth_uid()
  )
);

drop policy if exists "vibesync_call_participants_send_broadcast" on realtime.messages;
create policy "vibesync_call_participants_send_broadcast"
on realtime.messages for insert to authenticated
with check (
  extension = 'broadcast'
  and split_part(realtime.topic(), ':', 1) = 'call'
  and (select auth.uid()) is not null
  and public.vibesync_jwt_session_active()
  and public.vibesync_profile_id_from_auth_uid() is not null
  and (
    split_part(realtime.topic(), ':', 2) = public.vibesync_profile_id_from_auth_uid()
    or split_part(realtime.topic(), ':', 3) = public.vibesync_profile_id_from_auth_uid()
  )
);

drop policy if exists "vibesync_call_inbox_receive_own_invites" on realtime.messages;
create policy "vibesync_call_inbox_receive_own_invites"
on realtime.messages for select to authenticated
using (
  extension = 'broadcast'
  and split_part(realtime.topic(), ':', 1) = 'call-inbox'
  and (select auth.uid()) is not null
  and public.vibesync_jwt_session_active()
  and split_part(realtime.topic(), ':', 2) = public.vibesync_profile_id_from_auth_uid()
);

drop policy if exists "vibesync_call_inbox_authenticated_sender_only" on realtime.messages;
create policy "vibesync_call_inbox_authenticated_sender_only"
on realtime.messages for insert to authenticated
with check (
  extension = 'broadcast'
  and split_part(realtime.topic(), ':', 1) = 'call-inbox'
  and (select auth.uid()) is not null
  and public.vibesync_jwt_session_active()
  and split_part(realtime.topic(), ':', 2) =
    coalesce(payload ->> 'recipientProfileId', payload -> 'payload' ->> 'recipientProfileId')
  and coalesce(payload ->> 'senderProfileId', payload -> 'payload' ->> 'senderProfileId') =
    public.vibesync_profile_id_from_auth_uid()
  and coalesce(payload ->> 'recipientProfileId', payload -> 'payload' ->> 'recipientProfileId') <>
    public.vibesync_profile_id_from_auth_uid()
);

-- Reports are written by the validated service-role Edge Function. Authenticated clients may read
-- only their own participant row; the INSERT/UPDATE policies are defense in depth and are not
-- accompanied by client write grants, which would let clients spoof relay usage.
alter table public.call_bandwidth_reports enable row level security;
revoke all on table public.call_bandwidth_reports from anon, authenticated;
grant select on table public.call_bandwidth_reports to authenticated;
grant all on table public.call_bandwidth_reports to service_role;

drop policy if exists "vibesync_call_bandwidth_select_own_participant" on public.call_bandwidth_reports;
create policy "vibesync_call_bandwidth_select_own_participant"
on public.call_bandwidth_reports for select to authenticated
using (
  (select auth.uid()) is not null
  and public.vibesync_jwt_session_active()
  and profile_id = public.vibesync_profile_id_from_auth_uid()
  and (
    split_part(call_id, ':', 1) = public.vibesync_profile_id_from_auth_uid()
    or split_part(call_id, ':', 2) = public.vibesync_profile_id_from_auth_uid()
  )
);

drop policy if exists "vibesync_call_bandwidth_insert_own_participant" on public.call_bandwidth_reports;
create policy "vibesync_call_bandwidth_insert_own_participant"
on public.call_bandwidth_reports for insert to authenticated
with check (
  (select auth.uid()) is not null
  and public.vibesync_jwt_session_active()
  and profile_id = public.vibesync_profile_id_from_auth_uid()
  and (
    split_part(call_id, ':', 1) = public.vibesync_profile_id_from_auth_uid()
    or split_part(call_id, ':', 2) = public.vibesync_profile_id_from_auth_uid()
  )
);

drop policy if exists "vibesync_call_bandwidth_update_own_participant" on public.call_bandwidth_reports;
create policy "vibesync_call_bandwidth_update_own_participant"
on public.call_bandwidth_reports for update to authenticated
using (
  (select auth.uid()) is not null
  and public.vibesync_jwt_session_active()
  and profile_id = public.vibesync_profile_id_from_auth_uid()
  and (
    split_part(call_id, ':', 1) = public.vibesync_profile_id_from_auth_uid()
    or split_part(call_id, ':', 2) = public.vibesync_profile_id_from_auth_uid()
  )
)
with check (
  (select auth.uid()) is not null
  and public.vibesync_jwt_session_active()
  and profile_id = public.vibesync_profile_id_from_auth_uid()
  and (
    split_part(call_id, ':', 1) = public.vibesync_profile_id_from_auth_uid()
    or split_part(call_id, ':', 2) = public.vibesync_profile_id_from_auth_uid()
  )
);

notify pgrst, 'reload schema';
commit;
