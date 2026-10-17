-- Historical idempotent copy kept to match an earlier successful deployment entry
-- (version 20261016030000_story_owner_server_timestamps) recorded by Supabase.
-- The canonical first application is also kept at version 20261015180019.
-- Prevent story inserts from failing because of device-clock skew or media-upload latency.
-- Ownership remains bound to the verified profile and the current signed JWT session.
begin;

-- This helper must bypass the session table's service-role-only RLS policy to validate
-- the caller's jti. It returns only a boolean and never exposes session rows or token values.
create or replace function public.vibesync_jwt_session_active()
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public
as $vibesync_session_check$
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
$vibesync_session_check$;

revoke all on function public.vibesync_jwt_session_active() from public, anon;
grant execute on function public.vibesync_jwt_session_active() to authenticated, service_role;

alter table public.status_stories
  alter column "createdAt" set default ((extract(epoch from now()) * 1000)::bigint),
  alter column "expiresAt" set default (((extract(epoch from now()) * 1000)::bigint) + 86400000);

create or replace function public.enforce_status_story_owner_and_timestamps()
returns trigger
language plpgsql
security invoker
set search_path = pg_catalog, public
as $vibesync_story_trigger$
declare
  active_profile_id text := public.vibesync_jwt_profile_id();
  server_now_ms bigint := ((extract(epoch from statement_timestamp()) * 1000)::bigint);
begin
  if not public.vibesync_jwt_session_active() or active_profile_id is null then
    raise exception 'verified active VibeSync session required for story upload'
      using errcode = '42501';
  end if;

  if new."userId" is distinct from active_profile_id then
    raise exception 'story userId must match the active VibeSync profile'
      using errcode = '42501';
  end if;

  new."createdAt" := server_now_ms;
  new."expiresAt" := server_now_ms + 86400000;
  return new;
end;
$vibesync_story_trigger$;

drop trigger if exists trg_status_story_owner_timestamps on public.status_stories;
create trigger trg_status_story_owner_timestamps
before insert on public.status_stories
for each row execute function public.enforce_status_story_owner_and_timestamps();

revoke insert on table public.status_stories from anon, public;
grant insert on table public.status_stories to authenticated;

notify pgrst, 'reload schema';
commit;
