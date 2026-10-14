-- Voice note bytes are Tink AES-256-GCM ciphertext. Upload/delete is limited to the owner profile folder.
-- Reads require an active VibeSync session and either ownership or an existing conversation-participant row.
begin;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('chat-voice-notes', 'chat-voice-notes', false, 15728640, array['application/octet-stream'])
on conflict (id) do update
set public = false, file_size_limit = 15728640,
    allowed_mime_types = array['application/octet-stream'];

drop policy if exists "authenticated users can upload encrypted chat voice notes" on storage.objects;
drop policy if exists "authenticated users can read encrypted chat voice notes" on storage.objects;
drop policy if exists "authenticated users can delete encrypted chat voice notes" on storage.objects;
drop policy if exists vibesync_voice_notes_owner_insert on storage.objects;
drop policy if exists vibesync_voice_notes_participant_select on storage.objects;
drop policy if exists vibesync_voice_notes_owner_delete on storage.objects;

create policy vibesync_voice_notes_owner_insert on storage.objects
for insert to authenticated
with check (
  bucket_id = 'chat-voice-notes'
  and public.vibesync_jwt_session_active()
  and (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
  and (storage.foldername(name))[2] = 'voice-notes'
);

create policy vibesync_voice_notes_participant_select on storage.objects
for select to authenticated
using (
  bucket_id = 'chat-voice-notes'
  and public.vibesync_jwt_session_active()
  and (
    (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
    or exists (
      select 1
      from public.chat_messages cm
      where cm.match_id = (storage.foldername(name))[3]
        and (
          cm.sender_id = public.vibesync_jwt_profile_id()
          or cm.receiver_id = public.vibesync_jwt_profile_id()
        )
    )
  )
);

create policy vibesync_voice_notes_owner_delete on storage.objects
for delete to authenticated
using (
  bucket_id = 'chat-voice-notes'
  and public.vibesync_jwt_session_active()
  and (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
  and (storage.foldername(name))[2] = 'voice-notes'
);

grant select, insert, delete on table storage.objects to authenticated;
notify pgrst, 'reload schema';
commit;
