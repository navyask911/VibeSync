-- Invite codes are admin-controlled capabilities: ordinary members must not be able to
-- retrieve them from the group listing RPC or direct PostgREST table queries.
begin;

revoke select on table public.chat_groups from anon, authenticated;
grant select (
  id, title, description, avatar_url, created_by, invite_revoked_at,
  admins_only_messages, admins_only_info_editing, created_at, updated_at
) on table public.chat_groups to authenticated;

create or replace function public.vibesync_list_my_chat_groups()
returns table (
  group_id uuid, title text, description text, avatar_url text, created_by text,
  invite_code text, admins_only_messages boolean, admins_only_info_editing boolean,
  role text, member_count integer, is_pinned boolean, is_muted_until timestamptz
)
language sql stable security definer
set search_path = pg_catalog, public
as $function$
  select g.id, g.title, g.description, g.avatar_url, g.created_by,
         case when mine.role in ('owner','admin') then g.invite_code else null end,
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

alter function public.vibesync_list_my_chat_groups() security definer;
revoke all on function public.vibesync_list_my_chat_groups() from public, anon;
grant execute on function public.vibesync_list_my_chat_groups() to authenticated;

commit;
