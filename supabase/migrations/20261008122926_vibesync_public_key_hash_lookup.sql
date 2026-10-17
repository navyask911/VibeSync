-- Privacy-preserving public-key recovery for an E2EE contact.
create or replace function public.get_public_identity_key_by_phone_hash(p_hash text)
returns text
language plpgsql
security definer
set search_path=pg_catalog,public,extensions
as $$
declare current_profile_id text := public.vibesync_current_profile_id();
  result_key text;
begin
  if current_profile_id is null then raise exception 'vibesync session required'; end if;
  if p_hash is null or p_hash !~ '^[0-9a-fA-F]{64}$' then return null; end if;

  select p.public_identity_key
    into result_key
  from public.phone_sync ps
  join public.profiles p on p.id::text = ps.user_id
  where ps.phone_hash = decode(p_hash,'hex')
    and coalesce(p.is_deleted,false)=false
    and coalesce(p.account_status,'ACTIVE')='ACTIVE'
    and p.id <> current_profile_id
  limit 1;

  return nullif(result_key,'');
end;
$$;

revoke all on function public.get_public_identity_key_by_phone_hash(text) from public;
grant execute on function public.get_public_identity_key_by_phone_hash(text) to anon;