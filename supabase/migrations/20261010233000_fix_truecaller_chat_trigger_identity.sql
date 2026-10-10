-- Fix chat inserts for Truecaller-backed profiles whose JWT sub is TEXT, not auth.users UUID.
-- The encrypted-message RPC intentionally keeps legacy sender_user_id/receiver_user_id nullable
-- and writes authoritative identities to sender_id/receiver_id. Never cast an opaque sub to UUID.
begin;

create or replace function public.normalize_chat_ciphertext()
returns trigger
language plpgsql
security invoker
set search_path = pg_catalog, public
as $$
declare
  request_subject text;
  request_claims text;
begin
  if auth.role() = 'authenticated' then
    if not new.is_encrypted then
      raise exception 'encrypted messages required';
    end if;

    if coalesce(new.ciphertext, '') = '' and coalesce(new.text, '') <> '' then
      new.ciphertext := new.text;
    end if;
    new.text := null;
    new.message := null;
    new.message_text := null;
    new.sender_phone := null;
    new.receiver_phone := null;

    if new.sender_user_id is null then
      request_subject := nullif(current_setting('request.jwt.claim.sub', true), '');
      if request_subject is null then
        request_claims := nullif(current_setting('request.jwt.claims', true), '');
        if request_claims is not null then
          request_subject := nullif(request_claims::jsonb ->> 'sub', '');
        end if;
      end if;

      -- UUID compatibility is retained for genuine Supabase Auth sessions. Opaque text
      -- profile IDs remain in sender_id and can no longer abort the INSERT with invalid UUID syntax.
      if request_subject ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
        new.sender_user_id := request_subject::uuid;
      end if;
    end if;

    if new.timestamp is null then
      new.timestamp := (extract(epoch from now()) * 1000)::bigint;
    end if;
  end if;
  return new;
end;
$$;

comment on function public.normalize_chat_ciphertext() is
  'Stores encrypted chat payloads without casting Truecaller TEXT profile IDs to auth.users UUIDs.';

notify pgrst, 'reload schema';
commit;
