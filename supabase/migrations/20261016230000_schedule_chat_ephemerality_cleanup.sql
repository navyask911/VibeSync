begin;

-- Run ciphertext TTL and retryable private-media cleanup once per day at 03:17 UTC.
-- The job is installed only when pg_cron is available; this remains safe in local/dev stacks
-- where the extension may not be enabled.
do $schedule$
declare
  v_existing_job bigint;
begin
  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    select jobid into v_existing_job
      from cron.job
     where jobname = 'vibesync-chat-undelivered-ttl-30d'
     limit 1;

    if v_existing_job is null then
      perform cron.schedule(
        'vibesync-chat-undelivered-ttl-30d',
        '17 3 * * *',
        'select public.cleanup_expired_undelivered_chat_messages();'
      );
    end if;
  end if;
end;
$schedule$;

commit;
