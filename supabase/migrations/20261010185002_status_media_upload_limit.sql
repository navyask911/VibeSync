-- The Android story uploader streams media and enforces a 100 MiB cap.
-- Keep Supabase Storage's private story bucket in sync with the client-side limit.
begin;
update storage.buckets
set file_size_limit = 104857600
where id = 'status-media';
commit;
