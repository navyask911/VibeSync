begin;

-- A timestamp-backed receipt lets the sender render authoritative blue double ticks.
alter table public.chat_messages
  add column if not exists read_at timestamptz;

create or replace function public.capture_chat_message_read_at()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
  if new.is_read is true and new.read_at is null then
    new.read_at := now();
  end if;
  return new;
end;
$$;

drop trigger if exists chat_messages_capture_read_at on public.chat_messages;
create trigger chat_messages_capture_read_at
before insert or update of is_read on public.chat_messages
for each row
execute function public.capture_chat_message_read_at();

commit;
