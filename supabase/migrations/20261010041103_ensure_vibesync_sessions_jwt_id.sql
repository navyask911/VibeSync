-- Ensure the Truecaller JWT session identifier is queryable by PostgREST.
-- The JWT session-active RLS function validates the JWT jti against this column.
begin;

alter table public.vibesync_sessions
  add column if not exists jwt_id text;

create unique index if not exists idx_vibesync_sessions_jwt_id
  on public.vibesync_sessions(jwt_id)
  where jwt_id is not null;

notify pgrst, 'reload schema';

commit;
