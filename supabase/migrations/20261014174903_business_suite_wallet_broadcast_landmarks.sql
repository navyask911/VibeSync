begin;
do $$ begin
  create type public.curated_landmark_category as enum ('PARK','MONUMENT','TOURISM','LOCAL_FAVORITE');
exception when duplicate_object then null;
end $$;

create table if not exists public.businesses (
 id text primary key, owner_profile_id text references public.profiles(id) on delete set null,
 name text not null, tagline text not null default '', category text not null default 'LOCAL_FAVORITE',
 description text not null default '', address text not null default '', city text not null default 'Bengaluru',
 latitude double precision not null default 12.9716, longitude double precision not null default 77.5946,
 rating numeric(3,2) not null default 0, review_count integer not null default 0, banner_url text not null default '',
 logo_emoji text not null default '📍', phone_number text not null default '', website_url text not null default '',
 social_handle text not null default '', is_verified boolean not null default false, follower_count integer not null default 0,
 active_offer_summary text not null default '', verification_tier text not null default 'STANDARD',
 wallet_points integer not null default 0, is_listing_paid boolean not null default false,
 listing_payment_txn_id text not null default '', photo_gallery_json text not null default '',
 created_at_epoch_ms bigint not null default (extract(epoch from now())*1000)::bigint,
 is_published boolean not null default true, is_curated_landmark boolean not null default false,
 landmark_category public.curated_landmark_category not null default 'LOCAL_FAVORITE',
 opening_hours text not null default '', updated_at timestamptz not null default now()
);
alter table public.businesses add column if not exists owner_profile_id text references public.profiles(id) on delete set null;
alter table public.businesses add column if not exists tagline text not null default '';
alter table public.businesses add column if not exists description text not null default '';
alter table public.businesses add column if not exists address text not null default '';
alter table public.businesses add column if not exists city text not null default 'Bengaluru';
alter table public.businesses add column if not exists latitude double precision not null default 12.9716;
alter table public.businesses add column if not exists longitude double precision not null default 77.5946;
alter table public.businesses add column if not exists rating numeric(3,2) not null default 0;
alter table public.businesses add column if not exists review_count integer not null default 0;
alter table public.businesses add column if not exists banner_url text not null default '';
alter table public.businesses add column if not exists logo_emoji text not null default '📍';
alter table public.businesses add column if not exists phone_number text not null default '';
alter table public.businesses add column if not exists website_url text not null default '';
alter table public.businesses add column if not exists social_handle text not null default '';
alter table public.businesses add column if not exists is_verified boolean not null default false;
alter table public.businesses add column if not exists follower_count integer not null default 0;
alter table public.businesses add column if not exists active_offer_summary text not null default '';
alter table public.businesses add column if not exists verification_tier text not null default 'STANDARD';
alter table public.businesses add column if not exists wallet_points integer not null default 0;
alter table public.businesses add column if not exists is_listing_paid boolean not null default false;
alter table public.businesses add column if not exists listing_payment_txn_id text not null default '';
alter table public.businesses add column if not exists photo_gallery_json text not null default '';
alter table public.businesses add column if not exists created_at_epoch_ms bigint not null default (extract(epoch from now())*1000)::bigint;
alter table public.businesses add column if not exists is_published boolean not null default true;
alter table public.businesses add column if not exists is_curated_landmark boolean not null default false;
alter table public.businesses add column if not exists landmark_category public.curated_landmark_category not null default 'LOCAL_FAVORITE';
alter table public.businesses add column if not exists opening_hours text not null default '';
alter table public.businesses add column if not exists updated_at timestamptz not null default now();
create index if not exists idx_businesses_public_directory on public.businesses(is_published,is_curated_landmark desc,city,category);
create index if not exists idx_businesses_owner on public.businesses(owner_profile_id,created_at_epoch_ms desc);

create table if not exists public.business_followers (
 business_id text not null references public.businesses(id) on delete cascade,
 profile_id text not null references public.profiles(id) on delete cascade,
 interest_tags text[] not null default '{}', followed_at timestamptz not null default now(),
 primary key(business_id,profile_id)
);
create index if not exists idx_business_followers_profile on public.business_followers(profile_id);
create table if not exists public.business_wallets (
 business_id text primary key references public.businesses(id) on delete cascade,
 points_balance integer not null default 0 check(points_balance>=0), updated_at timestamptz not null default now()
);
insert into public.business_wallets(business_id,points_balance)
select id,greatest(wallet_points,0) from public.businesses on conflict(business_id) do nothing;
create table if not exists public.business_wallet_ledger (
 id uuid primary key default gen_random_uuid(), business_id text not null references public.businesses(id) on delete cascade,
 event_type text not null check(event_type in ('TOP_UP','BROADCAST_DEBIT','BROADCAST_REFUND','ADJUSTMENT')),
 points_delta integer not null, amount_inr numeric(12,2) not null default 0,
 status text not null default 'CONFIRMED' check(status in ('PENDING','CONFIRMED','FAILED','REVERSED')),
 reference_id text not null default '', created_at timestamptz not null default now()
);
create index if not exists idx_business_wallet_ledger_recent on public.business_wallet_ledger(business_id,created_at desc);
create table if not exists public.business_wallet_payment_orders (
 id uuid primary key default gen_random_uuid(), business_id text not null references public.businesses(id) on delete cascade,
 owner_profile_id text not null references public.profiles(id) on delete cascade, points integer not null check(points>0),
 base_amount_inr numeric(12,2) not null, gst_amount_inr numeric(12,2) not null, total_amount_inr numeric(12,2) not null,
 provider text not null default 'RAZORPAY', provider_order_id text unique, provider_payment_id text unique,
 status text not null default 'PENDING' check(status in ('PENDING','PAID','FAILED','EXPIRED')),
 created_at timestamptz not null default now(), paid_at timestamptz
);
create table if not exists public.business_broadcasts (
 id uuid primary key default gen_random_uuid(), business_id text not null references public.businesses(id) on delete cascade,
 owner_profile_id text not null references public.profiles(id) on delete cascade, title text not null, message text not null,
 banner_url text not null default '', cta_label text not null default '', cta_url text not null default '',
 target_type text not null check(target_type in ('ALL_FOLLOWERS','NEARBY','INTEREST_TAGS')),
 radius_km integer not null default 5 check(radius_km in (5,10)), interest_tags text[] not null default '{}',
 audience_size integer not null default 0, points_per_user numeric(5,2) not null default 1,
 points_charged integer not null default 0,
 status text not null default 'PENDING_DISPATCH' check(status in ('PENDING_DISPATCH','DISPATCHING','DISPATCHED','FAILED')),
 dispatch_error text not null default '', created_at timestamptz not null default now(), dispatched_at timestamptz
);
create index if not exists idx_business_broadcasts_owner on public.business_broadcasts(owner_profile_id,created_at desc);
create table if not exists public.business_analytics_events (
 id uuid primary key default gen_random_uuid(), business_id text not null references public.businesses(id) on delete cascade,
 event_type text not null check(event_type in ('VIEW','IMPRESSION','CHAT_INQUIRY','BROADCAST_OPEN','BROADCAST_CLICK','LOCATION_CLICK','CALL_CLICK')),
 event_metadata jsonb not null default '{}', created_at timestamptz not null default now()
);
create index if not exists idx_business_analytics_recent on public.business_analytics_events(business_id,created_at desc);

alter table public.businesses enable row level security;
alter table public.business_followers enable row level security;
alter table public.business_wallets enable row level security;
alter table public.business_wallet_ledger enable row level security;
alter table public.business_wallet_payment_orders enable row level security;
alter table public.business_broadcasts enable row level security;
alter table public.business_analytics_events enable row level security;
drop policy if exists businesses_public_published on public.businesses;
create policy businesses_public_published on public.businesses for select to anon,authenticated using(is_published=true);
drop policy if exists business_analytics_insert_events on public.business_analytics_events;
create policy business_analytics_insert_events on public.business_analytics_events for insert to authenticated
with check(public.vibesync_jwt_session_active());
revoke all on public.business_wallets,public.business_wallet_ledger,public.business_wallet_payment_orders,
 public.business_broadcasts,public.business_followers from anon,authenticated,public;
grant select on public.businesses to anon,authenticated;
grant select,insert on public.business_analytics_events to authenticated;

create or replace function public.upsert_my_business_listing(p_payload jsonb)
returns jsonb language plpgsql security definer set search_path=pg_catalog,public as $$
declare owner_id text:=public.vibesync_jwt_profile_id(); biz_id text:=coalesce(p_payload->>'id',''); result_row public.businesses%rowtype;
begin
 if not public.vibesync_jwt_session_active() or owner_id is null or biz_id='' then raise exception 'verified_business_session_required' using errcode='42501'; end if;
 if length(trim(coalesce(p_payload->>'name',''))) not between 2 and 120 then raise exception 'invalid_business_name' using errcode='22023'; end if;
 insert into public.businesses(id,owner_profile_id,name,tagline,category,description,address,city,latitude,longitude,
 banner_url,logo_emoji,phone_number,website_url,social_handle,is_verified,follower_count,active_offer_summary,
 verification_tier,wallet_points,is_listing_paid,listing_payment_txn_id,photo_gallery_json,created_at_epoch_ms,
 is_published,is_curated_landmark,landmark_category,updated_at)
 values(biz_id,owner_id,trim(p_payload->>'name'),coalesce(p_payload->>'tagline',''),coalesce(p_payload->>'category','LOCAL_FAVORITE'),
 coalesce(p_payload->>'description',''),coalesce(p_payload->>'address',''),coalesce(p_payload->>'city','Bengaluru'),
 coalesce((p_payload->>'latitude')::double precision,12.9716),coalesce((p_payload->>'longitude')::double precision,77.5946),
 coalesce(p_payload->>'banner_url',''),coalesce(p_payload->>'logo_emoji','🏢'),coalesce(p_payload->>'phone_number',''),
 coalesce(p_payload->>'website_url',''),coalesce(p_payload->>'social_handle',''),false,0,
 coalesce(p_payload->>'active_offer_summary',''),'STANDARD',0,coalesce((p_payload->>'is_listing_paid')::boolean,false),
 coalesce(p_payload->>'listing_payment_txn_id',''),coalesce(p_payload->>'photo_gallery_json',''),
 coalesce((p_payload->>'created_at_epoch_ms')::bigint,(extract(epoch from now())*1000)::bigint),true,false,'LOCAL_FAVORITE',now())
 on conflict(id) do update set name=excluded.name,tagline=excluded.tagline,category=excluded.category,
 description=excluded.description,address=excluded.address,city=excluded.city,latitude=excluded.latitude,longitude=excluded.longitude,
 banner_url=excluded.banner_url,logo_emoji=excluded.logo_emoji,phone_number=excluded.phone_number,website_url=excluded.website_url,
 social_handle=excluded.social_handle,active_offer_summary=excluded.active_offer_summary,is_listing_paid=excluded.is_listing_paid,
 listing_payment_txn_id=excluded.listing_payment_txn_id,photo_gallery_json=excluded.photo_gallery_json,is_published=true,updated_at=now()
 where public.businesses.owner_profile_id=owner_id and not public.businesses.is_curated_landmark returning * into result_row;
 if not found then raise exception 'business_id_not_owned_or_reserved' using errcode='42501'; end if;
 insert into public.business_wallets(business_id,points_balance) values(biz_id,0) on conflict(business_id) do nothing;
 return jsonb_build_object('ok',true,'business_id',result_row.id,'is_published',result_row.is_published);
end $$;
revoke all on function public.upsert_my_business_listing(jsonb) from public,anon;
grant execute on function public.upsert_my_business_listing(jsonb) to authenticated;

create or replace function public.set_my_business_follow(p_business_id text,p_following boolean)
returns jsonb language plpgsql security definer set search_path=pg_catalog,public as $$
declare uid text:=public.vibesync_jwt_profile_id(); followers integer;
begin
 if not public.vibesync_jwt_session_active() or uid is null then raise exception 'verified_session_required' using errcode='42501'; end if;
 if not exists(select 1 from public.businesses where id=p_business_id and is_published) then raise exception 'business_not_found' using errcode='P0002'; end if;
 if p_following then insert into public.business_followers(business_id,profile_id) values(p_business_id,uid) on conflict do nothing;
 else delete from public.business_followers where business_id=p_business_id and profile_id=uid; end if;
 select count(*)::integer into followers from public.business_followers where business_id=p_business_id;
 update public.businesses set follower_count=followers,updated_at=now() where id=p_business_id;
 return jsonb_build_object('ok',true,'is_following',p_following,'follower_count',followers);
end $$;
revoke all on function public.set_my_business_follow(text,boolean) from public,anon;
grant execute on function public.set_my_business_follow(text,boolean) to authenticated;

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
 rate:=case when biz.verification_tier='GOLD' then 0.5 else 1.0 end; charge:=ceil(audience*rate)::integer;
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

create or replace function public.record_business_analytics_event(p_business_id text,p_event_type text,p_event_metadata jsonb default '{}'::jsonb)
returns void language plpgsql security definer set search_path=pg_catalog,public as $$
begin
 if p_event_type not in ('VIEW','IMPRESSION','CHAT_INQUIRY','BROADCAST_OPEN','BROADCAST_CLICK','LOCATION_CLICK','CALL_CLICK') then raise exception 'invalid_business_event_type' using errcode='22023'; end if;
 insert into public.business_analytics_events(business_id,event_type,event_metadata)
 select p_business_id, p_event_type, coalesce(p_event_metadata,'{}'::jsonb)
 where exists(select 1 from public.businesses where id=p_business_id and is_published);
end $$;
revoke all on function public.record_business_analytics_event(text,text,jsonb) from public,anon;
grant execute on function public.record_business_analytics_event(text,text,jsonb) to authenticated;

-- The live project may have the legacy super_admins(user_id, phone) shape rather
-- than super_admins(profile_id). Detect the deployed column and fail closed if absent.
create or replace function public.is_vibesync_super_admin()
returns boolean
language plpgsql
stable
security definer
set search_path = pg_catalog, public
as $function$
declare
  uid text := public.vibesync_jwt_profile_id();
  authorized boolean := false;
  admins_oid oid := to_regclass('public.super_admins');
begin
  if uid is null or not public.vibesync_jwt_session_active() or admins_oid is null then
    return false;
  end if;

  if exists (
    select 1 from pg_attribute
    where attrelid = admins_oid and attname = 'profile_id' and not attisdropped
  ) then
    execute 'select exists (select 1 from public.super_admins where profile_id = $1)'
      into authorized using uid;
  elsif exists (
    select 1 from pg_attribute
    where attrelid = admins_oid and attname = 'user_id' and not attisdropped
  ) then
    execute 'select exists (select 1 from public.super_admins where user_id = $1)'
      into authorized using uid;
  end if;

  return coalesce(authorized, false);
end;
$function$;
revoke all on function public.is_vibesync_super_admin() from public, anon;
grant execute on function public.is_vibesync_super_admin() to authenticated;

create or replace function public.admin_update_curated_landmark(
 p_business_id text,p_name text,p_description text,p_photo_url text,p_opening_hours text,
 p_latitude double precision,p_longitude double precision,p_landmark_category public.curated_landmark_category
) returns void language plpgsql security definer set search_path=pg_catalog,public as $$
begin
 if not public.vibesync_jwt_session_active() or not public.is_vibesync_super_admin() then raise exception 'super_admin_required' using errcode='42501'; end if;
 if not exists(select 1 from public.businesses where id=p_business_id and is_curated_landmark) then raise exception 'curated_landmark_not_found' using errcode='P0002'; end if;
 update public.businesses set name=trim(p_name),description=coalesce(p_description,''),banner_url=coalesce(p_photo_url,''),
 opening_hours=coalesce(p_opening_hours,''),latitude=p_latitude,longitude=p_longitude,landmark_category=p_landmark_category,
 category=p_landmark_category::text,updated_at=now() where id=p_business_id;
end $$;
revoke all on function public.admin_update_curated_landmark(text,text,text,text,text,double precision,double precision,public.curated_landmark_category) from public,anon;
grant execute on function public.admin_update_curated_landmark(text,text,text,text,text,double precision,double precision,public.curated_landmark_category) to authenticated;

insert into public.businesses(id,owner_profile_id,name,category,description,address,city,latitude,longitude,rating,review_count,banner_url,logo_emoji,is_verified,follower_count,active_offer_summary,verification_tier,wallet_points,is_listing_paid,is_published,is_curated_landmark,landmark_category,created_at_epoch_ms)
values
 ('landmark_cubbon_park',null,'Cubbon Park','PARK','A leafy public park in central Bengaluru with walking paths and open lawns.','Kasturba Road, Bengaluru','Bengaluru',12.9763,77.5929,0,0,'https://images.unsplash.com/photo-1441974231531-c6227db76b6e?auto=format&fit=crop&w=1000&q=80','🌳',true,0,'Free public landmark','STANDARD',0,false,true,true,'PARK',(extract(epoch from now())*1000)::bigint),
 ('landmark_lalbagh',null,'Lalbagh Botanical Garden','PARK','Historic botanical garden with a glass house, gardens and a lake.','Lalbagh Road, Bengaluru','Bengaluru',12.9507,77.5848,0,0,'https://images.unsplash.com/photo-1472396961693-142e6e269027?auto=format&fit=crop&w=1000&q=80','🌿',true,0,'Free public landmark','STANDARD',0,false,true,true,'PARK',(extract(epoch from now())*1000)::bigint),
 ('landmark_vidhana_soudha',null,'Vidhana Soudha','MONUMENT','Bengaluru landmark and seat of the Karnataka state legislature; exterior viewing only.','Dr Ambedkar Veedhi, Bengaluru','Bengaluru',12.9799,77.5907,0,0,'https://images.unsplash.com/photo-1518005020951-eccb494ad742?auto=format&fit=crop&w=1000&q=80','🏛️',true,0,'Free public landmark','STANDARD',0,false,true,true,'MONUMENT',(extract(epoch from now())*1000)::bigint),
 ('landmark_commercial_street',null,'Commercial Street','LOCAL_FAVORITE','A popular central Bengaluru shopping street with independent shops and eateries.','Commercial Street, Bengaluru','Bengaluru',12.9831,77.6082,0,0,'https://images.unsplash.com/photo-1441986300917-64674bd600d8?auto=format&fit=crop&w=1000&q=80','🛍️',true,0,'Free public landmark','STANDARD',0,false,true,true,'LOCAL_FAVORITE',(extract(epoch from now())*1000)::bigint)
on conflict(id) do update set name=excluded.name,category=excluded.category,description=excluded.description,address=excluded.address,
 city=excluded.city,latitude=excluded.latitude,longitude=excluded.longitude,banner_url=excluded.banner_url,
 logo_emoji=excluded.logo_emoji,active_offer_summary=excluded.active_offer_summary,is_published=true,is_curated_landmark=true,
 landmark_category=excluded.landmark_category,is_listing_paid=false;
insert into public.business_wallets(business_id,points_balance)
select id,0 from public.businesses where is_curated_landmark on conflict(business_id) do nothing;
commit;
