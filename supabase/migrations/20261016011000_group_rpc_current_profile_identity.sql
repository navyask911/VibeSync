-- Ensure every group-chat RPC derives actor identity through current_profile_id().
-- All functions remain SECURITY INVOKER so the authenticated caller's table grants and RLS apply.
begin;

create or replace function public.vibesync_create_chat_group(
  p_title text, p_description text default '', p_avatar_url text default null,
  p_member_profile_ids text[] default array[]::text[]
)
returns uuid
language plpgsql security invoker
set search_path = public, pg_temp
as $function$
declare
  v_actor text := public.vibesync_current_profile_id();
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
    -- The client supplies IDs from its permitted contact list. Do not read profile rows
    -- around profile-directory RLS just to check existence; the FK rejects unknown IDs.
    insert into public.chat_group_members(group_id, profile_id, role)
    values (v_group_id, v_member, 'member')
    on conflict (group_id, profile_id) do nothing;
  end loop;
  return v_group_id;
end;
$function$;

create or replace function public.vibesync_delete_chat_group(p_group_id uuid)
returns void
language plpgsql security invoker
set search_path = public, pg_temp
as $function$
declare v_actor text := public.vibesync_current_profile_id();
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

create or replace function public.vibesync_leave_chat_group(p_group_id uuid)
returns void
language plpgsql security invoker
set search_path = public, pg_temp
as $function$
declare v_actor text := public.vibesync_current_profile_id();
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

create or replace function public.vibesync_remove_chat_group_member(p_group_id uuid, p_target_profile_id text)
returns void
language plpgsql security invoker
set search_path = public, pg_temp
as $function$
declare
  v_actor text := public.vibesync_current_profile_id();
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

create or replace function public.vibesync_send_chat_group_message(
  p_group_id uuid, p_message_id uuid, p_envelopes jsonb
)
returns uuid
language plpgsql security invoker
set search_path = public, pg_temp
as $function$
declare
  v_actor text := public.vibesync_current_profile_id();
  v_sender_name text;
  v_item jsonb;
  v_recipient text;
  v_ciphertext text;
  v_count integer := 0;
  v_active_count integer := 0;
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

  select count(*)::integer into v_active_count from public.chat_group_members m
    where m.group_id=p_group_id and m.left_at is null;
  if v_active_count < 1 or v_active_count > 100 then
    raise exception 'group_member_count_out_of_range' using errcode='22023';
  end if;
  if jsonb_array_length(p_envelopes) <> v_active_count then
    raise exception 'envelope_for_every_active_member_required' using errcode='22023';
  end if;

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
  if v_count <> v_active_count then
    raise exception 'envelope_for_every_active_member_required' using errcode='22023';
  end if;
  return p_message_id;
end;
$function$;

create or replace function public.vibesync_set_chat_group_member_role(
  p_group_id uuid, p_target_profile_id text, p_role text
)
returns void
language plpgsql security invoker
set search_path = public, pg_temp
as $function$
declare
  v_actor text := public.vibesync_current_profile_id();
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

create or replace function public.vibesync_set_chat_group_preferences(
  p_group_id uuid, p_is_pinned boolean, p_mute_until timestamptz
)
returns void
language plpgsql security invoker
set search_path = public, pg_temp
as $function$
declare v_actor text := public.vibesync_current_profile_id();
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

create or replace function public.vibesync_update_chat_group_info(
  p_group_id uuid, p_title text default null, p_description text default null,
  p_avatar_url text default null
)
returns void
language plpgsql security invoker
set search_path = public, pg_temp
as $function$
declare
  v_actor text := public.vibesync_current_profile_id();
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
language plpgsql security invoker
set search_path = public, pg_temp
as $function$
declare v_actor text := public.vibesync_current_profile_id();
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

alter function public.vibesync_create_chat_group(text, text, text, text[]) security invoker;
alter function public.vibesync_create_chat_group(text, text, text, text[]) set search_path = public, pg_temp;
revoke all on function public.vibesync_create_chat_group(text, text, text, text[]) from public, anon;
grant execute on function public.vibesync_create_chat_group(text, text, text, text[]) to authenticated;
alter function public.vibesync_delete_chat_group(uuid) security invoker;
alter function public.vibesync_delete_chat_group(uuid) set search_path = public, pg_temp;
revoke all on function public.vibesync_delete_chat_group(uuid) from public, anon;
grant execute on function public.vibesync_delete_chat_group(uuid) to authenticated;
alter function public.vibesync_leave_chat_group(uuid) security invoker;
alter function public.vibesync_leave_chat_group(uuid) set search_path = public, pg_temp;
revoke all on function public.vibesync_leave_chat_group(uuid) from public, anon;
grant execute on function public.vibesync_leave_chat_group(uuid) to authenticated;
alter function public.vibesync_remove_chat_group_member(uuid, text) security invoker;
alter function public.vibesync_remove_chat_group_member(uuid, text) set search_path = public, pg_temp;
revoke all on function public.vibesync_remove_chat_group_member(uuid, text) from public, anon;
grant execute on function public.vibesync_remove_chat_group_member(uuid, text) to authenticated;
alter function public.vibesync_send_chat_group_message(uuid, uuid, jsonb) security invoker;
alter function public.vibesync_send_chat_group_message(uuid, uuid, jsonb) set search_path = public, pg_temp;
revoke all on function public.vibesync_send_chat_group_message(uuid, uuid, jsonb) from public, anon;
grant execute on function public.vibesync_send_chat_group_message(uuid, uuid, jsonb) to authenticated;
alter function public.vibesync_set_chat_group_member_role(uuid, text, text) security invoker;
alter function public.vibesync_set_chat_group_member_role(uuid, text, text) set search_path = public, pg_temp;
revoke all on function public.vibesync_set_chat_group_member_role(uuid, text, text) from public, anon;
grant execute on function public.vibesync_set_chat_group_member_role(uuid, text, text) to authenticated;
alter function public.vibesync_set_chat_group_preferences(uuid, boolean, timestamptz) security invoker;
alter function public.vibesync_set_chat_group_preferences(uuid, boolean, timestamptz) set search_path = public, pg_temp;
revoke all on function public.vibesync_set_chat_group_preferences(uuid, boolean, timestamptz) from public, anon;
grant execute on function public.vibesync_set_chat_group_preferences(uuid, boolean, timestamptz) to authenticated;
alter function public.vibesync_update_chat_group_info(uuid, text, text, text) security invoker;
alter function public.vibesync_update_chat_group_info(uuid, text, text, text) set search_path = public, pg_temp;
revoke all on function public.vibesync_update_chat_group_info(uuid, text, text, text) from public, anon;
grant execute on function public.vibesync_update_chat_group_info(uuid, text, text, text) to authenticated;
alter function public.vibesync_update_chat_group_settings(uuid, boolean, boolean) security invoker;
alter function public.vibesync_update_chat_group_settings(uuid, boolean, boolean) set search_path = public, pg_temp;
revoke all on function public.vibesync_update_chat_group_settings(uuid, boolean, boolean) from public, anon;
grant execute on function public.vibesync_update_chat_group_settings(uuid, boolean, boolean) to authenticated;

notify pgrst, 'reload schema';
commit;
