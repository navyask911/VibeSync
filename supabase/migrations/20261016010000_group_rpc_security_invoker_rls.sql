-- Replace exposed group-chat SECURITY DEFINER RPCs with caller-privilege execution.
-- The deployed schema uses set_chat_group_member_role (not update_chat_group_member_role)
-- and the current three-argument update_chat_group_settings signature.
begin;

-- Keep the current identity helper compatible with signed-in VibeSync JWT sessions while
-- preserving the legacy session-header fallback for older non-group surfaces.
create or replace function public.vibesync_current_profile_id()
returns text
language sql
stable
security invoker
set search_path = public, pg_temp
as $function$
  select coalesce(
    (
      select public.vibesync_jwt_profile_id()
      where public.vibesync_jwt_session_active()
    ),
    (
      select s.profile_id
      from public.vibesync_sessions s
      where s.session_hash = encode(
        extensions.digest(
          convert_to(
            coalesce((current_setting('request.headers', true)::jsonb)->>'x-vibesync-session', ''),
            'utf8'
          ),
          'sha256'
        ),
        'hex'
      )
        and s.expires_at > now()
      limit 1
    )
  );
$function$;

-- RLS policies cannot call the public member-role RPC to decide whether a row is visible,
-- because that would recurse back through chat_group_members RLS. This private helper answers
-- one question only: is the caller (from the verified session identity) an active member?
create schema if not exists private;

create or replace function private.vibesync_group_has_active_member(p_group_id uuid)
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public, pg_temp
as $function$
  select public.vibesync_jwt_session_active()
     and exists (
       select 1
       from public.chat_group_members m
       where m.group_id = p_group_id
         and m.profile_id = public.vibesync_current_profile_id()
         and m.left_at is null
     );
$function$;

-- Invite-code access is intentionally narrow: these helpers reveal no group metadata and only
-- resolve a valid, unrevoked code or return a code to an active owner/admin.
create or replace function private.vibesync_group_id_for_invite(p_invite_code text)
returns uuid
language sql
stable
security definer
set search_path = pg_catalog, public, pg_temp
as $function$
  select g.id
  from public.chat_groups g
  where public.vibesync_jwt_session_active()
    and g.invite_revoked_at is null
    and g.invite_code = nullif(trim(p_invite_code), '')
  limit 1;
$function$;

create or replace function private.vibesync_group_invite_matches(
  p_group_id uuid,
  p_invite_code text
)
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public, pg_temp
as $function$
  select public.vibesync_jwt_session_active()
     and exists (
       select 1 from public.chat_groups g
       where g.id = p_group_id
         and g.invite_revoked_at is null
         and g.invite_code = nullif(trim(p_invite_code), '')
     );
$function$;

create or replace function private.vibesync_admin_invite_code(p_group_id uuid)
returns text
language sql
stable
security definer
set search_path = pg_catalog, public, pg_temp
as $function$
  select g.invite_code
  from public.chat_groups g
  join public.chat_group_members m on m.group_id = g.id
  where public.vibesync_jwt_session_active()
    and g.id = p_group_id
    and m.profile_id = public.vibesync_current_profile_id()
    and m.left_at is null
    and m.role in ('owner', 'admin')
  limit 1;
$function$;

revoke all on function private.vibesync_group_has_active_member(uuid) from public, anon;
revoke all on function private.vibesync_group_id_for_invite(text) from public, anon;
revoke all on function private.vibesync_group_invite_matches(uuid, text) from public, anon;
revoke all on function private.vibesync_admin_invite_code(uuid) from public, anon;
grant usage on schema private to authenticated;
grant execute on function private.vibesync_group_has_active_member(uuid) to authenticated;
grant execute on function private.vibesync_group_id_for_invite(text) to authenticated;
grant execute on function private.vibesync_group_invite_matches(uuid, text) to authenticated;
grant execute on function private.vibesync_admin_invite_code(uuid) to authenticated;

-- These RPCs now run with the authenticated caller's table privileges and RLS policies.
-- Member-role lookups are deliberately restricted to the caller's own identity.
create or replace function public.vibesync_chat_group_member_role(
  p_group_id uuid,
  p_profile_id text
)
returns text
language sql
stable
security invoker
set search_path = public, pg_temp
as $function$
  select m.role
  from public.chat_group_members m
  where m.group_id = p_group_id
    and m.profile_id = p_profile_id
    and m.profile_id = public.vibesync_current_profile_id()
    and m.left_at is null
    and public.vibesync_jwt_session_active()
  limit 1;
$function$;

create or replace function public.vibesync_can_send_chat_group_message(
  p_group_id uuid,
  p_profile_id text
)
returns boolean
language sql
stable
security invoker
set search_path = public, pg_temp
as $function$
  select coalesce((
    select public.vibesync_jwt_session_active()
       and p_profile_id = public.vibesync_current_profile_id()
       and m.left_at is null
       and (
         not g.admins_only_messages
         or m.role in ('owner', 'admin')
       )
    from public.chat_group_members m
    join public.chat_groups g on g.id = m.group_id
    where m.group_id = p_group_id
      and m.profile_id = p_profile_id
    limit 1
  ), false);
$function$;

-- A LEFT JOIN preserves roster membership when profile RLS correctly hides a peer's profile row.
create or replace function public.vibesync_list_chat_group_members(p_group_id uuid)
returns table(
  profile_id text,
  display_name text,
  avatar_url text,
  role text,
  joined_at timestamptz,
  is_muted_until timestamptz,
  is_pinned boolean,
  is_you boolean
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $function$
  select m.profile_id,
         coalesce(nullif(trim(p.name), ''), 'VibeSync member'),
         p.avatar_url,
         m.role,
         m.joined_at,
         m.is_muted_until,
         m.is_pinned,
         m.profile_id = public.vibesync_current_profile_id()
  from public.chat_group_members m
  left join public.profiles p on p.id = m.profile_id
  where m.group_id = p_group_id
    and m.left_at is null
    and public.vibesync_jwt_session_active()
    and private.vibesync_group_has_active_member(p_group_id)
  order by case m.role when 'owner' then 0 when 'admin' then 1 else 2 end, m.joined_at;
$function$;

-- invite_code is intentionally not granted as a directly selectable column. Return it only through
-- a narrow private helper that verifies this caller is an active owner/admin of the requested group.
create or replace function public.vibesync_list_my_chat_groups()
returns table(
  group_id uuid,
  title text,
  description text,
  avatar_url text,
  created_by text,
  invite_code text,
  admins_only_messages boolean,
  admins_only_info_editing boolean,
  role text,
  member_count integer,
  is_pinned boolean,
  is_muted_until timestamptz
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $function$
  select g.id,
         g.title,
         g.description,
         g.avatar_url,
         g.created_by,
         case when mine.role in ('owner', 'admin')
              then private.vibesync_admin_invite_code(g.id)
              else null end,
         g.admins_only_messages,
         g.admins_only_info_editing,
         mine.role,
         (select count(*)::integer
          from public.chat_group_members active
          where active.group_id = g.id and active.left_at is null),
         mine.is_pinned,
         mine.is_muted_until
  from public.chat_groups g
  join public.chat_group_members mine on mine.group_id = g.id
  where public.vibesync_jwt_session_active()
    and mine.profile_id = public.vibesync_current_profile_id()
    and mine.left_at is null
  order by mine.is_pinned desc, g.updated_at desc;
$function$;

-- Invoker version resolves a valid invite only through the private, boolean/UUID-only helper.
-- The transaction-local code is consumed by RLS policies and the membership rejoin guard.
create or replace function public.vibesync_join_chat_group_by_invite(p_invite_code text)
returns uuid
language plpgsql
security invoker
set search_path = public, pg_temp
as $function$
declare
  v_actor text := public.vibesync_current_profile_id();
  v_group_id uuid;
  v_code text := nullif(trim(p_invite_code), '');
begin
  if not public.vibesync_jwt_session_active() or coalesce(v_actor, '') = '' then
    raise exception 'verified_session_required' using errcode = '42501';
  end if;
  if v_code is null then
    raise exception 'invite_invalid_or_revoked' using errcode = '22023';
  end if;

  v_group_id := private.vibesync_group_id_for_invite(v_code);
  if v_group_id is null then
    raise exception 'invite_invalid_or_revoked' using errcode = '22023';
  end if;

  perform set_config('vibesync.chat_group_invite_code', v_code, true);
  insert into public.chat_group_members(group_id, profile_id, role)
  values (v_group_id, v_actor, 'member')
  on conflict (group_id, profile_id) do update
    set joined_at = case
          when public.chat_group_members.left_at is null then public.chat_group_members.joined_at
          else now()
        end,
        left_at = null,
        role = case
          when public.chat_group_members.role = 'owner' then 'owner'
          when public.chat_group_members.left_at is null then public.chat_group_members.role
          else 'member'
        end;

  return v_group_id;
end;
$function$;

-- Avoid SELECT privilege on the secret invite_code column (including RETURNING). The generated
-- code is already known locally by the authorized mutator, so return that value directly.
create or replace function public.vibesync_rotate_chat_group_invite(
  p_group_id uuid,
  p_revoke boolean default false
)
returns text
language plpgsql
security invoker
set search_path = public, pg_temp
as $function$
declare
  v_actor text := public.vibesync_current_profile_id();
  v_code text;
begin
  if not public.vibesync_jwt_session_active() or coalesce(v_actor, '') = '' then
    raise exception 'verified_session_required' using errcode = '42501';
  end if;
  if public.vibesync_chat_group_member_role(p_group_id, v_actor) not in ('owner', 'admin') then
    raise exception 'group_admin_required' using errcode = '42501';
  end if;

  if p_revoke then
    update public.chat_groups
      set invite_code = null, invite_revoked_at = now(), updated_at = now()
      where id = p_group_id;
    return null;
  end if;

  v_code := replace(gen_random_uuid()::text, '-', '') ||
            replace(gen_random_uuid()::text, '-', '');
  update public.chat_groups
    set invite_code = v_code, invite_revoked_at = null, updated_at = now()
    where id = p_group_id;
  if not found then
    raise exception 'group_not_found_or_not_accessible' using errcode = '42501';
  end if;
  return v_code;
end;
$function$;

-- RLS-safe guardrails for columns that row policies alone cannot distinguish (e.g. whether a
-- member edits group details versus changes admin flags or rotates an invite).
create or replace function private.vibesync_guard_chat_group_update()
returns trigger
language plpgsql
security invoker
set search_path = public, pg_temp
as $function$
declare
  v_actor text := public.vibesync_current_profile_id();
  v_role text;
begin
  if new.id is distinct from old.id or new.created_by is distinct from old.created_by then
    raise exception 'group_identity_is_immutable' using errcode = '42501';
  end if;
  if not public.vibesync_jwt_session_active() or coalesce(v_actor, '') = '' then
    raise exception 'verified_session_required' using errcode = '42501';
  end if;

  v_role := public.vibesync_chat_group_member_role(old.id, v_actor);
  if v_role not in ('owner', 'admin') then
    if new.admins_only_messages is distinct from old.admins_only_messages
       or new.admins_only_info_editing is distinct from old.admins_only_info_editing
       or new.invite_code is distinct from old.invite_code
       or new.invite_revoked_at is distinct from old.invite_revoked_at then
      raise exception 'group_admin_required' using errcode = '42501';
    end if;
    if old.admins_only_info_editing
       and (new.title is distinct from old.title
         or new.description is distinct from old.description
         or new.avatar_url is distinct from old.avatar_url) then
      raise exception 'group_info_admins_only' using errcode = '42501';
    end if;
  end if;
  return new;
end;
$function$;

create or replace function private.vibesync_guard_chat_group_member_update()
returns trigger
language plpgsql
security invoker
set search_path = public, pg_temp
as $function$
declare
  v_actor text := public.vibesync_current_profile_id();
  v_actor_role text;
  v_invite_rejoin boolean;
begin
  if new.group_id is distinct from old.group_id or new.profile_id is distinct from old.profile_id then
    raise exception 'group_member_identity_is_immutable' using errcode = '42501';
  end if;
  if not public.vibesync_jwt_session_active() or coalesce(v_actor, '') = '' then
    raise exception 'verified_session_required' using errcode = '42501';
  end if;

  v_actor_role := public.vibesync_chat_group_member_role(old.group_id, v_actor);
  v_invite_rejoin := old.left_at is not null
    and new.left_at is null
    and private.vibesync_group_invite_matches(
      old.group_id,
      current_setting('vibesync.chat_group_invite_code', true)
    );

  if old.profile_id = v_actor then
    if old.role = 'owner' and new.left_at is distinct from old.left_at then
      raise exception 'owner_must_delete_or_transfer_group' using errcode = '42501';
    end if;
    if new.role is distinct from old.role and not v_invite_rejoin then
      raise exception 'members_cannot_change_own_role' using errcode = '42501';
    end if;
    if new.joined_at is distinct from old.joined_at and not v_invite_rejoin then
      raise exception 'joined_at_is_managed_by_membership_flow' using errcode = '42501';
    end if;
    if v_invite_rejoin and new.role <> 'member' and new.role <> 'owner' then
      raise exception 'invalid_rejoin_role' using errcode = '42501';
    end if;
  else
    if v_actor_role not in ('owner', 'admin') or old.role = 'owner' then
      raise exception 'owner_or_authorized_admin_required' using errcode = '42501';
    end if;
    if v_actor_role = 'admin' and old.role <> 'member' then
      raise exception 'admins_cannot_manage_admins' using errcode = '42501';
    end if;
    if new.role = 'owner' and old.role <> 'owner' then
      raise exception 'owner_transfer_not_supported' using errcode = '42501';
    end if;
    if new.joined_at is distinct from old.joined_at then
      raise exception 'joined_at_is_managed_by_membership_flow' using errcode = '42501';
    end if;
    if new.left_at is null and old.left_at is not null then
      raise exception 'only_the_member_can_rejoin_by_invite' using errcode = '42501';
    end if;
    if new.is_pinned is distinct from old.is_pinned
       or new.is_muted_until is distinct from old.is_muted_until then
      raise exception 'member_preferences_are_private' using errcode = '42501';
    end if;
  end if;
  return new;
end;
$function$;

revoke all on function private.vibesync_guard_chat_group_update() from public, anon;
revoke all on function private.vibesync_guard_chat_group_member_update() from public, anon;

drop trigger if exists trg_vibesync_guard_chat_group_update on public.chat_groups;
create trigger trg_vibesync_guard_chat_group_update
before update on public.chat_groups
for each row execute function private.vibesync_guard_chat_group_update();

drop trigger if exists trg_vibesync_guard_chat_group_member_update on public.chat_group_members;
create trigger trg_vibesync_guard_chat_group_member_update
before update on public.chat_group_members
for each row execute function private.vibesync_guard_chat_group_member_update();

-- Remove recursive member-role checks from the table policies. All user-facing queries now run
-- as authenticated and are filtered by RLS; the private membership boolean is only the recursion
-- breaker needed by those policies.
drop policy if exists chat_groups_select_active_members on public.chat_groups;
create policy chat_groups_select_active_members
on public.chat_groups for select to authenticated
using (
  public.vibesync_jwt_session_active()
  and (
    private.vibesync_group_has_active_member(id)
    or created_by = public.vibesync_current_profile_id()
    or private.vibesync_group_invite_matches(
      id, current_setting('vibesync.chat_group_invite_code', true)
    )
  )
);

drop policy if exists chat_group_members_select_active_group_members on public.chat_group_members;
create policy chat_group_members_select_active_group_members
on public.chat_group_members for select to authenticated
using (
  public.vibesync_jwt_session_active()
  and (
    private.vibesync_group_has_active_member(group_id)
    or (
      profile_id = public.vibesync_current_profile_id()
      and private.vibesync_group_invite_matches(
        group_id, current_setting('vibesync.chat_group_invite_code', true)
      )
    )
  )
);

drop policy if exists chat_group_messages_select_active_members on public.chat_group_messages;
create policy chat_group_messages_select_active_members
on public.chat_group_messages for select to authenticated
using (
  public.vibesync_jwt_session_active()
  and private.vibesync_group_has_active_member(group_id)
);

drop policy if exists chat_group_messages_insert_sender on public.chat_group_messages;
create policy chat_group_messages_insert_sender
on public.chat_group_messages for insert to authenticated
with check (
  public.vibesync_jwt_session_active()
  and sender_profile_id = public.vibesync_current_profile_id()
  and public.vibesync_can_send_chat_group_message(group_id, sender_profile_id)
);

drop policy if exists chat_group_message_envelopes_select_recipient on public.chat_group_message_envelopes;
create policy chat_group_message_envelopes_select_recipient
on public.chat_group_message_envelopes for select to authenticated
using (
  public.vibesync_jwt_session_active()
  and recipient_profile_id = public.vibesync_current_profile_id()
  and private.vibesync_group_has_active_member(group_id)
);

drop policy if exists chat_group_message_envelopes_insert_sender on public.chat_group_message_envelopes;
create policy chat_group_message_envelopes_insert_sender
on public.chat_group_message_envelopes for insert to authenticated
with check (
  public.vibesync_jwt_session_active()
  and private.vibesync_group_has_active_member(group_id)
  and recipient_profile_id in (
    select m.profile_id from public.chat_group_members m
    where m.group_id = chat_group_message_envelopes.group_id
      and m.left_at is null
  )
  and exists (
    select 1
    from public.chat_group_messages gm
    where gm.message_id = chat_group_message_envelopes.message_id
      and gm.group_id = chat_group_message_envelopes.group_id
      and gm.sender_profile_id = public.vibesync_current_profile_id()
      and public.vibesync_can_send_chat_group_message(gm.group_id, gm.sender_profile_id)
  )
);

drop policy if exists chat_group_members_insert_authorized on public.chat_group_members;
create policy chat_group_members_insert_authorized
on public.chat_group_members for insert to authenticated
with check (
  public.vibesync_jwt_session_active()
  and (
    (
      profile_id = public.vibesync_current_profile_id()
      and (
        exists (
          select 1 from public.chat_groups g
          where g.id = chat_group_members.group_id
            and g.created_by = public.vibesync_current_profile_id()
        )
        or private.vibesync_group_invite_matches(
          group_id, current_setting('vibesync.chat_group_invite_code', true)
        )
      )
    )
    or public.vibesync_chat_group_member_role(
      group_id, public.vibesync_current_profile_id()
    ) in ('owner', 'admin')
  )
);

drop policy if exists chat_group_members_update_authorized on public.chat_group_members;
create policy chat_group_members_update_authorized
on public.chat_group_members for update to authenticated
using (
  public.vibesync_jwt_session_active()
  and (
    (
      profile_id = public.vibesync_current_profile_id()
      and (
        left_at is null
        or private.vibesync_group_invite_matches(
          group_id, current_setting('vibesync.chat_group_invite_code', true)
        )
      )
    )
    or public.vibesync_chat_group_member_role(
      group_id, public.vibesync_current_profile_id()
    ) in ('owner', 'admin')
  )
)
with check (
  public.vibesync_jwt_session_active()
  and (
    profile_id = public.vibesync_current_profile_id()
    or public.vibesync_chat_group_member_role(
      group_id, public.vibesync_current_profile_id()
    ) in ('owner', 'admin')
  )
);

drop policy if exists chat_groups_insert_creator on public.chat_groups;
create policy chat_groups_insert_creator
on public.chat_groups for insert to authenticated
with check (
  public.vibesync_jwt_session_active()
  and created_by = public.vibesync_current_profile_id()
);

drop policy if exists chat_groups_update_members on public.chat_groups;
create policy chat_groups_update_members
on public.chat_groups for update to authenticated
using (
  public.vibesync_jwt_session_active()
  and private.vibesync_group_has_active_member(id)
  and (
    not admins_only_info_editing
    or public.vibesync_chat_group_member_role(
      id, public.vibesync_current_profile_id()
    ) in ('owner', 'admin')
  )
)
with check (
  public.vibesync_jwt_session_active()
  and private.vibesync_group_has_active_member(id)
  and (
    not admins_only_info_editing
    or public.vibesync_chat_group_member_role(
      id, public.vibesync_current_profile_id()
    ) in ('owner', 'admin')
  )
);

drop policy if exists chat_groups_delete_owner on public.chat_groups;
create policy chat_groups_delete_owner
on public.chat_groups for delete to authenticated
using (
  public.vibesync_jwt_session_active()
  and created_by = public.vibesync_current_profile_id()
  and public.vibesync_chat_group_member_role(
    id, public.vibesync_current_profile_id()
  ) = 'owner'
);

-- Grant only columns required by the invoker RPCs. In particular, invite_code remains unreadable
-- through direct REST table queries. Policy/trigger checks above guard sensitive mutations.
grant insert (title, description, avatar_url, created_by, invite_code)
  on public.chat_groups to authenticated;
grant update (title, description, avatar_url, admins_only_messages,
              admins_only_info_editing, invite_code, invite_revoked_at, updated_at)
  on public.chat_groups to authenticated;
grant delete on public.chat_groups to authenticated;

grant insert (group_id, profile_id, role)
  on public.chat_group_members to authenticated;
grant update (joined_at, left_at, role, is_pinned, is_muted_until)
  on public.chat_group_members to authenticated;

grant insert (message_id, group_id, sender_profile_id, sender_name)
  on public.chat_group_messages to authenticated;
grant insert (group_id, message_id, recipient_profile_id, ciphertext)
  on public.chat_group_message_envelopes to authenticated;

-- Apply SECURITY INVOKER and a fixed search_path to all 15 functions reported by the live advisor.
-- The aliases below use the exact deployed signatures; two names/signatures in older client notes
-- no longer match the deployed group RPC schema.
alter function public.vibesync_can_send_chat_group_message(uuid, text) security invoker;
alter function public.vibesync_can_send_chat_group_message(uuid, text) set search_path = public, pg_temp;
alter function public.vibesync_chat_group_member_role(uuid, text) security invoker;
alter function public.vibesync_chat_group_member_role(uuid, text) set search_path = public, pg_temp;
alter function public.vibesync_create_chat_group(text, text, text, text[]) security invoker;
alter function public.vibesync_create_chat_group(text, text, text, text[]) set search_path = public, pg_temp;
alter function public.vibesync_delete_chat_group(uuid) security invoker;
alter function public.vibesync_delete_chat_group(uuid) set search_path = public, pg_temp;
alter function public.vibesync_join_chat_group_by_invite(text) security invoker;
alter function public.vibesync_join_chat_group_by_invite(text) set search_path = public, pg_temp;
alter function public.vibesync_leave_chat_group(uuid) security invoker;
alter function public.vibesync_leave_chat_group(uuid) set search_path = public, pg_temp;
alter function public.vibesync_list_chat_group_members(uuid) security invoker;
alter function public.vibesync_list_chat_group_members(uuid) set search_path = public, pg_temp;
alter function public.vibesync_list_my_chat_groups() security invoker;
alter function public.vibesync_list_my_chat_groups() set search_path = public, pg_temp;
alter function public.vibesync_remove_chat_group_member(uuid, text) security invoker;
alter function public.vibesync_remove_chat_group_member(uuid, text) set search_path = public, pg_temp;
alter function public.vibesync_rotate_chat_group_invite(uuid, boolean) security invoker;
alter function public.vibesync_rotate_chat_group_invite(uuid, boolean) set search_path = public, pg_temp;
alter function public.vibesync_send_chat_group_message(uuid, uuid, jsonb) security invoker;
alter function public.vibesync_send_chat_group_message(uuid, uuid, jsonb) set search_path = public, pg_temp;
alter function public.vibesync_set_chat_group_member_role(uuid, text, text) security invoker;
alter function public.vibesync_set_chat_group_member_role(uuid, text, text) set search_path = public, pg_temp;
alter function public.vibesync_set_chat_group_preferences(uuid, boolean, timestamptz) security invoker;
alter function public.vibesync_set_chat_group_preferences(uuid, boolean, timestamptz) set search_path = public, pg_temp;
alter function public.vibesync_update_chat_group_info(uuid, text, text, text) security invoker;
alter function public.vibesync_update_chat_group_info(uuid, text, text, text) set search_path = public, pg_temp;
alter function public.vibesync_update_chat_group_settings(uuid, boolean, boolean) security invoker;
alter function public.vibesync_update_chat_group_settings(uuid, boolean, boolean) set search_path = public, pg_temp;

-- Keep the RPC boundary explicit while preserving client EXECUTE grants for authenticated sessions.
revoke all on function public.vibesync_can_send_chat_group_message(uuid, text) from public, anon;
revoke all on function public.vibesync_chat_group_member_role(uuid, text) from public, anon;
revoke all on function public.vibesync_create_chat_group(text, text, text, text[]) from public, anon;
revoke all on function public.vibesync_delete_chat_group(uuid) from public, anon;
revoke all on function public.vibesync_join_chat_group_by_invite(text) from public, anon;
revoke all on function public.vibesync_leave_chat_group(uuid) from public, anon;
revoke all on function public.vibesync_list_chat_group_members(uuid) from public, anon;
revoke all on function public.vibesync_list_my_chat_groups() from public, anon;
revoke all on function public.vibesync_remove_chat_group_member(uuid, text) from public, anon;
revoke all on function public.vibesync_rotate_chat_group_invite(uuid, boolean) from public, anon;
revoke all on function public.vibesync_send_chat_group_message(uuid, uuid, jsonb) from public, anon;
revoke all on function public.vibesync_set_chat_group_member_role(uuid, text, text) from public, anon;
revoke all on function public.vibesync_set_chat_group_preferences(uuid, boolean, timestamptz) from public, anon;
revoke all on function public.vibesync_update_chat_group_info(uuid, text, text, text) from public, anon;
revoke all on function public.vibesync_update_chat_group_settings(uuid, boolean, boolean) from public, anon;

grant execute on function public.vibesync_can_send_chat_group_message(uuid, text) to authenticated;
grant execute on function public.vibesync_chat_group_member_role(uuid, text) to authenticated;
grant execute on function public.vibesync_create_chat_group(text, text, text, text[]) to authenticated;
grant execute on function public.vibesync_delete_chat_group(uuid) to authenticated;
grant execute on function public.vibesync_join_chat_group_by_invite(text) to authenticated;
grant execute on function public.vibesync_leave_chat_group(uuid) to authenticated;
grant execute on function public.vibesync_list_chat_group_members(uuid) to authenticated;
grant execute on function public.vibesync_list_my_chat_groups() to authenticated;
grant execute on function public.vibesync_remove_chat_group_member(uuid, text) to authenticated;
grant execute on function public.vibesync_rotate_chat_group_invite(uuid, boolean) to authenticated;
grant execute on function public.vibesync_send_chat_group_message(uuid, uuid, jsonb) to authenticated;
grant execute on function public.vibesync_set_chat_group_member_role(uuid, text, text) to authenticated;
grant execute on function public.vibesync_set_chat_group_preferences(uuid, boolean, timestamptz) to authenticated;
grant execute on function public.vibesync_update_chat_group_info(uuid, text, text, text) to authenticated;
grant execute on function public.vibesync_update_chat_group_settings(uuid, boolean, boolean) to authenticated;

notify pgrst, 'reload schema';
commit;
