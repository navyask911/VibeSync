-- Realtime authorization must resolve identity from the signed JWT claims.
-- The legacy x-vibesync-session header helper is for PostgREST RPCs and is not
-- used as a Realtime JWT identity fallback.
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

notify pgrst, 'reload schema';
commit;
