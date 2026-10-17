begin;
alter table public.business_wallet_ledger add column if not exists expires_at timestamptz;
create table if not exists public.business_payment_orders (
 id uuid primary key default gen_random_uuid(),
 business_id text not null references public.businesses(id) on delete cascade,
 owner_profile_id text not null references public.profiles(id) on delete cascade,
 purchase_type text not null check(purchase_type in ('WALLET_RECHARGE','VERIFICATION_BADGE')),
 points integer not null default 0 check(points>=0),
 verification_tier text not null default '',
 base_amount_inr numeric(12,2) not null check(base_amount_inr>0),
 gst_amount_inr numeric(12,2) not null check(gst_amount_inr>=0),
 total_amount_inr numeric(12,2) not null check(total_amount_inr>0),
 provider text not null default 'RAZORPAY',provider_order_id text unique,provider_payment_id text unique,
 status text not null default 'PENDING' check(status in ('PENDING','PAID','FAILED','EXPIRED')),
 created_at timestamptz not null default now(),paid_at timestamptz
);
create index if not exists idx_business_payment_orders_owner on public.business_payment_orders(owner_profile_id,created_at desc);
alter table public.business_payment_orders enable row level security;
revoke all on public.business_payment_orders from public,anon,authenticated;

create or replace function public.complete_business_payment_order(p_order_id uuid,p_payment_id text)
returns jsonb language plpgsql security definer set search_path=pg_catalog,public as $$
declare o public.business_payment_orders%rowtype; bal integer; bonus integer:=0;
begin
 if p_payment_id is null or length(trim(p_payment_id))<5 then raise exception 'invalid_payment_id' using errcode='22023'; end if;
 select * into o from public.business_payment_orders where id=p_order_id for update;
 if not found then raise exception 'payment_order_not_found' using errcode='P0002'; end if;
 if o.status='PAID' then
   if o.provider_payment_id<>p_payment_id then raise exception 'order_already_paid' using errcode='23505'; end if;
   select points_balance into bal from public.business_wallets where business_id=o.business_id;
   return jsonb_build_object('ok',true,'already_paid',true,'purchase_type',o.purchase_type,'balance',coalesce(bal,0),'transaction_id',p_payment_id);
 end if;
 if o.status<>'PENDING' or (o.provider_payment_id is not null and o.provider_payment_id<>p_payment_id) then raise exception 'payment_order_not_pending' using errcode='23514'; end if;
 update public.business_payment_orders set status='PAID',provider_payment_id=p_payment_id,paid_at=now() where id=p_order_id;
 insert into public.business_wallets(business_id,points_balance) values(o.business_id,0) on conflict(business_id) do nothing;
 if o.purchase_type='WALLET_RECHARGE' then
   update public.business_wallets set points_balance=points_balance+o.points,updated_at=now() where business_id=o.business_id returning points_balance into bal;
   update public.businesses set wallet_points=bal,updated_at=now() where id=o.business_id;
   insert into public.business_wallet_ledger(business_id,event_type,points_delta,amount_inr,status,reference_id,expires_at)
   values(o.business_id,'TOP_UP',o.points,o.total_amount_inr,'CONFIRMED',p_payment_id,now()+interval '365 days');
 else
   if o.verification_tier not in ('GOLD','SILVER','BLUE_TICK') then raise exception 'invalid_verification_tier' using errcode='22023'; end if;
   update public.businesses set verification_tier=o.verification_tier,is_verified=true,is_listing_paid=true,listing_payment_txn_id=p_payment_id,updated_at=now()
    where id=o.business_id and owner_profile_id=o.owner_profile_id;
   if not found then raise exception 'business_owner_mismatch' using errcode='42501'; end if;
   bonus:=case o.verification_tier when 'GOLD' then 1500 when 'SILVER' then 800 else 400 end;
   update public.business_wallets set points_balance=points_balance+bonus,updated_at=now() where business_id=o.business_id returning points_balance into bal;
   update public.businesses set wallet_points=bal where id=o.business_id;
   insert into public.business_wallet_ledger(business_id,event_type,points_delta,amount_inr,status,reference_id,expires_at)
    values(o.business_id,'TOP_UP',bonus,0,'CONFIRMED',p_payment_id||':tier-bonus',now()+interval '365 days');
 end if;
 return jsonb_build_object('ok',true,'already_paid',false,'purchase_type',o.purchase_type,'points',o.points,'bonus_points',bonus,
  'balance',coalesce(bal,0),'transaction_id',p_payment_id,'verification_tier',o.verification_tier);
end $$;
revoke all on function public.complete_business_payment_order(uuid,text) from public,anon,authenticated;
grant execute on function public.complete_business_payment_order(uuid,text) to service_role;

create or replace function public.get_my_business_wallet_snapshot(p_business_id text)
returns jsonb language plpgsql security definer set search_path=pg_catalog,public as $$
declare uid text:=public.vibesync_jwt_profile_id(); bal integer; expiry integer; entries jsonb;
begin
 if not public.vibesync_jwt_session_active() or uid is null then raise exception 'verified_session_required' using errcode='42501'; end if;
 if not exists(select 1 from public.businesses where id=p_business_id and owner_profile_id=uid) then raise exception 'business_owner_required' using errcode='42501'; end if;
 select points_balance into bal from public.business_wallets where business_id=p_business_id;
 select coalesce(sum(points_delta),0)::integer into expiry from public.business_wallet_ledger where business_id=p_business_id and points_delta>0 and expires_at>now() and expires_at<=now()+interval '30 days';
 select coalesce(jsonb_agg(jsonb_build_object('event_type',event_type,'points_delta',points_delta,'amount_inr',amount_inr,'status',status,'reference_id',reference_id,'created_at',created_at,'expires_at',expires_at) order by created_at desc),'[]'::jsonb)
 into entries from (select * from public.business_wallet_ledger where business_id=p_business_id order by created_at desc limit 10) ledger_rows;
 return jsonb_build_object('ok',true,'balance',coalesce(bal,0),'points_expiring',coalesce(expiry,0),'recent_entries',entries);
end $$;
revoke all on function public.get_my_business_wallet_snapshot(text) from public,anon;
grant execute on function public.get_my_business_wallet_snapshot(text) to authenticated;
commit;
