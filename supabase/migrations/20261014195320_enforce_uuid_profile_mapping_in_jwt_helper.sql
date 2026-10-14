-- Make the signed UUID subject resolve through the trusted UUID -> legacy profile mapping.
-- This prevents profile-level RLS/RPC helpers from using a profile_id claim without a map entry.
begin;

create or replace function public.vibesync_jwt_profile_id()
returns text
language sql
stable
set search_path = 'pg_catalog', 'public'
as $$
  select public.vibesync_profile_id_from_auth_uid()
  where public.vibesync_profile_id_from_auth_uid() =
    nullif(
      (nullif(current_setting('request.jwt.claims', true), '')::jsonb)->>'profile_id',
      ''
    );
$$;

revoke all on function public.vibesync_jwt_profile_id() from public, anon;
grant execute on function public.vibesync_jwt_profile_id() to authenticated, service_role;

notify pgrst, 'reload schema';
commit;
