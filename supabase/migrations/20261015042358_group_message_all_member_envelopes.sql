-- Require one recipient-specific ciphertext envelope for every active group member.
-- This prevents a valid sender from silently excluding individual members from a group message.
begin;
create or replace function public.vibesync_send_chat_group_message(
  p_group_id uuid, p_message_id uuid, p_envelopes jsonb
)
returns uuid
language plpgsql security definer
set search_path = pg_catalog, public
as $function$
declare
  v_actor text := public.vibesync_jwt_profile_id();
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
alter function public.vibesync_send_chat_group_message(uuid,uuid,jsonb) security definer;
commit;