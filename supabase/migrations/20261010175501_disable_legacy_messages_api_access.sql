-- public.messages is the legacy plaintext-shaped table; VibeSync sends only through chat_messages RPCs.
-- No database routine references public.messages. Lock it out from API roles to prevent accidental regressions.
begin;

alter table public.messages enable row level security;

do $$
declare policy_row record;
begin
  for policy_row in
    select policyname from pg_policies
    where schemaname = 'public' and tablename = 'messages'
  loop
    execute format('drop policy %I on public.messages', policy_row.policyname);
  end loop;
end;
$$;

revoke all on table public.messages from anon, authenticated, public;

notify pgrst, 'reload schema';
commit;
