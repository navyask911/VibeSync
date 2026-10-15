-- Allow authenticated RLS predicates to resolve only the caller's mapped profile ID.
-- The identity map remains inaccessible through direct table queries.
begin;

create or replace function public.vibesync_profile_id_from_auth_uid()
returns text
language sql
stable
security definer
set search_path = pg_catalog, public, auth
as $profile_map$
  select m.profile_id
  from public.vibesync_auth_identity_map as m
  where m.auth_uid = (select auth.uid())
  limit 1;
$profile_map$;

revoke all on function public.vibesync_profile_id_from_auth_uid() from public, anon;
grant execute on function public.vibesync_profile_id_from_auth_uid() to authenticated, service_role;

notify pgrst, 'reload schema';
commit;
