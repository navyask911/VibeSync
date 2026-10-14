begin;

create table if not exists public.truecaller_login_nonces (
  request_nonce text primary key,
  used_at timestamptz not null default now()
);

alter table public.truecaller_login_nonces enable row level security;

revoke all on public.truecaller_login_nonces from anon;
revoke all on public.truecaller_login_nonces from authenticated;
revoke all on public.truecaller_login_nonces from public;

create index if not exists idx_truecaller_login_nonces_used_at
  on public.truecaller_login_nonces(used_at);

commit;
