begin;

-- All business tiers use one point per eligible recipient. Audience eligibility and wallet
-- balance are still recalculated under the owner and wallet locks on the server.
create or replace function public.stage_my_business_broadcast(
 p_business_id text,p_title text,p_message text,p_banner_url text,p_cta_label text,p_cta_url text,
 p_target_type text,p_radius_km integer,p_interest_tags text[]
) returns jsonb language plpgsql security definer set search_path=pg_catalog,public as $$
declare uid text:=public.vibesync_jwt_profile_id(); biz public.businesses%rowtype; audience integer:=0;
 rate numeric(5,2); charge integer; new_id uuid; balance integer;
begin
 if not public.vibesync_jwt_session_active() or uid is null then raise exception 'verified_session_required' using errcode='42501'; end if;
 select * into biz from public.businesses where id=p_business_id and owner_profile_id=uid and is_published for update;
 if not found then raise exception 'business_owner_required' using errcode='42501'; end if;
 if length(trim(coalesce(p_title,''))) not between 2 and 120 or length(trim(coalesce(p_message,''))) not between 1 and 1800 then
 raise exception 'invalid_broadcast_content' using errcode='22023'; end if;
 if p_target_type not in ('ALL_FOLLOWERS','NEARBY','INTEREST_TAGS') then raise exception 'invalid_target_type' using errcode='22023'; end if;
 if p_target_type='NEARBY' and p_radius_km not in (5,10) then raise exception 'invalid_radius' using errcode='22023'; end if;
 select count(*)::integer into audience from public.business_followers f where f.business_id=p_business_id
 and (p_target_type<>'INTEREST_TAGS' or coalesce(cardinality(p_interest_tags),0)=0 or f.interest_tags && p_interest_tags)
 and (p_target_type<>'NEARBY' or exists(select 1 from public.profiles p where p.id=f.profile_id and p.latitude is not null and p.longitude is not null
 and 6371*acos(least(1,greatest(-1,cos(radians(biz.latitude))*cos(radians(p.latitude))*cos(radians(p.longitude)-radians(biz.longitude))
 +sin(radians(biz.latitude))*sin(radians(p.latitude)))))<=p_radius_km));
 rate:=1.0; charge:=ceil(audience*rate)::integer;
 select points_balance into balance from public.business_wallets where business_id=p_business_id for update; balance:=coalesce(balance,0);
 if balance<charge then return jsonb_build_object('ok',false,'error','low_balance','audience_size',audience,'required_points',charge,'balance',balance); end if;
 update public.business_wallets set points_balance=points_balance-charge,updated_at=now() where business_id=p_business_id;
 update public.businesses set wallet_points=greatest(wallet_points-charge,0),updated_at=now() where id=p_business_id;
 insert into public.business_broadcasts(business_id,owner_profile_id,title,message,banner_url,cta_label,cta_url,target_type,radius_km,interest_tags,audience_size,points_per_user,points_charged,status)
 values(p_business_id,uid,trim(p_title),trim(p_message),coalesce(p_banner_url,''),coalesce(p_cta_label,''),coalesce(p_cta_url,''),
 p_target_type,coalesce(p_radius_km,5),coalesce(p_interest_tags,'{}'),audience,rate,charge,'PENDING_DISPATCH') returning id into new_id;
 insert into public.business_wallet_ledger(business_id,event_type,points_delta,status,reference_id)
 values(p_business_id,'BROADCAST_DEBIT',-charge,'CONFIRMED',new_id::text);
 return jsonb_build_object('ok',true,'broadcast_id',new_id,'audience_size',audience,'points_per_user',rate,
 'required_points',charge,'balance',balance-charge,'status','PENDING_DISPATCH');
end $$;
revoke all on function public.stage_my_business_broadcast(text,text,text,text,text,text,text,integer,text[]) from public,anon;
grant execute on function public.stage_my_business_broadcast(text,text,text,text,text,text,text,integer,text[]) to authenticated;

-- Add explicit profile-click tracking without changing the owner/session restrictions.
alter table public.business_analytics_events
  drop constraint if exists business_analytics_events_event_type_check;
alter table public.business_analytics_events
  add constraint business_analytics_events_event_type_check
  check (event_type in ('VIEW','PROFILE_CLICK','IMPRESSION','CHAT_INQUIRY','BROADCAST_OPEN','BROADCAST_CLICK','LOCATION_CLICK','CALL_CLICK'));

create or replace function public.record_business_analytics_event(p_business_id text,p_event_type text,p_event_metadata jsonb default '{}'::jsonb)
returns void language plpgsql security definer set search_path=pg_catalog,public as $$
begin
 if p_event_type not in ('VIEW','PROFILE_CLICK','IMPRESSION','CHAT_INQUIRY','BROADCAST_OPEN','BROADCAST_CLICK','LOCATION_CLICK','CALL_CLICK') then raise exception 'invalid_business_event_type' using errcode='22023'; end if;
 insert into public.business_analytics_events(business_id,event_type,event_metadata)
 select p_business_id, p_event_type, coalesce(p_event_metadata,'{}'::jsonb)
 where exists(select 1 from public.businesses where id=p_business_id and is_published);
end $$;
revoke all on function public.record_business_analytics_event(text,text,jsonb) from public,anon;
grant execute on function public.record_business_analytics_event(text,text,jsonb) to authenticated;

-- Add current follower count and period follower growth to the owner-only snapshot.
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
  profile_clicks integer := 0;
  follower_count integer := 0;
  follower_growth integer := 0;
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
    count(*) filter (where event_type='PROFILE_CLICK')::integer,
    count(*) filter (where event_type='CHAT_INQUIRY')::integer,
    count(*) filter (where event_type='BROADCAST_OPEN')::integer,
    count(*) filter (where event_type='BROADCAST_CLICK')::integer,
    count(*) filter (where event_type='LOCATION_CLICK')::integer,
    count(*) filter (where event_type='CALL_CLICK')::integer
  into views, impressions, profile_clicks, inquiries, broadcast_opens, broadcast_clicks, location_clicks, call_clicks
  from public.business_analytics_events
  where business_id=p_business_id and (since_ts is null or created_at >= since_ts);

  select count(*)::integer into follower_count
    from public.business_followers where business_id=p_business_id;

  select count(*)::integer into follower_growth
    from public.business_followers
   where business_id=p_business_id
     and (since_ts is null or followed_at >= since_ts);

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
    'profile_clicks',coalesce(profile_clicks,0),
    'follower_count',coalesce(follower_count,0),'follower_growth',coalesce(follower_growth,0),
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
