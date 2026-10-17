-- Private, participant-scoped Supabase Realtime channels for call signaling.
-- SDP and ICE are broadcast-only ephemeral messages; do not add signaling tables.
begin;

drop policy if exists "vibesync_call_participants_receive_broadcast" on realtime.messages;
create policy "vibesync_call_participants_receive_broadcast"
on realtime.messages
for select
to authenticated
using (
  extension = 'broadcast'
  and (
    split_part(realtime.topic(), ':', 2) = auth.uid()::text
    or split_part(realtime.topic(), ':', 3) = auth.uid()::text
  )
);

drop policy if exists "vibesync_call_participants_send_broadcast" on realtime.messages;
create policy "vibesync_call_participants_send_broadcast"
on realtime.messages
for insert
to authenticated
with check (
  extension = 'broadcast'
  and (
    split_part(realtime.topic(), ':', 2) = auth.uid()::text
    or split_part(realtime.topic(), ':', 3) = auth.uid()::text
  )
);

drop policy if exists "vibesync_call_inbox_receive_own_invites" on realtime.messages;
create policy "vibesync_call_inbox_receive_own_invites"
on realtime.messages
for select
to authenticated
using (
  extension = 'broadcast'
  and split_part(realtime.topic(), ':', 2) = auth.uid()::text
);

drop policy if exists "vibesync_call_inbox_authenticated_sender_only" on realtime.messages;
create policy "vibesync_call_inbox_authenticated_sender_only"
on realtime.messages
for insert
to authenticated
with check (
  extension = 'broadcast'
  and split_part(realtime.topic(), ':', 1) = 'call-inbox'
  and split_part(realtime.topic(), ':', 2) =
    coalesce(payload ->> 'recipientProfileId', payload -> 'payload' ->> 'recipientProfileId')
  and coalesce(payload ->> 'senderProfileId', payload -> 'payload' ->> 'senderProfileId') =
    auth.uid()::text
);

create table if not exists public.call_bandwidth_reports (
  profile_id text not null,
  call_id text not null,
  bytes_sent bigint not null default 0 check (bytes_sent >= 0),
  bytes_received bigint not null default 0 check (bytes_received >= 0),
  candidate_type text not null check (candidate_type = 'relay'),
  reported_at timestamptz not null default now(),
  primary key (profile_id, call_id)
);

comment on table public.call_bandwidth_reports is
  'Post-call relay-only bandwidth counters. Contains no SDP, ICE candidates, or media payloads.';

alter table public.call_bandwidth_reports enable row level security;
revoke all on table public.call_bandwidth_reports from anon, authenticated;
grant all on table public.call_bandwidth_reports to service_role;

commit;
