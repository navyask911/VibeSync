-- Address measured advisor findings and strictly scope Storage operations:
-- 1) index foreign-key columns used for joins/deletes;
-- 2) remove the duplicate non-unique E.164 index;
-- 3) combine overlapping RLS policies without widening the existing owner model;
-- 4) prevent object listing while retaining public-image GET and authenticated owner uploads.
begin;

create index if not exists idx_business_broadcasts_business_id
  on public.business_broadcasts (business_id);
create index if not exists idx_business_payment_orders_business_id
  on public.business_payment_orders (business_id);
create index if not exists idx_business_wallet_payment_orders_business_id
  on public.business_wallet_payment_orders (business_id);
create index if not exists idx_business_wallet_payment_orders_owner_profile_id
  on public.business_wallet_payment_orders (owner_profile_id);
create index if not exists idx_lawful_requests_reviewer_user_id
  on public.lawful_requests (reviewer_user_id);
create index if not exists idx_preservation_holds_created_by
  on public.preservation_holds (created_by);
create index if not exists idx_preservation_holds_request_id
  on public.preservation_holds (request_id);
create index if not exists idx_security_audit_events_actor_user_id
  on public.security_audit_events (actor_user_id);
create index if not exists idx_staff_assignments_assigned_by_profile_id
  on public.staff_assignments (assigned_by_profile_id);

-- Both indexes were ordinary BTREE indexes on the same column; retain idx_profiles_e164.
drop index if exists public.idx_profiles_phone_e164;

-- These two profile policies had identical expressions and granted no additional access.
drop policy if exists "Users can update their own fcm token" on public.profiles;

-- The old public read policy accidentally also allowed object listing because it lacked an
-- operation-aware filter. Public URLs for public buckets work without granting SELECT on rows.
drop policy if exists avatars_direct_image_read on storage.objects;
create policy avatars_direct_image_read
on storage.objects
for select
to public
using (
  bucket_id = 'avatars'
  and storage.allow_any_operation(array[
    'object.get_authenticated_info',
    'object.get_authenticated'
  ])
);

-- Remove the legacy bucket-only INSERT policy (which permitted arbitrary avatar object names).
drop policy if exists avatars_user_insert on storage.objects;
drop policy if exists profile_avatars_insert_owner on storage.objects;
create policy profile_avatars_insert_owner
on storage.objects
for insert
to authenticated
with check (
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

-- Storage upsert requires SELECT + UPDATE. Operation filtering lets upsert's object-info check pass,
-- but explicitly denies list/list_v2 even for the owner's allowed filenames.
drop policy if exists profile_avatars_owner_metadata_read on storage.objects;
drop policy if exists profile_avatars_update_owner on storage.objects;
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
  and storage.allow_any_operation(array[
    'object.get_authenticated_info',
    'object.get_authenticated'
  ])
);

create policy profile_avatars_update_owner
on storage.objects
for update
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
)
with check (
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

drop policy if exists profile_avatars_delete_owner on storage.objects;
create policy profile_avatars_delete_owner
on storage.objects
for delete
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

-- Replace overlapping staff-assignment policies with one authorization union per action.
drop policy if exists staff_assignments_superadmin_all on public.staff_assignments;
drop policy if exists staff_assignments_assignee_read on public.staff_assignments;
drop policy if exists staff_assignments_assignee_update on public.staff_assignments;

create policy staff_assignments_select_authorized
on public.staff_assignments
for select
to authenticated
using (
  public.is_vibesync_super_admin()
  or (
    staff_profile_id = public.vibesync_jwt_profile_id()
    and public.vibesync_jwt_session_active()
  )
);

create policy staff_assignments_insert_superadmin
on public.staff_assignments
for insert
to authenticated
with check (public.is_vibesync_super_admin());

create policy staff_assignments_update_authorized
on public.staff_assignments
for update
to authenticated
using (
  public.is_vibesync_super_admin()
  or (
    staff_profile_id = public.vibesync_jwt_profile_id()
    and public.vibesync_jwt_session_active()
  )
)
with check (
  public.is_vibesync_super_admin()
  or (
    staff_profile_id = public.vibesync_jwt_profile_id()
    and public.vibesync_jwt_session_active()
  )
);

create policy staff_assignments_delete_superadmin
on public.staff_assignments
for delete
to authenticated
using (public.is_vibesync_super_admin());

notify pgrst, 'reload schema';
commit;
