-- Public avatar reads; writes are restricted to the active VibeSync profile folder.
begin;
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('profile-avatars', 'profile-avatars', true, 10485760, array['image/jpeg','image/png','image/webp']::text[])
on conflict (id) do update set public = true, file_size_limit = 10485760, allowed_mime_types = array['image/jpeg','image/png','image/webp']::text[];
drop policy if exists profile_avatars_public_read on storage.objects;
create policy profile_avatars_public_read on storage.objects for select to public using (bucket_id = 'profile-avatars');
drop policy if exists profile_avatars_insert_owner on storage.objects;
create policy profile_avatars_insert_owner on storage.objects for insert to authenticated with check (
bucket_id = 'profile-avatars' and public.vibesync_jwt_session_active() and (storage.foldername(name))[1] = public.vibesync_jwt_profile_id());
drop policy if exists profile_avatars_update_owner on storage.objects;
create policy profile_avatars_update_owner on storage.objects for update to authenticated using (
bucket_id = 'profile-avatars' and public.vibesync_jwt_session_active() and (storage.foldername(name))[1] = public.vibesync_jwt_profile_id())
with check (bucket_id = 'profile-avatars' and public.vibesync_jwt_session_active() and (storage.foldername(name))[1] = public.vibesync_jwt_profile_id());
drop policy if exists profile_avatars_delete_owner on storage.objects;
create policy profile_avatars_delete_owner on storage.objects for delete to authenticated using (
bucket_id = 'profile-avatars' and public.vibesync_jwt_session_active() and (storage.foldername(name))[1] = public.vibesync_jwt_profile_id());
grant select, insert, update, delete on table storage.objects to authenticated;
notify pgrst, 'reload schema';
commit;
