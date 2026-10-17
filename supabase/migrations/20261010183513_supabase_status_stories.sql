-- Move status story metadata and media authorization onto the Supabase-only backend.
-- Story records expire after 24 hours; visibility is enforced by RLS before signed URLs can be issued.
begin;

alter table public.status_stories
  add column if not exists "userAvatarUrl" text not null default '',
  add column if not exists "mediaType" text not null default 'PHOTO',
  add column if not exists "backgroundMusicUrl" text not null default '',
  add column if not exists "videoDurationSeconds" integer not null default 0,
  add column if not exists "backgroundColorStart" bigint not null default 4278233220,
  add column if not exists "backgroundColorEnd" bigint not null default 4279405694,
  add column if not exists "privacy" text not null default 'FRIENDS_AND_MATCHES',
  add column if not exists "allowedViewerProfileIds" text[] not null default '{}'::text[];

create index if not exists idx_status_stories_expiry_created
  on public.status_stories ("expiresAt", "createdAt" desc);

alter table public.status_stories enable row level security;

do $$
declare policy_row record;
begin
  for policy_row in
    select policyname from pg_policies
    where schemaname = 'public' and tablename = 'status_stories'
  loop
    execute format('drop policy %I on public.status_stories', policy_row.policyname);
  end loop;
end;
$$;

revoke all on table public.status_stories from anon, authenticated, public;
grant select, insert, delete on table public.status_stories to authenticated;

create policy status_stories_select_active_visible
  on public.status_stories
  for select to authenticated
  using (
    public.vibesync_jwt_session_active()
    and "createdAt" >= ((extract(epoch from now()) * 1000)::bigint - 86400000)
    and "expiresAt" > (extract(epoch from now()) * 1000)::bigint
    and (
      "userId" = public.vibesync_jwt_profile_id()
      or upper(coalesce("privacy", 'FRIENDS_AND_MATCHES')) in ('EVERYONE', 'ALL_NETWORK')
      or public.vibesync_jwt_profile_id() = any(coalesce("allowedViewerProfileIds", '{}'::text[]))
    )
  );

create policy status_stories_insert_owner
  on public.status_stories
  for insert to authenticated
  with check (
    public.vibesync_jwt_session_active()
    and "userId" = public.vibesync_jwt_profile_id()
    and "createdAt" <= (extract(epoch from now()) * 1000)::bigint + 60000
    and "expiresAt" > "createdAt"
    and "expiresAt" <= "createdAt" + 86400000
  );

create policy status_stories_delete_owner
  on public.status_stories
  for delete to authenticated
  using (
    public.vibesync_jwt_session_active()
    and "userId" = public.vibesync_jwt_profile_id()
  );

-- The previous combined media policies accidentally made status-media public.
-- Retain legacy access behavior for non-story buckets, but keep this bucket private.
drop policy if exists "Allow media downloads" on storage.objects;
drop policy if exists "Allow media uploads" on storage.objects;
create policy legacy_media_downloads_except_status_stories
  on storage.objects
  for select to public
  using (bucket_id = any(array['chat_media','chat-media','status_media','avatars']::text[]));
create policy legacy_media_uploads_except_status_stories
  on storage.objects
  for insert to public
  with check (bucket_id = any(array['chat_media','chat-media','status_media','avatars']::text[]));

insert into storage.buckets (id, name, public)
values ('status-media', 'status-media', false)
on conflict (id) do update set public = false;

drop policy if exists status_media_read_verified_viewers on storage.objects;
create policy status_media_read_verified_viewers
  on storage.objects
  for select to authenticated
  using (
    bucket_id = 'status-media'
    and public.vibesync_jwt_session_active()
    and (
      (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
      or exists (
        select 1
        from public.status_stories s
        where s."mediaUrl" = name
           or s."backgroundMusicUrl" = name
      )
    )
  );

drop policy if exists status_media_insert_owner on storage.objects;
create policy status_media_insert_owner
  on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'status-media'
    and public.vibesync_jwt_session_active()
    and (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
  );

drop policy if exists status_media_delete_owner on storage.objects;
create policy status_media_delete_owner
  on storage.objects
  for delete to authenticated
  using (
    bucket_id = 'status-media'
    and public.vibesync_jwt_session_active()
    and (storage.foldername(name))[1] = public.vibesync_jwt_profile_id()
  );

grant select, insert, delete on table storage.objects to authenticated;
notify pgrst, 'reload schema';
commit;
