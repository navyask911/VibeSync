-- All generic chat attachments are encrypted by the sender before private Storage upload.
begin;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('chat-attachments', 'chat-attachments', false, 26214464, array['application/octet-stream'])
on conflict (id) do update
set public = false, file_size_limit = 26214464,
    allowed_mime_types = array['application/octet-stream'];

-- Receipt mappings outlive the ciphertext row and are used to authorize recipient downloads
-- after the hard-delete RPC removes chat_messages.
create or replace function public.vibesync_can_read_chat_storage_object(p_bucket_id text, p_name text)
returns boolean
language plpgsql
stable
security definer
set search_path = pg_catalog, public
as $$
declare
    v_actor text := public.vibesync_jwt_profile_id();
    v_parts text[] := string_to_array(coalesce(p_name, ''), '/');
    v_owner text;
    v_match_id text;
    v_message_id text;
begin
    if v_actor is null or v_actor = '' or not public.vibesync_jwt_session_active() then
        return false;
    end if;
    if array_length(v_parts, 1) <> 4 or
       v_parts[1] is null or v_parts[1] = '' or
       v_parts[2] is null or v_parts[3] is null or v_parts[4] is null or
       v_parts[1] like '%..%' or v_parts[2] like '%..%' or
       v_parts[3] like '%..%' or v_parts[4] like '%..%' then
        return false;
    end if;

    v_owner := v_parts[1];
    v_match_id := v_parts[3];

    if p_bucket_id = 'chat-attachments' and v_parts[2] = 'attachments'
       and right(v_parts[4], 4) = '.enc' then
        v_message_id := left(v_parts[4], length(v_parts[4]) - 4);
    elsif p_bucket_id = 'chat-voice-notes' and v_parts[2] = 'voice-notes'
       and right(v_parts[4], 8) = '.m4a.enc' then
        v_message_id := left(v_parts[4], length(v_parts[4]) - 8);
    else
        return false;
    end if;

    if v_message_id is null or v_message_id = '' or v_message_id like '%..%' then
        return false;
    end if;
    if v_owner = v_actor then
        return true;
    end if;

    return exists (
        select 1
        from public.chat_message_receipt_participants p
        where p.message_id = v_message_id
          and p.match_id = v_match_id
          and p.sender_id = v_owner
          and p.receiver_id = v_actor
    );
end;
$$;

revoke all on function public.vibesync_can_read_chat_storage_object(text, text) from public, anon;
grant execute on function public.vibesync_can_read_chat_storage_object(text, text) to authenticated;

drop policy if exists vibesync_chat_attachments_owner_insert on storage.objects;
create policy vibesync_chat_attachments_owner_insert on storage.objects
for insert to authenticated
with check (
    bucket_id = 'chat-attachments'
    and public.vibesync_jwt_session_active()
    and (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
    and (storage.foldername(name))[2] = 'attachments'
);

drop policy if exists vibesync_chat_attachments_owner_update on storage.objects;
create policy vibesync_chat_attachments_owner_update on storage.objects
for update to authenticated
using (
    bucket_id = 'chat-attachments'
    and public.vibesync_jwt_session_active()
    and (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
    and (storage.foldername(name))[2] = 'attachments'
)
with check (
    bucket_id = 'chat-attachments'
    and public.vibesync_jwt_session_active()
    and (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
    and (storage.foldername(name))[2] = 'attachments'
);

drop policy if exists vibesync_chat_attachments_participant_select on storage.objects;
create policy vibesync_chat_attachments_participant_select on storage.objects
for select to authenticated
using (bucket_id = 'chat-attachments' and public.vibesync_can_read_chat_storage_object(bucket_id, name));

drop policy if exists vibesync_chat_attachments_owner_delete on storage.objects;
create policy vibesync_chat_attachments_owner_delete on storage.objects
for delete to authenticated
using (
    bucket_id = 'chat-attachments'
    and public.vibesync_jwt_session_active()
    and (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
    and (storage.foldername(name))[2] = 'attachments'
);

-- Upsert permission is scoped to an active sender and only their private voice-note folder.
drop policy if exists vibesync_voice_notes_owner_update on storage.objects;
create policy vibesync_voice_notes_owner_update on storage.objects
for update to authenticated
using (
    bucket_id = 'chat-voice-notes'
    and public.vibesync_jwt_session_active()
    and (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
    and (storage.foldername(name))[2] = 'voice-notes'
)
with check (
    bucket_id = 'chat-voice-notes'
    and public.vibesync_jwt_session_active()
    and (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
    and (storage.foldername(name))[2] = 'voice-notes'
);

-- New voice objects will be match/message scoped. Keep the original policy for legacy objects.
drop policy if exists vibesync_voice_notes_receipt_participant_select on storage.objects;
create policy vibesync_voice_notes_receipt_participant_select on storage.objects
for select to authenticated
using (bucket_id = 'chat-voice-notes' and public.vibesync_can_read_chat_storage_object(bucket_id, name));

update storage.buckets
set file_size_limit = 15728656,
    allowed_mime_types = array['application/octet-stream'],
    public = false
where id = 'chat-voice-notes';

notify pgrst, 'reload schema';
commit;
