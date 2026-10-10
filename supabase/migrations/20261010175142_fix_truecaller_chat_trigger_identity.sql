-- Repair the encrypted chat write contract and the Truecaller TEXT-profile trigger path.
-- The deployed send_encrypted_message RPC uses ON CONFLICT(message_id), which requires a
-- UNIQUE constraint/index on message_id. The table's UUID primary key on id does not satisfy it.
begin;

do $$
begin
  if exists (
    select message_id
    from public.chat_messages
    where message_id is not null
    group by message_id
    having count(*) > 1
  ) then
    raise exception 'Cannot add unique chat message_id index: duplicate non-null message_id values already exist.';
  end if;
end;
$$;

create unique index if not exists idx_chat_messages_message_id_unique
  on public.chat_messages (message_id);

-- Truecaller sessions use opaque TEXT profile IDs, unlike auth.users UUIDs.
-- Do not invoke auth.uid() for these claims; preserve UUID compatibility only when the sub is UUID-shaped.
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
  if coalesce(new.is_encrypted, false) is not true then
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
        begin
          request_subject := nullif(request_claims::jsonb ->> 'sub', '');
        exception when others then
          request_subject := null;
        end;
      end if;
    end if;

    if request_subject ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
      new.sender_user_id := request_subject::uuid;
    end if;
  end if;

  if new.timestamp is null then
    new.timestamp := (extract(epoch from now()) * 1000)::bigint;
  end if;
  return new;
end;
$$;

comment on function public.normalize_chat_ciphertext() is
  'Enforces encrypted-only rows and avoids casting Truecaller TEXT profile IDs to auth.users UUIDs.';

notify pgrst, 'reload schema';
commit;
