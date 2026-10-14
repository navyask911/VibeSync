-- Public bucket URLs remain directly readable, but Storage object metadata must not
-- enumerate other profiles' avatar object names through the authenticated Storage API.
-- Upsert/update still needs SELECT on the caller's own stable avatar object.
begin;

drop policy if exists profile_avatars_public_read on storage.objects;
drop policy if exists profile_avatars_owner_metadata_read on storage.objects;

create policy profile_avatars_owner_metadata_read
on storage.objects
for select
to authenticated
using (
  bucket_id = 'avatars'
  and public.vibesync_jwt_session_active()
  and name in (
    public.vibesync_jwt_profile_id() || '/avatar.webp',
    public.vibesync_jwt_profile_id() || '/avatar.jpg',
    public.vibesync_jwt_profile_id() || '/avatar.png',
    public.vibesync_jwt_profile_id() || '.enc',
    public.vibesync_jwt_profile_id() || '_2.enc',
    public.vibesync_jwt_profile_id() || '_3.enc',
    public.vibesync_jwt_profile_id() || '.jpg',
    public.vibesync_jwt_profile_id() || '_2.jpg',
    public.vibesync_jwt_profile_id() || '_3.jpg'
  )
);

-- Public bucket GETs by exact URL do not require SELECT on storage.objects. Remove anonymous
-- row-listing privilege; authenticated upsert/update is limited by the policy above.
revoke select on table storage.objects from public, anon;
grant select on table storage.objects to authenticated;

commit;
