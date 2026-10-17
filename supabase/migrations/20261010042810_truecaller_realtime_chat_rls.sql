-- Allow Truecaller-authenticated sessions to receive only chat rows where
-- they are sender or recipient. Realtime was published but chat_messages had
-- RLS enabled with no SELECT policy, so subscribers could connect without
-- receiving Postgres Changes rows.
begin;

create or replace function public.vibesync_jwt_profile_id()
returns text
language sql
stable
set search_path = pg_catalog, public
as $$
  select coalesce(
    nullif(current_setting('request.jwt.claim.sub', true), ''),
    nullif((nullif(current_setting('request.jwt.claims', true), '')::jsonb)->>'sub', '')
  );
$$;

revoke all on function public.vibesync_jwt_profile_id() from public;
grant execute on function public.vibesync_jwt_profile_id() to authenticated;

create or replace function public.vibesync_jwt_session_active()
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public
as $$
  select exists (
    select 1
    from public.vibesync_sessions s
    where s.jwt_id = coalesce(
      nullif(current_setting('request.jwt.claim.jti', true), ''),
      nullif((nullif(current_setting('request.jwt.claims', true), '')::jsonb)->>'jti', '')
    )
      and s.profile_id = public.vibesync_jwt_profile_id()
      and s.expires_at > now()
  );
$$;

revoke all on function public.vibesync_jwt_session_active() from public;
grant execute on function public.vibesync_jwt_session_active() to authenticated;

grant select on public.chat_messages to authenticated;
alter table public.chat_messages enable row level security;

drop policy if exists chat_messages_truecaller_realtime_select_participant
  on public.chat_messages;
create policy chat_messages_truecaller_realtime_select_participant
  on public.chat_messages
  for select to authenticated
  using (
    public.vibesync_jwt_session_active()
    and (
      sender_id = public.vibesync_jwt_profile_id()
      or receiver_id = public.vibesync_jwt_profile_id()
    )
  );

notify pgrst, 'reload schema';
commit;
