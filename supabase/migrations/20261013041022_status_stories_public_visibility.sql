-- Normalize public-story visibility aliases while retaining authenticated-session and expiry gates.
begin;
drop policy if exists status_stories_select_active_visible on public.status_stories;
create policy status_stories_select_active_visible
  on public.status_stories for select to authenticated
  using (
    public.vibesync_jwt_session_active()
    and "createdAt" >= ((extract(epoch from now()) * 1000)::bigint - 86400000)
    and "expiresAt" > (extract(epoch from now()) * 1000)::bigint
    and (
      "userId" = public.vibesync_jwt_profile_id()
      or upper(trim(coalesce("privacy", 'FRIENDS_AND_MATCHES'))) in ('EVERYONE','ALL_NETWORK','PUBLIC','ALL')
      or public.vibesync_jwt_profile_id() = any(coalesce("allowedViewerProfileIds", '{}'::text[]))
    )
  );
notify pgrst, 'reload schema';
commit;
