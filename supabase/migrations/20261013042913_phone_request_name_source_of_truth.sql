-- Phone-request banners must use the registered profile name, not client-controlled text.
begin;
create or replace function public.set_phone_request_requester_name()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    authoritative_name text;
begin
    select nullif(trim(p.name), '')
      into authoritative_name
      from public.profiles p
     where p.id = new.requester_profile_id;
    new.requester_name := coalesce(authoritative_name, 'VibeSync member');
    return new;
end;
$$;
drop trigger if exists phone_number_requests_authoritative_name on public.phone_number_requests;
create trigger phone_number_requests_authoritative_name
before insert on public.phone_number_requests
for each row execute function public.set_phone_request_requester_name();
revoke all on function public.set_phone_request_requester_name() from public, anon, authenticated;
commit;
