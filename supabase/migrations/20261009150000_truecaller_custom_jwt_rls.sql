-- RLS-bound custom JWT access for Truecaller profile IDs that do not exist in auth.users.
-- Prefer the verified sub claim text; auth.uid() is a legacy UUID fallback only.

begin;

alter table public.vibesync_sessions add column if not exists jwt_id text;
create unique index if not exists idx_vibesync_sessions_jwt_id
  on public.vibesync_sessions(jwt_id) where jwt_id is not null;

create or replace function public.vibesync_jwt_profile_id()
returns text
language sql
stable
set search_path = pg_catalog, public, auth
as $$
  select coalesce(
    nullif(current_setting('request.jwt.claim.sub', true), ''),
    nullif((nullif(current_setting('request.jwt.claims', true), '')::jsonb)->>'sub', ''),
    auth.uid()::text
  );
$$;
revoke all on function public.vibesync_jwt_profile_id() from public;
grant execute on function public.vibesync_jwt_profile_id() to authenticated;

create or replace function public.vibesync_jwt_session_active()
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public
as $$
  select exists (
    select 1 from public.vibesync_sessions s
    where s.jwt_id = coalesce(
      nullif(current_setting('request.jwt.claim.jti', true), ''),
      nullif((nullif(current_setting('request.jwt.claims', true), '')::jsonb)->>'jti', '')
    )
      and s.profile_id = public.vibesync_jwt_profile_id()
      and s.expires_at > now()
  );
$$;
revoke all on function public.vibesync_jwt_session_active() from public;
grant execute on function public.vibesync_jwt_session_active() to authenticated;

do $$
declare target_table text; policy_row record;
begin
  foreach target_table in array array[
    'profiles', 'registered_accounts', 'chat_messages', 'phone_sync',
    'user_contacts', 'matched_contacts', 'device_tokens', 'messages',
    'user_blocks', 'safety_reports'
  ] loop
    if to_regclass('public.' || target_table) is not null then
      for policy_row in
        select policyname from pg_policies
        where schemaname = 'public' and tablename = target_table
      loop
        execute format('drop policy %I on public.%I', policy_row.policyname, target_table);
      end loop;
    end if;
  end loop;
end;
$$;

alter table public.profiles enable row level security;
alter table public.registered_accounts enable row level security;
alter table public.chat_messages enable row level security;

revoke all on table public.profiles from anon, authenticated, public;
grant select (
  id, name, bio, age, gender, city, country, country_flag, place, occupation,
  qualification, marital_status, avatar_emoji, avatar_url, account_status,
  is_verified, is_deleted, updated_at, public_identity_key
) on public.profiles to authenticated;

create policy profiles_truecaller_jwt_select_own on public.profiles
  for select to authenticated
  using (
    public.vibesync_jwt_session_active()
    and id::text = public.vibesync_jwt_profile_id()
    and coalesce(is_deleted, false) = false
  );
grant select on public.vibesync_profiles_public to authenticated;

revoke all on table public.registered_accounts from anon, authenticated, public;
grant select (id, phone_number, user_name, is_verified, is_deleted, account_status)
  on public.registered_accounts to authenticated;
create policy registered_accounts_truecaller_jwt_select_own on public.registered_accounts
  for select to authenticated
  using (
    public.vibesync_jwt_session_active()
    and id::text = public.vibesync_jwt_profile_id()
  );

revoke all on table public.chat_messages from anon, authenticated, public;
grant select (
  message_id, match_id, sender_id, receiver_id, ciphertext, timestamp,
  is_delivered, is_read, media_url, media_type, voice_duration_seconds, created_at
) on public.chat_messages to authenticated;
grant insert (
  message_id, match_id, sender_id, receiver_id, ciphertext, is_encrypted,
  media_url, media_type, voice_duration_seconds, timestamp, idempotency_key
) on public.chat_messages to authenticated;
grant update (is_delivered, is_read, delivered_at, read_at, dispatch_status)
  on public.chat_messages to authenticated;
grant delete on public.chat_messages to authenticated;

create policy chat_messages_truecaller_jwt_select_participant on public.chat_messages
  for select to authenticated
  using (
    public.vibesync_jwt_session_active()
    and (sender_id = public.vibesync_jwt_profile_id()
      or receiver_id = public.vibesync_jwt_profile_id())
  );
create policy chat_messages_truecaller_jwt_insert_sender on public.chat_messages
  for insert to authenticated
  with check (
    public.vibesync_jwt_session_active()
    and sender_id = public.vibesync_jwt_profile_id()
    and coalesce(receiver_id, '') <> ''
    and is_encrypted = true
    and coalesce(ciphertext, '') <> ''
  );
create policy chat_messages_truecaller_jwt_update_participant on public.chat_messages
  for update to authenticated
  using (
    public.vibesync_jwt_session_active()
    and (sender_id = public.vibesync_jwt_profile_id()
      or receiver_id = public.vibesync_jwt_profile_id())
  )
  with check (
    public.vibesync_jwt_session_active()
    and (sender_id = public.vibesync_jwt_profile_id()
      or receiver_id = public.vibesync_jwt_profile_id())
  );
create policy chat_messages_truecaller_jwt_delete_participant on public.chat_messages
  for delete to authenticated
  using (
    public.vibesync_jwt_session_active()
    and (sender_id = public.vibesync_jwt_profile_id()
      or receiver_id = public.vibesync_jwt_profile_id())
  );

grant execute on function public.vibesync_current_profile_id() to anon, authenticated;
grant execute on function public.upsert_my_profile(jsonb) to anon, authenticated;
grant execute on function public.match_contacts(text[]) to anon, authenticated;
grant execute on function public.send_encrypted_message(text,text,text,text,text,text,integer) to anon, authenticated;
grant execute on function public.ack_message_delivery(text) to anon, authenticated;
grant execute on function public.ack_message_read(text) to anon, authenticated;
grant execute on function public.register_device_token(text,text) to anon, authenticated;
grant execute on function public.delete_encrypted_message(text,text) to anon, authenticated;

do $$
declare target_table text;
begin
  foreach target_table in array array[
    'phone_sync', 'user_contacts', 'matched_contacts', 'device_tokens', 'messages'
  ] loop
    if to_regclass('public.' || target_table) is not null then
      execute format('alter table public.%I enable row level security', target_table);
      execute format('revoke all on table public.%I from anon, authenticated, public', target_table);
    end if;
  end loop;
end;
$$;

create policy chat_messages_legacy_session_select_participant on public.chat_messages
  for select to anon
  using (
    public.vibesync_current_profile_id() is not null
    and (sender_id = public.vibesync_current_profile_id()
      or receiver_id = public.vibesync_current_profile_id())
  );
grant select (
  message_id, match_id, sender_id, receiver_id, ciphertext, timestamp,
  is_delivered, is_read, media_url, media_type, voice_duration_seconds, created_at
) on public.chat_messages to anon;

create or replace function public.protect_profile_fields()
returns trigger
language plpgsql
security invoker
set search_path = pg_catalog, public, auth
as $$
declare request_subject text;
begin
  if auth.role() = 'authenticated' then
    request_subject := public.vibesync_jwt_profile_id();
    if tg_op = 'INSERT' then
      if request_subject ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
        new.auth_user_id := request_subject::uuid;
      else
        new.auth_user_id := null;
      end if;
      new.is_verified := false;
      new.is_deleted := false;
      new.account_status := 'ACTIVE';
    else
      new.auth_user_id := old.auth_user_id;
      new.is_verified := old.is_verified;
      new.is_deleted := old.is_deleted;
      new.account_status := old.account_status;
    end if;
    new.phone_number := null;
    new.phone_number_e164 := null;
    new.clean_phone := null;
    new.phone_hash := null;
  end if;
  return new;
end;
$$;

alter table public.user_blocks add column if not exists blocker_profile_id text;
alter table public.user_blocks alter column blocker_user_id drop not null;
alter table public.user_blocks drop constraint if exists user_blocks_pkey;
alter table public.user_blocks drop constraint if exists user_blocks_blocker_identity_check;
alter table public.user_blocks add constraint user_blocks_blocker_identity_check
  check (num_nonnulls(blocker_user_id, blocker_profile_id) = 1);
create unique index if not exists idx_user_blocks_identity_target
  on public.user_blocks ((coalesce(blocker_profile_id, blocker_user_id::text)), blocked_profile_id);
revoke all on public.user_blocks from anon, authenticated, public;

alter table public.safety_reports add column if not exists reporter_profile_id text;
alter table public.safety_reports alter column reporter_user_id drop not null;
alter table public.safety_reports drop constraint if exists safety_reports_reporter_identity_check;
alter table public.safety_reports add constraint safety_reports_reporter_identity_check
  check (num_nonnulls(reporter_user_id, reporter_profile_id) = 1);
alter table public.security_audit_events add column if not exists actor_profile_id text;
revoke all on public.safety_reports from anon, authenticated, public;
revoke all on public.security_audit_events from anon, authenticated, public;

create or replace function public.block_user(p_blocked_profile_id text,p_reason text default 'USER_BLOCK')
returns boolean language plpgsql security definer set search_path=pg_catalog,public as $$
declare actor_profile_id text := public.vibesync_current_profile_id(); actor_user_id uuid;
begin
  if actor_profile_id is null then raise exception 'verified VibeSync session required'; end if;
  if coalesce(p_blocked_profile_id,'')='' or p_blocked_profile_id=actor_profile_id then raise exception 'invalid block target'; end if;
  if actor_profile_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    select id into actor_user_id from auth.users where id=actor_profile_id::uuid limit 1;
  end if;
  if not exists(select 1 from public.profiles p where p.id=p_blocked_profile_id and coalesce(p.is_deleted,false)=false) then
    raise exception 'block target not found';
  end if;
  insert into public.user_blocks(blocker_user_id,blocker_profile_id,blocked_profile_id,reason)
  values(actor_user_id,case when actor_user_id is null then actor_profile_id else null end,
    p_blocked_profile_id,left(coalesce(p_reason,'USER_BLOCK'),120))
  on conflict ((coalesce(blocker_profile_id,blocker_user_id::text)),blocked_profile_id)
  do update set reason=excluded.reason;
  return true;
end;
$$;
revoke all on function public.block_user(text,text) from public,anon;
grant execute on function public.block_user(text,text) to authenticated;

create or replace function public.unblock_user(p_blocked_profile_id text)
returns boolean language plpgsql security definer set search_path=pg_catalog,public as $$
declare actor_profile_id text := public.vibesync_current_profile_id(); actor_user_id uuid;
begin
  if actor_profile_id is null then raise exception 'verified VibeSync session required'; end if;
  if actor_profile_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    select id into actor_user_id from auth.users where id=actor_profile_id::uuid limit 1;
  end if;
  delete from public.user_blocks where blocked_profile_id=p_blocked_profile_id
    and ((actor_user_id is not null and blocker_user_id=actor_user_id)
      or (actor_user_id is null and blocker_profile_id=actor_profile_id));
  return true;
end;
$$;
revoke all on function public.unblock_user(text) from public,anon;
grant execute on function public.unblock_user(text) to authenticated;

create or replace function public.submit_safety_report(p_reported_profile_id text,p_reason text,p_details text default '')
returns uuid language plpgsql security definer set search_path=pg_catalog,public as $$
declare actor_profile_id text := public.vibesync_current_profile_id(); actor_user_id uuid; new_report_id uuid;
begin
  if actor_profile_id is null then raise exception 'verified VibeSync session required'; end if;
  if coalesce(p_reported_profile_id,'')='' or coalesce(p_reason,'')='' then raise exception 'report target and reason required'; end if;
  if actor_profile_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    select id into actor_user_id from auth.users where id=actor_profile_id::uuid limit 1;
  end if;
  insert into public.safety_reports(reporter_user_id,reporter_profile_id,reported_profile_id,reason,details)
  values(actor_user_id,case when actor_user_id is null then actor_profile_id else null end,
    p_reported_profile_id,left(p_reason,120),left(coalesce(p_details,''),4000))
  returning report_id into new_report_id;
  insert into public.security_audit_events(actor_user_id,actor_profile_id,event_type,subject_id,severity)
  values(actor_user_id,case when actor_user_id is null then actor_profile_id else null end,
    'SAFETY_REPORT_CREATED',p_reported_profile_id,'WARNING');
  return new_report_id;
end;
$$;
revoke all on function public.submit_safety_report(text,text,text) from public,anon;
grant execute on function public.submit_safety_report(text,text,text) to authenticated;

create or replace function public.staff_set_profile_moderation(p_profile_id text,p_action text,p_reason text default '')
returns boolean language plpgsql security definer set search_path=pg_catalog,public as $$
declare actor_profile_id text := public.vibesync_jwt_profile_id(); actor_user_id uuid;
begin
  if not public.is_platform_staff() then raise exception 'platform staff authorization required'; end if;
  if actor_profile_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    select id into actor_user_id from auth.users where id=actor_profile_id::uuid limit 1;
  end if;
  if not exists(select 1 from public.profiles where id=p_profile_id) then raise exception 'profile not found'; end if;
  if p_action='BAN' then
    update public.profiles set account_status='BANNED',updated_at=(extract(epoch from now())*1000)::bigint where id=p_profile_id;
  elsif p_action='UNBAN' then
    update public.profiles set account_status='ACTIVE',updated_at=(extract(epoch from now())*1000)::bigint where id=p_profile_id;
  elsif p_action='DELETE' then
    update public.profiles set is_deleted=true,account_status='DELETED',updated_at=(extract(epoch from now())*1000)::bigint where id=p_profile_id;
  else raise exception 'unsupported moderation action'; end if;
  insert into public.security_audit_events(actor_user_id,actor_profile_id,event_type,subject_id,severity,payload_digest)
  values(actor_user_id,case when actor_user_id is null then actor_profile_id else null end,
    'STAFF_MODERATION_'||p_action,p_profile_id,'HIGH',
    encode(extensions.digest(convert_to(coalesce(p_reason,''),'utf8'),'sha256'),'hex'));
  return true;
end;
$$;
revoke all on function public.staff_set_profile_moderation(text,text,text) from public,anon;
grant execute on function public.staff_set_profile_moderation(text,text,text) to authenticated;

create or replace function public.is_platform_staff()
returns boolean language plpgsql stable security definer set search_path=pg_catalog,public as $$
declare profile_sub text := public.vibesync_jwt_profile_id();
begin
  if profile_sub is null or profile_sub !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then return false; end if;
  return exists(select 1 from public.platform_staff s where s.auth_user_id=profile_sub::uuid and s.active=true);
end;
$$;
revoke all on function public.is_platform_staff() from public;
grant execute on function public.is_platform_staff() to authenticated;

create or replace function public.normalize_chat_ciphertext()
returns trigger language plpgsql security invoker set search_path=pg_catalog,public,auth as $$
declare request_subject text;
begin
  if auth.role()='authenticated' then
    if not new.is_encrypted then raise exception 'encrypted messages required'; end if;
    if coalesce(new.ciphertext,'')='' and coalesce(new.text,'')<>'' then new.ciphertext:=new.text; end if;
    new.text:=null; new.message:=null; new.message_text:=null; new.sender_phone:=null; new.receiver_phone:=null;
    request_subject:=public.vibesync_jwt_profile_id();
    if new.sender_user_id is null and request_subject ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
      new.sender_user_id:=request_subject::uuid;
    end if;
    if new.timestamp is null then new.timestamp:=(extract(epoch from now())*1000)::bigint; end if;
  end if;
  return new;
end;
$$;

commit;
