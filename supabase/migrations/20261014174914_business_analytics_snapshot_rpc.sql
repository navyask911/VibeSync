begin;

-- Keep direct event ingestion bound to a live VibeSync session and a published business.
drop policy if exists business_analytics_insert_events on public.business_analytics_events;
create policy business_analytics_insert_events
  on public.business_analytics_events for insert to authenticated
  with check (
    public.vibesync_jwt_session_active()
    and exists (
      select 1 from public.businesses b
       where b.id = business_analytics_events.business_id
         and b.is_published = true
    )
  );

-- Owner-only analytics snapshot. The UI never needs broad SELECT access to the event ledger.
create or replace function public.get_my_business_analytics_snapshot(
  p_business_id text,
  p_range text default 'LAST 7 DAYS'
) returns jsonb
language plpgsql security definer set search_path=pg_catalog,public as $$
declare
  uid text := public.vibesync_jwt_profile_id();
  since_ts timestamptz;
  trend_start timestamptz;
  views integer := 0;
  impressions integer := 0;
  inquiries integer := 0;
  broadcast_opens integer := 0;
  broadcast_clicks integer := 0;
  location_clicks integer := 0;
  call_clicks integer := 0;
  trend_rows jsonb := '[]'::jsonb;
  normalized_range text := upper(trim(coalesce(p_range,'LAST 7 DAYS')));
begin
  if not public.vibesync_jwt_session_active() or uid is null then
    raise exception 'verified_session_required' using errcode='42501';
  end if;
  if not exists (
    select 1 from public.businesses
     where id=p_business_id and owner_profile_id=uid and is_published=true
  ) then
    raise exception 'business_owner_required' using errcode='42501';
  end if;

  since_ts := case normalized_range
    when 'TODAY' then date_trunc('day', now())
    when 'LAST 7 DAYS' then now() - interval '7 days'
    when 'LAST 30 DAYS' then now() - interval '30 days'
    when 'ALL TIME' then null
    else null
  end;
  if normalized_range not in ('TODAY','LAST 7 DAYS','LAST 30 DAYS','ALL TIME') then
    raise exception 'invalid_analytics_range' using errcode='22023';
  end if;

  select
    count(*) filter (where event_type='VIEW')::integer,
    count(*) filter (where event_type='IMPRESSION')::integer,
    count(*) filter (where event_type='CHAT_INQUIRY')::integer,
    count(*) filter (where event_type='BROADCAST_OPEN')::integer,
    count(*) filter (where event_type='BROADCAST_CLICK')::integer,
    count(*) filter (where event_type='LOCATION_CLICK')::integer,
    count(*) filter (where event_type='CALL_CLICK')::integer
  into views, impressions, inquiries, broadcast_opens, broadcast_clicks, location_clicks, call_clicks
  from public.business_analytics_events
  where business_id=p_business_id and (since_ts is null or created_at >= since_ts);

  -- Always render a bounded 14-day trend. All-time totals remain all-time, while the plot stays
  -- readable and does not generate thousands of empty days for older listings.
  trend_start := greatest(
    coalesce(date_trunc('day', since_ts), date_trunc('day', now() - interval '13 days')),
    date_trunc('day', now() - interval '13 days')
  );
  select coalesce(jsonb_agg(jsonb_build_object(
    'day', to_char(day_rows.day, 'YYYY-MM-DD'),
    'views', day_rows.views,
    'impressions', day_rows.impressions,
    'broadcast_opens', day_rows.broadcast_opens,
    'broadcast_clicks', day_rows.broadcast_clicks,
    'inquiries', day_rows.inquiries,
    'location_clicks', day_rows.location_clicks,
    'call_clicks', day_rows.call_clicks
  ) order by day_rows.day), '[]'::jsonb)
  into trend_rows
  from (
    select day_series.day,
      count(e.id) filter (where e.event_type='VIEW')::integer as views,
      count(e.id) filter (where e.event_type='IMPRESSION')::integer as impressions,
      count(e.id) filter (where e.event_type='BROADCAST_OPEN')::integer as broadcast_opens,
      count(e.id) filter (where e.event_type='BROADCAST_CLICK')::integer as broadcast_clicks,
      count(e.id) filter (where e.event_type='CHAT_INQUIRY')::integer as inquiries,
      count(e.id) filter (where e.event_type='LOCATION_CLICK')::integer as location_clicks,
      count(e.id) filter (where e.event_type='CALL_CLICK')::integer as call_clicks
    from generate_series(trend_start, date_trunc('day', now()), interval '1 day') as day_series(day)
    left join public.business_analytics_events e
      on e.business_id=p_business_id
     and date_trunc('day',e.created_at)=day_series.day
     and (since_ts is null or e.created_at >= since_ts)
    group by day_series.day
  ) as day_rows;

  return jsonb_build_object(
    'ok',true,'range',normalized_range,
    'profile_views',coalesce(views,0),'impressions',coalesce(impressions,0),
    'chat_inquiries',coalesce(inquiries,0),'broadcast_opens',coalesce(broadcast_opens,0),
    'broadcast_clicks',coalesce(broadcast_clicks,0),
    'broadcast_click_rate_pct',case when coalesce(broadcast_opens,0)>0 then round((broadcast_clicks::numeric/broadcast_opens::numeric)*100,1) else 0 end,
    'location_clicks',coalesce(location_clicks,0),'call_clicks',coalesce(call_clicks,0),
    'trend',coalesce(trend_rows,'[]'::jsonb)
  );
end $$;

revoke all on function public.get_my_business_analytics_snapshot(text,text) from public,anon;
grant execute on function public.get_my_business_analytics_snapshot(text,text) to authenticated;

commit;
