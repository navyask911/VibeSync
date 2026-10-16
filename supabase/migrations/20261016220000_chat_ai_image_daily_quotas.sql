begin;

create table if not exists public.chat_ai_image_daily_usage (
    profile_id text not null,
    usage_date date not null,
    request_count integer not null default 0 check (request_count >= 0),
    primary key (profile_id, usage_date)
);

alter table public.chat_ai_image_daily_usage enable row level security;
revoke all on table public.chat_ai_image_daily_usage from public, anon, authenticated;
grant select, insert, update, delete on table public.chat_ai_image_daily_usage to service_role;

-- Only the server-side Edge Function can consume quota; callers cannot choose another profile.
create or replace function public.consume_chat_ai_image_quota(
    p_profile_id text,
    p_daily_limit integer default 8
)
returns integer
language plpgsql
set search_path = ''
as $function$
declare
    v_profile_id text := nullif(btrim(p_profile_id), '');
    v_daily_limit integer := least(greatest(coalesce(p_daily_limit, 8), 1), 12);
    v_used integer;
    v_day date := (timezone('utc', now()))::date;
begin
    if v_profile_id is null then
        raise exception 'profile_id_required' using errcode = '22023';
    end if;

    insert into public.chat_ai_image_daily_usage(profile_id, usage_date, request_count)
    values (v_profile_id, v_day, 1)
    on conflict (profile_id, usage_date) do update
       set request_count = public.chat_ai_image_daily_usage.request_count + 1
     where public.chat_ai_image_daily_usage.request_count < v_daily_limit
    returning request_count into v_used;

    return v_used;
end;
$function$;

revoke all on function public.consume_chat_ai_image_quota(text, integer) from public, anon, authenticated;
grant execute on function public.consume_chat_ai_image_quota(text, integer) to service_role;

notify pgrst, 'reload schema';
commit;
