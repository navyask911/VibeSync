-- Voice notes are end-to-end encrypted by the Android client before upload.
-- The bucket is private; this policy permits only authenticated sessions to transport ciphertext.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('chat-voice-notes', 'chat-voice-notes', false, 15728640, array['application/octet-stream'])
on conflict (id) do update
set public = false,
    file_size_limit = 15728640,
    allowed_mime_types = array['application/octet-stream'];

drop policy if exists "authenticated users can upload encrypted chat voice notes" on storage.objects;
create policy "authenticated users can upload encrypted chat voice notes"
on storage.objects for insert to authenticated
with check (bucket_id = 'chat-voice-notes');

drop policy if exists "authenticated users can read encrypted chat voice notes" on storage.objects;
create policy "authenticated users can read encrypted chat voice notes"
on storage.objects for select to authenticated
using (bucket_id = 'chat-voice-notes');

drop policy if exists "authenticated users can delete encrypted chat voice notes" on storage.objects;
create policy "authenticated users can delete encrypted chat voice notes"
on storage.objects for delete to authenticated
using (bucket_id = 'chat-voice-notes');
