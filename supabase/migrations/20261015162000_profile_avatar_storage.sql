-- Public reads expose encrypted avatar blobs only. Writes use the live app's avatars bucket and
-- are limited to the current profile's root filename (profileId.enc / profileId_2.enc / profileId_3.enc)
-- or to a profile-owned subfolder for future storage layout changes.
begin;
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('avatars', 'avatars', true, 10485760, array['image/jpeg','image/png','image/webp']::text[])
on conflict (id) do update
set public = true, file_size_limit = 10485760,
    allowed_mime_types = array['image/jpeg','image/png','image/webp']::text[];

drop policy if exists profile_avatars_public_read on storage.objects;
create policy profile_avatars_public_read on storage.objects
for select to public using (bucket_id = 'avatars');

drop policy if exists profile_avatars_insert_owner on storage.objects;
create policy profile_avatars_insert_owner on storage.objects
for insert to authenticated with check (
  bucket_id = 'avatars'
  and public.vibesync_jwt_session_active()
  and (
    (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
    or name = public.vibesync_jwt_profile_id() || '.enc'
    or name ~ ('^' || public.vibesync_jwt_profile_id() || '(_[23])?\\.enc$')
  )
);

drop policy if exists profile_avatars_update_owner on storage.objects;
create policy profile_avatars_update_owner on storage.objects
for update to authenticated using (
  bucket_id = 'avatars'
  and public.vibesync_jwt_session_active()
  and (
    (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
    or name = public.vibesync_jwt_profile_id() || '.enc'
    or name ~ ('^' || public.vibesync_jwt_profile_id() || '(_[23])?\\.enc$')
  )
)
with check (
  bucket_id = 'avatars'
  and public.vibesync_jwt_session_active()
  and (
    (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
    or name = public.vibesync_jwt_profile_id() || '.enc'
    or name ~ ('^' || public.vibesync_jwt_profile_id() || '(_[23])?\\.enc$')
  )
);

drop policy if exists profile_avatars_delete_owner on storage.objects;
create policy profile_avatars_delete_owner on storage.objects
for delete to authenticated using (
  bucket_id = 'avatars'
  and public.vibesync_jwt_session_active()
  and (
    (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
    or name = public.vibesync_jwt_profile_id() || '.enc'
    or name ~ ('^' || public.vibesync_jwt_profile_id() || '(_[23])?\\.enc$')
  )
);

grant select, insert, update, delete on table storage.objects to authenticated;
notify pgrst, 'reload schema';
commit;
