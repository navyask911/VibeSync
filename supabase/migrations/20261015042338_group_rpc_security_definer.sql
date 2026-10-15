-- Ensure group RPCs run with owner privileges while validating the caller's verified VibeSync JWT.
-- Explicit ALTERs are kept separate from CREATE OR REPLACE definitions so deployment tooling
-- cannot silently leave them as SECURITY INVOKER.
begin;
alter function public.vibesync_chat_group_member_role(uuid,text) security definer;
alter function public.vibesync_can_send_chat_group_message(uuid,text) security definer;
alter function public.vibesync_list_my_chat_groups() security definer;
alter function public.vibesync_list_chat_group_members(uuid) security definer;
alter function public.vibesync_create_chat_group(text,text,text,text[]) security definer;
alter function public.vibesync_update_chat_group_info(uuid,text,text,text) security definer;
alter function public.vibesync_update_chat_group_settings(uuid,boolean,boolean) security definer;
alter function public.vibesync_set_chat_group_member_role(uuid,text,text) security definer;
alter function public.vibesync_remove_chat_group_member(uuid,text) security definer;
alter function public.vibesync_leave_chat_group(uuid) security definer;
alter function public.vibesync_delete_chat_group(uuid) security definer;
alter function public.vibesync_set_chat_group_preferences(uuid,boolean,timestamptz) security definer;
alter function public.vibesync_rotate_chat_group_invite(uuid,boolean) security definer;
alter function public.vibesync_join_chat_group_by_invite(text) security definer;
alter function public.vibesync_send_chat_group_message(uuid,uuid,jsonb) security definer;
commit;