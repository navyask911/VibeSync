-- Production E2EE group chat: membership-gated metadata, pairwise encrypted envelopes,
-- owner/admin RPCs, invite rotation, per-user pins/mutes, and data-only FCM fan-out.
begin;

create table if not exists public.chat_groups (
  id uuid primary key default gen_random_uuid(),
  title text not null check (char_length(trim(title)) between 1 and 80),
  description text not null default '' check (char_length(description) <= 500),
  avatar_url text,
  created_by text not null references public.profiles(id) on delete restrict,
  invite_code text unique,
  invite_revoked_at timestamptz,
  admins_only_messages boolean not null default false,
  admins_only_info_editing boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.chat_group_members (
  group_id uuid not null references public.chat_groups(id) on delete cascade,
  profile_id text not null references public.profiles(id) on delete cascade,
  role text not null default 'member' check (role in ('owner','admin','member')),
  joined_at timestamptz not null default now(),
  is_muted_until timestamptz,
  is_pinned boolean not null default false,
  left_at timestamptz,
  primary key (group_id, profile_id)
);

create index if not exists idx_chat_group_members_profile_active
  on public.chat_group_members(profile_id, joined_at desc) where left_at is null;
create index if not exists idx_chat_group_members_group_active
  on public.chat_group_members(group_id, joined_at) where left_at is null;
create index if not exists idx_chat_groups_created_by
  on public.chat_groups(created_by, created_at desc);

-- A metadata row is shared by the group. Each member has a distinct Tink envelope row,
-- so the service and other members never receive another recipient's decryptable ciphertext.
create table if not exists public.chat_group_messages (
  message_id uuid primary key,
  group_id uuid not null references public.chat_groups(id) on delete cascade,
  sender_profile_id text not null references public.profiles(id) on delete restrict,
  sender_name text not null,
  media_type text not null default 'TEXT' check (media_type in ('TEXT')),
  created_at timestamptz not null default now(),
  edited_at timestamptz,
  deleted_at timestamptz
);

create index if not exists idx_chat_group_messages_timeline
  on public.chat_group_messages(group_id, created_at desc, message_id);

create table if not exists public.chat_group_message_envelopes (
  group_id uuid not null,
  message_id uuid not null references public.chat_group_messages(message_id) on delete cascade,
  recipient_profile_id text not null references public.profiles(id) on delete cascade,
  ciphertext text not null check (ciphertext like 'TINK_ENC:%'),
  dispatch_status text not null default 'PENDING' check (dispatch_status in ('PENDING','SENDING','SENT')),
  dispatch_attempts integer not null default 0 check (dispatch_attempts >= 0),
  delivered_at timestamptz,
  created_at timestamptz not null default now(),
  primary key (message_id, recipient_profile_id),
  foreign key (group_id, recipient_profile_id)
    references public.chat_group_members(group_id, profile_id) on delete cascade
);

create index if not exists idx_chat_group_envelopes_recipient
  on public.chat_group_message_envelopes(recipient_profile_id, created_at desc);
create index if not exists idx_chat_group_envelopes_pending
  on public.chat_group_message_envelopes(dispatch_status, created_at)
  where dispatch_status = 'PENDING';

create or replace function public.vibesync_chat_group_member_role(p_group_id uuid, p_profile_id text)
returns text
language sql stable security definer
set search_path = pg_catalog, public
as $function$
  select m.role
  from public.chat_group_members m
  where m.group_id = p_group_id
    and m.profile_id = p_profile_id
    and m.left_at is null
    and public.vibesync_jwt_session_active()
    and p_profile_id = public.vibesync_jwt_profile_id()
  limit 1;
$function$;

create or replace function public.vibesync_can_send_chat_group_message(p_group_id uuid, p_profile_id text)
returns boolean
language sql stable security definer
set search_path = pg_catalog, public
as $function$
  select coalesce((
    select public.vibesync_jwt_session_active()
       and p_profile_id = public.vibesync_jwt_profile_id()
       and m.left_at is null
       and (
         not g.admins_only_messages
         or m.role in ('owner','admin')
       )
    from public.chat_group_members m
    join public.chat_groups g on g.id = m.group_id
    where m.group_id = p_group_id and m.profile_id = p_profile_id
    limit 1
  ), false);
$function$;

revoke all on function public.vibesync_chat_group_member_role(uuid,text) from public, anon;
revoke all on function public.vibesync_can_send_chat_group_message(uuid,text) from public, anon;
grant execute on function public.vibesync_chat_group_member_role(uuid,text) to authenticated;
grant execute on function public.vibesync_can_send_chat_group_message(uuid,text) to authenticated;

alter table public.chat_groups enable row level security;
alter table public.chat_group_members enable row level security;
alter table public.chat_group_messages enable row level security;
alter table public.chat_group_message_envelopes enable row level security;

drop policy if exists chat_groups_select_active_members on public.chat_groups;
create policy chat_groups_select_active_members on public.chat_groups
for select to authenticated
using (
  public.vibesync_jwt_session_active()
  and public.vibesync_chat_group_member_role(id, public.vibesync_jwt_profile_id()) is not null
);

drop policy if exists chat_group_members_select_active_group_members on public.chat_group_members;
create policy chat_group_members_select_active_group_members on public.chat_group_members
for select to authenticated
using (
  public.vibesync_jwt_session_active()
  and public.vibesync_chat_group_member_role(group_id, public.vibesync_jwt_profile_id()) is not null
);

drop policy if exists chat_group_messages_select_active_members on public.chat_group_messages;
create policy chat_group_messages_select_active_members on public.chat_group_messages
for select to authenticated
using (
  public.vibesync_jwt_session_active()
  and public.vibesync_chat_group_member_role(group_id, public.vibesync_jwt_profile_id()) is not null
);

drop policy if exists chat_group_message_envelopes_select_recipient on public.chat_group_message_envelopes;
create policy chat_group_message_envelopes_select_recipient on public.chat_group_message_envelopes
for select to authenticated
using (
  public.vibesync_jwt_session_active()
  and recipient_profile_id = public.vibesync_jwt_profile_id()
  and public.vibesync_chat_group_member_role(group_id, public.vibesync_jwt_profile_id()) is not null
);

drop policy if exists chat_group_messages_insert_sender on public.chat_group_messages;
create policy chat_group_messages_insert_sender on public.chat_group_messages
for insert to authenticated
with check (
  public.vibesync_jwt_session_active()
  and sender_profile_id = public.vibesync_jwt_profile_id()
  and public.vibesync_can_send_chat_group_message(group_id, sender_profile_id)
);

drop policy if exists chat_group_message_envelopes_insert_sender on public.chat_group_message_envelopes;
create policy chat_group_message_envelopes_insert_sender on public.chat_group_message_envelopes
for insert to authenticated
with check (
  public.vibesync_jwt_session_active()
  and public.vibesync_chat_group_member_role(group_id, public.vibesync_jwt_profile_id()) is not null
  and recipient_profile_id in (
    select m.profile_id from public.chat_group_members m
    where m.group_id = chat_group_message_envelopes.group_id and m.left_at is null
  )
  and exists (
    select 1 from public.chat_group_messages gm
    where gm.message_id = chat_group_message_envelopes.message_id
      and gm.group_id = chat_group_message_envelopes.group_id
      and gm.sender_profile_id = public.vibesync_jwt_profile_id()
      and public.vibesync_can_send_chat_group_message(gm.group_id, gm.sender_profile_id)
  )
);

revoke all on table public.chat_groups, public.chat_group_members,
  public.chat_group_messages, public.chat_group_message_envelopes from anon, authenticated;
grant select on table public.chat_groups, public.chat_group_members,
  public.chat_group_messages, public.chat_group_message_envelopes to authenticated;
grant update (dispatch_status, dispatch_attempts, delivered_at)
  on table public.chat_group_message_envelopes to service_role;

create or replace function public.vibesync_list_my_chat_groups()
returns table (
  group_id uuid, title text, description text, avatar_url text, created_by text,
  invite_code text, admins_only_messages boolean, admins_only_info_editing boolean,
  role text, member_count integer, is_pinned boolean, is_muted_until timestamptz
)
language sql stable security definer
set search_path = pg_catalog, public
as $function$
  select g.id, g.title, g.description, g.avatar_url, g.created_by, g.invite_code,
         g.admins_only_messages, g.admins_only_info_editing, mine.role,
         (select count(*)::integer from public.chat_group_members active
           where active.group_id = g.id and active.left_at is null),
         mine.is_pinned, mine.is_muted_until
  from public.chat_groups g
  join public.chat_group_members mine on mine.group_id = g.id
  where public.vibesync_jwt_session_active()
    and mine.profile_id = public.vibesync_jwt_profile_id()
    and mine.left_at is null
  order by mine.is_pinned desc, g.updated_at desc;
$function$;

create or replace function public.vibesync_list_chat_group_members(p_group_id uuid)
returns table (
  profile_id text, display_name text, avatar_url text, role text,
  joined_at timestamptz, is_muted_until timestamptz, is_pinned boolean, is_you boolean
)
language sql stable security definer
set search_path = pg_catalog, public
as $function$
  select m.profile_id, coalesce(nullif(trim(p.name), ''), 'VibeSync member'),
         p.avatar_url, m.role, m.joined_at, m.is_muted_until, m.is_pinned,
         m.profile_id = public.vibesync_jwt_profile_id()
  from public.chat_group_members m
  join public.profiles p on p.id = m.profile_id
  where m.group_id = p_group_id and m.left_at is null
    and public.vibesync_jwt_session_active()
    and public.vibesync_chat_group_member_role(p_group_id, public.vibesync_jwt_profile_id()) is not null
  order by case m.role when 'owner' then 0 when 'admin' then 1 else 2 end, m.joined_at;
$function$;

create or replace function public.vibesync_create_chat_group(
  p_title text, p_description text default '', p_avatar_url text default null,
  p_member_profile_ids text[] default array[]::text[]
)
returns uuid
language plpgsql security definer
set search_path = pg_catalog, public
as $function$
declare
  v_actor text := public.vibesync_jwt_profile_id();
  v_group_id uuid;
  v_member text;
begin
  if not public.vibesync_jwt_session_active() or coalesce(v_actor,'') = '' then
    raise exception 'verified_session_required' using errcode = '42501';
  end if;
  if char_length(trim(coalesce(p_title,''))) not between 1 and 80 then
    raise exception 'group_title_length_invalid' using errcode = '22023';
  end if;
  if char_length(coalesce(p_description,'')) > 500 then
    raise exception 'group_description_too_long' using errcode = '22023';
  end if;

  insert into public.chat_groups(title, description, avatar_url, created_by, invite_code)
  values (trim(p_title), coalesce(p_description,''), nullif(trim(p_avatar_url), ''),
          v_actor, replace(gen_random_uuid()::text, '-', ''))
  returning id into v_group_id;

  insert into public.chat_group_members(group_id, profile_id, role)
  values (v_group_id, v_actor, 'owner');

  for v_member in
    select distinct trim(members.profile_id)
    from unnest(coalesce(p_member_profile_ids, array[]::text[])) as members(profile_id)
    where trim(members.profile_id) <> '' and trim(members.profile_id) <> v_actor
  loop
    if exists (select 1 from public.profiles p where p.id = v_member and coalesce(p.is_deleted,false) = false) then
      insert into public.chat_group_members(group_id, profile_id, role)
      values (v_group_id, v_member, 'member')
      on conflict (group_id, profile_id) do nothing;
    end if;
  end loop;
  return v_group_id;
end;
$function$;

create or replace function public.vibesync_update_chat_group_info(
  p_group_id uuid, p_title text default null, p_description text default null,
  p_avatar_url text default null
)
returns void
language plpgsql security definer
set search_path = pg_catalog, public
as $function$
declare
  v_actor text := public.vibesync_jwt_profile_id();
  v_role text;
  v_admins_only boolean;
begin
  if not public.vibesync_jwt_session_active() or coalesce(v_actor,'') = '' then
    raise exception 'verified_session_required' using errcode = '42501';
  end if;
  select m.role, g.admins_only_info_editing into v_role, v_admins_only
  from public.chat_group_members m join public.chat_groups g on g.id=m.group_id
  where m.group_id=p_group_id and m.profile_id=v_actor and m.left_at is null;
  if v_role is null then raise exception 'active_group_membership_required' using errcode='42501'; end if;
  if v_admins_only and v_role not in ('owner','admin') then
    raise exception 'group_info_admins_only' using errcode='42501';
  end if;
  if p_title is not null and char_length(trim(p_title)) not between 1 and 80 then
    raise exception 'group_title_length_invalid' using errcode='22023';
  end if;
  if p_description is not null and char_length(p_description) > 500 then
    raise exception 'group_description_too_long' using errcode='22023';
  end if;
  update public.chat_groups set
    title = coalesce(trim(p_title), title),
    description = coalesce(p_description, description),
    avatar_url = coalesce(nullif(trim(p_avatar_url),''), avatar_url),
    updated_at = now()
  where id=p_group_id;
end;
$function$;

create or replace function public.vibesync_update_chat_group_settings(
  p_group_id uuid, p_admins_only_messages boolean, p_admins_only_info_editing boolean
)
returns void
language plpgsql security definer
set search_path = pg_catalog, public
as $function$
declare v_actor text := public.vibesync_jwt_profile_id();
begin
  if not public.vibesync_jwt_session_active() or coalesce(v_actor,'')='' then
    raise exception 'verified_session_required' using errcode='42501';
  end if;
  if public.vibesync_chat_group_member_role(p_group_id,v_actor) not in ('owner','admin') then
    raise exception 'group_admin_required' using errcode='42501';
  end if;
  update public.chat_groups set
    admins_only_messages = coalesce(p_admins_only_messages,admins_only_messages),
    admins_only_info_editing = coalesce(p_admins_only_info_editing,admins_only_info_editing),
    updated_at=now()
  where id=p_group_id;
end;
$function$;

create or replace function public.vibesync_set_chat_group_member_role(
  p_group_id uuid, p_target_profile_id text, p_role text
)
returns void
language plpgsql security definer
set search_path = pg_catalog, public
as $function$
declare
  v_actor text := public.vibesync_jwt_profile_id();
  v_actor_role text;
  v_target_role text;
begin
  if not public.vibesync_jwt_session_active() or coalesce(v_actor,'')='' then
    raise exception 'verified_session_required' using errcode='42501';
  end if;
  if p_role not in ('admin','member') then raise exception 'invalid_group_role' using errcode='22023'; end if;
  v_actor_role := public.vibesync_chat_group_member_role(p_group_id,v_actor);
  select role into v_target_role from public.chat_group_members
    where group_id=p_group_id and profile_id=p_target_profile_id and left_at is null;
  if v_actor_role='owner' then
    null;
  elsif v_actor_role='admin' and v_target_role='member' then
    null;
  else
    raise exception 'owner_or_authorized_admin_required' using errcode='42501';
  end if;
  if v_target_role is null or v_target_role='owner' then
    raise exception 'target_member_not_eligible' using errcode='22023';
  end if;
  if v_actor_role='admin' and v_target_role <> 'member' then
    raise exception 'admins_cannot_manage_admins' using errcode='42501';
  end if;
  update public.chat_group_members set role=p_role
  where group_id=p_group_id and profile_id=p_target_profile_id and left_at is null;
end;
$function$;

create or replace function public.vibesync_remove_chat_group_member(p_group_id uuid, p_target_profile_id text)
returns void
language plpgsql security definer
set search_path = pg_catalog, public
as $function$
declare
  v_actor text := public.vibesync_jwt_profile_id();
  v_actor_role text;
  v_target_role text;
begin
  if not public.vibesync_jwt_session_active() or coalesce(v_actor,'')='' then
    raise exception 'verified_session_required' using errcode='42501';
  end if;
  v_actor_role := public.vibesync_chat_group_member_role(p_group_id,v_actor);
  select role into v_target_role from public.chat_group_members
    where group_id=p_group_id and profile_id=p_target_profile_id and left_at is null;
  if v_target_role is null or v_target_role='owner' then
    raise exception 'target_member_not_eligible' using errcode='22023';
  end if;
  if v_actor = p_target_profile_id then
    if v_actor_role='owner' then raise exception 'owner_must_delete_or_transfer_group' using errcode='42501'; end if;
  elsif v_actor_role='owner' then
    null;
  elsif v_actor_role='admin' and v_target_role='member' then
    null;
  else
    raise exception 'owner_or_authorized_admin_required' using errcode='42501';
  end if;
  if v_actor_role='admin' and v_target_role <> 'member' then
    raise exception 'admins_cannot_remove_admins' using errcode='42501';
  end if;
  update public.chat_group_members set left_at=now()
  where group_id=p_group_id and profile_id=p_target_profile_id and left_at is null;
end;
$function$;

create or replace function public.vibesync_leave_chat_group(p_group_id uuid)
returns void
language plpgsql security definer
set search_path = pg_catalog, public
as $function$
declare v_actor text := public.vibesync_jwt_profile_id();
declare v_role text;
begin
  if not public.vibesync_jwt_session_active() or coalesce(v_actor,'')='' then
    raise exception 'verified_session_required' using errcode='42501';
  end if;
  v_role := public.vibesync_chat_group_member_role(p_group_id,v_actor);
  if v_role is null then raise exception 'active_group_membership_required' using errcode='42501'; end if;
  if v_role='owner' then raise exception 'owner_must_delete_or_transfer_group' using errcode='42501'; end if;
  update public.chat_group_members set left_at=now()
  where group_id=p_group_id and profile_id=v_actor and left_at is null;
end;
$function$;

create or replace function public.vibesync_delete_chat_group(p_group_id uuid)
returns void
language plpgsql security definer
set search_path = pg_catalog, public
as $function$
declare v_actor text := public.vibesync_jwt_profile_id();
begin
  if not public.vibesync_jwt_session_active() or coalesce(v_actor,'')='' then
    raise exception 'verified_session_required' using errcode='42501';
  end if;
  if public.vibesync_chat_group_member_role(p_group_id,v_actor) <> 'owner' then
    raise exception 'group_owner_required' using errcode='42501';
  end if;
  delete from public.chat_groups where id=p_group_id and created_by=v_actor;
end;
$function$;

create or replace function public.vibesync_set_chat_group_preferences(
  p_group_id uuid, p_is_pinned boolean, p_mute_until timestamptz
)
returns void
language plpgsql security definer
set search_path = pg_catalog, public
as $function$
declare v_actor text := public.vibesync_jwt_profile_id();
begin
  if not public.vibesync_jwt_session_active() or coalesce(v_actor,'')='' then
    raise exception 'verified_session_required' using errcode='42501';
  end if;
  update public.chat_group_members set
    is_pinned=coalesce(p_is_pinned,is_pinned),
    is_muted_until=p_mute_until
  where group_id=p_group_id and profile_id=v_actor and left_at is null;
  if not found then raise exception 'active_group_membership_required' using errcode='42501'; end if;
end;
$function$;

create or replace function public.vibesync_rotate_chat_group_invite(p_group_id uuid, p_revoke boolean default false)
returns text
language plpgsql security definer
set search_path = pg_catalog, public
as $function$
declare v_actor text := public.vibesync_jwt_profile_id(); v_code text;
begin
  if not public.vibesync_jwt_session_active() or coalesce(v_actor,'')='' then
    raise exception 'verified_session_required' using errcode='42501';
  end if;
  if public.vibesync_chat_group_member_role(p_group_id,v_actor) not in ('owner','admin') then
    raise exception 'group_admin_required' using errcode='42501';
  end if;
  if p_revoke then
    update public.chat_groups set invite_code=null, invite_revoked_at=now(), updated_at=now()
      where id=p_group_id returning invite_code into v_code;
    return null;
  end if;
  v_code := replace(gen_random_uuid()::text,'-','') || replace(gen_random_uuid()::text,'-','');
  update public.chat_groups set invite_code=v_code, invite_revoked_at=null, updated_at=now()
    where id=p_group_id;
  return v_code;
end;
$function$;

create or replace function public.vibesync_join_chat_group_by_invite(p_invite_code text)
returns uuid
language plpgsql security definer
set search_path = pg_catalog, public
as $function$
declare v_actor text := public.vibesync_jwt_profile_id(); v_group_id uuid;
begin
  if not public.vibesync_jwt_session_active() or coalesce(v_actor,'')='' then
    raise exception 'verified_session_required' using errcode='42501';
  end if;
  select id into v_group_id from public.chat_groups
  where invite_code=p_invite_code and invite_revoked_at is null limit 1;
  if v_group_id is null then raise exception 'invite_invalid_or_revoked' using errcode='22023'; end if;
  insert into public.chat_group_members(group_id,profile_id,role)
  values (v_group_id,v_actor,'member')
  on conflict (group_id,profile_id) do update set joined_at=now(),left_at=null,role=case
    when chat_group_members.role='owner' then 'owner' else 'member' end;
  update public.chat_groups set updated_at=now() where id=v_group_id;
  return v_group_id;
end;
$function$;

create or replace function public.vibesync_send_chat_group_message(
  p_group_id uuid, p_message_id uuid, p_envelopes jsonb
)
returns uuid
language plpgsql security definer
set search_path = pg_catalog, public
as $function$
declare
  v_actor text := public.vibesync_jwt_profile_id();
  v_role text;
  v_sender_name text;
  v_item jsonb;
  v_recipient text;
  v_ciphertext text;
  v_count integer := 0;
begin
  if not public.vibesync_jwt_session_active() or coalesce(v_actor,'')='' then
    raise exception 'verified_session_required' using errcode='42501';
  end if;
  if jsonb_typeof(p_envelopes) <> 'array' or jsonb_array_length(p_envelopes) < 1 or jsonb_array_length(p_envelopes) > 100 then
    raise exception 'invalid_envelope_batch' using errcode='22023';
  end if;
  if not public.vibesync_can_send_chat_group_message(p_group_id,v_actor) then
    raise exception 'active_member_or_admin_announcement_required' using errcode='42501';
  end if;
  select coalesce(nullif(trim(p.name),''),'VibeSync member') into v_sender_name
    from public.profiles p where p.id=v_actor;
  if v_sender_name is null then raise exception 'profile_not_found' using errcode='42501'; end if;

  insert into public.chat_group_messages(message_id,group_id,sender_profile_id,sender_name)
  values (coalesce(p_message_id,gen_random_uuid()),p_group_id,v_actor,v_sender_name)
  returning message_id into p_message_id;

  for v_item in select value from jsonb_array_elements(p_envelopes)
  loop
    v_recipient := nullif(trim(v_item->>'recipient_profile_id'),'');
    v_ciphertext := coalesce(v_item->>'ciphertext','');
    if v_recipient is null or v_ciphertext not like 'TINK_ENC:%' then
      raise exception 'invalid_group_message_envelope' using errcode='22023';
    end if;
    if not exists (
      select 1 from public.chat_group_members m
      where m.group_id=p_group_id and m.profile_id=v_recipient and m.left_at is null
    ) then
      raise exception 'envelope_recipient_not_active_member' using errcode='42501';
    end if;
    insert into public.chat_group_message_envelopes(group_id,message_id,recipient_profile_id,ciphertext)
    values (p_group_id,p_message_id,v_recipient,v_ciphertext);
    v_count := v_count + 1;
  end loop;
  if v_count < 1 then raise exception 'group_message_requires_envelopes' using errcode='22023'; end if;
  return p_message_id;
end;
$function$;

-- All group RPCs are entry points: remove PUBLIC/anon execution and require authenticated sessions.
revoke all on function public.vibesync_list_my_chat_groups() from public, anon;
revoke all on function public.vibesync_list_chat_group_members(uuid) from public, anon;
revoke all on function public.vibesync_create_chat_group(text,text,text,text[]) from public, anon;
revoke all on function public.vibesync_update_chat_group_info(uuid,text,text,text) from public, anon;
revoke all on function public.vibesync_update_chat_group_settings(uuid,boolean,boolean) from public, anon;
revoke all on function public.vibesync_set_chat_group_member_role(uuid,text,text) from public, anon;
revoke all on function public.vibesync_remove_chat_group_member(uuid,text) from public, anon;
revoke all on function public.vibesync_leave_chat_group(uuid) from public, anon;
revoke all on function public.vibesync_delete_chat_group(uuid) from public, anon;
revoke all on function public.vibesync_set_chat_group_preferences(uuid,boolean,timestamptz) from public, anon;
revoke all on function public.vibesync_rotate_chat_group_invite(uuid,boolean) from public, anon;
revoke all on function public.vibesync_join_chat_group_by_invite(text) from public, anon;
revoke all on function public.vibesync_send_chat_group_message(uuid,uuid,jsonb) from public, anon;
grant execute on function public.vibesync_list_my_chat_groups() to authenticated;
grant execute on function public.vibesync_list_chat_group_members(uuid) to authenticated;
grant execute on function public.vibesync_create_chat_group(text,text,text,text[]) to authenticated;
grant execute on function public.vibesync_update_chat_group_info(uuid,text,text,text) to authenticated;
grant execute on function public.vibesync_update_chat_group_settings(uuid,boolean,boolean) to authenticated;
grant execute on function public.vibesync_set_chat_group_member_role(uuid,text,text) to authenticated;
grant execute on function public.vibesync_remove_chat_group_member(uuid,text) to authenticated;
grant execute on function public.vibesync_leave_chat_group(uuid) to authenticated;
grant execute on function public.vibesync_delete_chat_group(uuid) to authenticated;
grant execute on function public.vibesync_set_chat_group_preferences(uuid,boolean,timestamptz) to authenticated;
grant execute on function public.vibesync_rotate_chat_group_invite(uuid,boolean) to authenticated;
grant execute on function public.vibesync_join_chat_group_by_invite(text) to authenticated;
grant execute on function public.vibesync_send_chat_group_message(uuid,uuid,jsonb) to authenticated;

-- Group avatars use the existing avatars bucket at group_avatars/<group UUID>/<filename>.
-- The bucket is currently public for the existing encrypted-profile-avatar pipeline; upload and
-- mutation remain role-gated here. Do not put secrets or plaintext message data in object names.
drop policy if exists group_avatars_member_read on storage.objects;
create policy group_avatars_member_read on storage.objects
for select to authenticated
using (
  bucket_id='avatars' and
  case when name ~ '^group_avatars/[0-9a-fA-F-]{36}/[^/]+$'
    then public.vibesync_chat_group_member_role(split_part(name,'/',2)::uuid,public.vibesync_jwt_profile_id()) is not null
    else false end
);

drop policy if exists group_avatars_admin_upload on storage.objects;
create policy group_avatars_admin_upload on storage.objects
for insert to authenticated
with check (
  bucket_id='avatars' and public.vibesync_jwt_session_active() and
  case when name ~ '^group_avatars/[0-9a-fA-F-]{36}/[^/]+$'
    then public.vibesync_chat_group_member_role(split_part(name,'/',2)::uuid,public.vibesync_jwt_profile_id()) in ('owner','admin')
    else false end
);

drop policy if exists group_avatars_admin_update on storage.objects;
create policy group_avatars_admin_update on storage.objects
for update to authenticated
using (
  bucket_id='avatars' and public.vibesync_jwt_session_active() and
  case when name ~ '^group_avatars/[0-9a-fA-F-]{36}/[^/]+$'
    then public.vibesync_chat_group_member_role(split_part(name,'/',2)::uuid,public.vibesync_jwt_profile_id()) in ('owner','admin')
    else false end
)
with check (
  bucket_id='avatars' and public.vibesync_jwt_session_active() and
  case when name ~ '^group_avatars/[0-9a-fA-F-]{36}/[^/]+$'
    then public.vibesync_chat_group_member_role(split_part(name,'/',2)::uuid,public.vibesync_jwt_profile_id()) in ('owner','admin')
    else false end
);

drop policy if exists group_avatars_admin_delete on storage.objects;
create policy group_avatars_admin_delete on storage.objects
for delete to authenticated
using (
  bucket_id='avatars' and public.vibesync_jwt_session_active() and
  case when name ~ '^group_avatars/[0-9a-fA-F-]{36}/[^/]+$'
    then public.vibesync_chat_group_member_role(split_part(name,'/',2)::uuid,public.vibesync_jwt_profile_id()) in ('owner','admin')
    else false end
);

-- Trigger dispatches only a content-free wakeup. Ciphertext is fetched and decrypted on-device.
create extension if not exists pg_net with schema extensions;

create or replace function public.forward_group_envelope_to_fcm_edge()
returns trigger
language plpgsql security definer
set search_path = pg_catalog, public, extensions, vault
as $function$
declare
  v_sender text;
  v_url text;
  v_secret text;
  v_request_id bigint;
begin
  select sender_profile_id into v_sender
  from public.chat_group_messages
  where message_id=new.message_id and group_id=new.group_id;
  if v_sender is null or v_sender=new.recipient_profile_id
     or coalesce(new.ciphertext,'') not like 'TINK_ENC:%' then
    return new;
  end if;
  select decrypted_secret into v_url from vault.decrypted_secrets
    where name='vibesync_fcm_webhook_url' limit 1;
  select decrypted_secret into v_secret from vault.decrypted_secrets
    where name='vibesync_fcm_webhook_secret' limit 1;
  if coalesce(v_url,'') <> 'https://imhcbgpvjwersbnlgwzq.supabase.co/functions/v1/vibesync-fcm-dispatch'
     or coalesce(v_secret,'')='' then
    raise warning 'Group FCM dispatch skipped: webhook Vault settings unavailable';
    return new;
  end if;
  select net.http_post(
    url:=v_url,
    body:=jsonb_build_object(
      'type','INSERT','table',TG_TABLE_NAME,'schema',TG_TABLE_SCHEMA,
      'record',to_jsonb(new) || jsonb_build_object('sender_profile_id',v_sender)
    ),
    headers:=jsonb_build_object('Content-Type','application/json','x-webhook-secret',v_secret),
    timeout_milliseconds:=5000
  ) into v_request_id;
  raise log 'Group FCM wake-up queued for group %, message %, recipient %, request %',
    new.group_id,new.message_id,new.recipient_profile_id,v_request_id;
  return new;
exception when others then
  raise warning 'Group FCM wake-up could not be queued: %', SQLERRM;
  return new;
end;
$function$;

revoke all on function public.forward_group_envelope_to_fcm_edge() from public, anon, authenticated;
drop trigger if exists trg_group_message_envelope_fcm_edge on public.chat_group_message_envelopes;
create trigger trg_group_message_envelope_fcm_edge
after insert on public.chat_group_message_envelopes
for each row
when (new.ciphertext like 'TINK_ENC:%')
execute function public.forward_group_envelope_to_fcm_edge();

notify pgrst, 'reload schema';
commit;