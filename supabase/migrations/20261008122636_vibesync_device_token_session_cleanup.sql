-- VibeSync custom-session compatibility cleanup for legacy device token rows.

alter table public.device_tokens alter column user_id drop not null;

create unique index if not exists idx_device_tokens_profile_token
  on public.device_tokens(profile_id, fcm_token)
  where profile_id is not null;

create or replace function public.register_device_token(p_fcm_token text,p_platform text default 'android')
returns boolean
language plpgsql
security definer
set search_path=pg_catalog,public
as $$
declare current_profile_id text:=public.vibesync_current_profile_id();
begin
  if current_profile_id is null then raise exception 'vibesync session required'; end if;
  if p_fcm_token is null or length(trim(p_fcm_token))<20 then raise exception 'invalid fcm token'; end if;

  insert into public.device_tokens(profile_id,user_id,fcm_token,platform,updated_at,created_at)
  values(current_profile_id,null,trim(p_fcm_token),coalesce(nullif(p_platform,''),'android'),now(),now())
  on conflict (profile_id,fcm_token) do update
    set platform=excluded.platform, updated_at=now();
  return true;
end;
$$;

revoke all on function public.register_device_token(text,text) from public;
grant execute on function public.register_device_token(text,text) to anon;

create or replace function public.delete_encrypted_message(p_message_id text,p_match_id text default '')
returns boolean
language plpgsql
security definer
set search_path=pg_catalog,public
as $$
declare current_profile_id text:=public.vibesync_current_profile_id(); updated_count integer;
begin
  if current_profile_id is null then raise exception 'vibesync session required'; end if;
  update public.chat_messages
  set is_deleted=true,
      ciphertext='TINK_ENC:DELETED',
      dispatch_status='REDACTED'
  where (id::text=p_message_id or message_id=p_message_id)
    and (p_match_id='' or match_id=p_match_id)
    and sender_id=current_profile_id;
  get diagnostics updated_count=row_count;
  return updated_count>0;
end;
$$;

revoke all on function public.delete_encrypted_message(text,text) from public;
grant execute on function public.delete_encrypted_message(text,text) to anon;