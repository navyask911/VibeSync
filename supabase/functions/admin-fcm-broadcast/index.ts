import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

type ServiceAccount = { client_email: string; private_key: string; project_id: string; token_uri?: string };
const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const anonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? Deno.env.get("SUPABASE_PUBLISHABLE_KEY") ?? "";
const serviceKey = (() => {
  try {
    const keys = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "");
    if (typeof keys?.default === "string" && keys.default) return keys.default;
  } catch { /* Allow migration to the new secret-key format. */ }
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
})();
const serviceAccountJson = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON") ?? "";
const allowedOrigins = new Set(["https://admin.vibesync.social", "https://navyask911.github.io"]);

function cors(origin: string | null): Record<string, string> {
  return {
    "Access-Control-Allow-Origin": origin && allowedOrigins.has(origin) ? origin : "https://admin.vibesync.social",
    "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin"
  };
}
function json(data: unknown, status = 200, origin: string | null = null) {
  return new Response(JSON.stringify(data), { status, headers: {
    ...cors(origin), "content-type": "application/json; charset=utf-8", "cache-control": "no-store"
  }});
}
function serviceAccount(): ServiceAccount | null {
  try {
    const parsed = JSON.parse(serviceAccountJson) as ServiceAccount;
    return parsed.client_email && parsed.private_key && parsed.project_id ? parsed : null;
  } catch { return null; }
}
function base64Url(value: string | Uint8Array): string {
  const bytes = typeof value === "string" ? new TextEncoder().encode(value) : value;
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
function pemToDer(pem: string): ArrayBuffer {
  const clean = pem.replace(/-----BEGIN PRIVATE KEY-----/g, "").replace(/-----END PRIVATE KEY-----/g, "").replace(/\s+/g, "");
  const raw = atob(clean), bytes = new Uint8Array(raw.length);
  for (let i=0;i<raw.length;i++) bytes[i]=raw.charCodeAt(i);
  return bytes.buffer;
}
async function googleToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now()/1000);
  const header = base64Url(JSON.stringify({alg:"RS256",typ:"JWT"}));
  const claim = base64Url(JSON.stringify({iss:sa.client_email,scope:"https://www.googleapis.com/auth/firebase.messaging",
    aud:sa.token_uri??"https://oauth2.googleapis.com/token",iat:now,exp:now+3600}));
  const unsigned=header+"."+claim;
  const key=await crypto.subtle.importKey("pkcs8",pemToDer(sa.private_key),
    {name:"RSASSA-PKCS1-v1_5",hash:"SHA-256"},false,["sign"]);
  const signature=await crypto.subtle.sign("RSASSA-PKCS1-v1_5",key,new TextEncoder().encode(unsigned));
  const response=await fetch(sa.token_uri??"https://oauth2.googleapis.com/token",{
    method:"POST",headers:{"content-type":"application/x-www-form-urlencoded"},
    body:new URLSearchParams({grant_type:"urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion:unsigned+"."+base64Url(new Uint8Array(signature))})
  });
  if(!response.ok)throw new Error("Google OAuth token exchange failed: HTTP "+response.status);
  const access=String((await response.json()).access_token??"");
  if(!access)throw new Error("Google access token was missing");
  return access;
}
async function sendPush(sa: ServiceAccount, token: string, access: string, days: number): Promise<void> {
  const response=await fetch("https://fcm.googleapis.com/v1/projects/"+encodeURIComponent(sa.project_id)+"/messages:send",{
    method:"POST",headers:{authorization:"Bearer "+access,"content-type":"application/json"},
    body:JSON.stringify({message:{
      token,
      notification:{title:"VibeSync is here when you are",
        body:days===3?"A little catch-up can go a long way. See what is new on VibeSync.":"Your VibeSync community is still here. Come back when you are ready."},
      data:{type:"reengagement",inactive_days:String(days),screen:"home"},
      android:{priority:"NORMAL",ttl:"86400s"},
      apns:{headers:{"apns-priority":"5"},payload:{aps:{sound:"default"}}}
    }})
  });
  if(!response.ok)throw new Error("FCM rejected a notification with HTTP "+response.status);
}

Deno.serve(async request => {
  const origin=request.headers.get("origin");
  if(origin && !allowedOrigins.has(origin))return json({error:"origin_not_allowed"},403,origin);
  if(request.method==="OPTIONS")return new Response("ok",{headers:cors(origin)});
  if(request.method!=="POST")return json({error:"method_not_allowed"},405,origin);
  if(!supabaseUrl||!serviceKey||!anonKey)return json({error:"server_configuration_missing"},503,origin);
  const auth=request.headers.get("authorization")??"";
  if(!auth.startsWith("Bearer "))return json({error:"authorization_required"},401,origin);

  const caller=createClient(supabaseUrl,anonKey,{global:{headers:{Authorization:auth}},
    auth:{persistSession:false,autoRefreshToken:false}});
  const {data:userResult,error:userError}=await caller.auth.getUser();
  if(userError||!userResult.user)return json({error:"invalid_staff_session"},401,origin);
  const admin=createClient(supabaseUrl,serviceKey,{auth:{persistSession:false,autoRefreshToken:false}});
  const {data:staff,error:staffError}=await admin.from("admin_users").select("role,is_active")
    .eq("user_id",userResult.user.id).maybeSingle();
  if(staffError||!staff||staff.is_active!==true||staff.role!=="super_admin")
    return json({error:"super_admin_required"},403,origin);

  let days:number;
  try { days=Number((await request.json()).inactiveDays); }
  catch { return json({error:"invalid_json"},400,origin); }
  if(days!==3&&days!==7)return json({error:"inactive_days_must_be_3_or_7"},400,origin);
  const sa=serviceAccount();
  if(!sa)return json({error:"FIREBASE_SERVICE_ACCOUNT_JSON is missing or invalid; no messages sent."},503,origin);

  const {data:recent,error:rateError}=await admin.from("admin_broadcast_jobs").select("id,created_at,status")
    .eq("initiated_by",userResult.user.id).gte("created_at",new Date(Date.now()-15*60*1000).toISOString())
    .neq("status","FAILED").limit(1);
  if(rateError)return json({error:"broadcast_rate_check_failed"},500,origin);
  if(recent?.length)return json({error:"broadcast_rate_limited_try_again_later"},429,origin);

  const {data:targets,error:targetError}=await admin.from("profiles").select("id")
    .eq("is_deleted",false).not("last_active_at","is",null)
    .lt("last_active_at",new Date(Date.now()-days*86400000).toISOString()).order("last_active_at",{ascending:true}).limit(501);
  if(targetError)return json({error:"inactive_audience_query_failed"},500,origin);
  const profiles=targets??[];
  if(profiles.length>500)return json({error:"audience_exceeds_500_profiles_narrow_the_batch_before_sending",eligible_profiles:profiles.length},413,origin);

  const {data:job,error:jobError}=await admin.from("admin_broadcast_jobs")
    .insert({initiated_by:userResult.user.id,inactive_days:days,status:"RUNNING",eligible_profiles:profiles.length})
    .select("id").single();
  if(jobError||!job)return json({error:"broadcast_job_record_failed"},500,origin);

  try {
    const ids=profiles.map(p=>String(p.id));
    const tokens:string[]=[];
    for(let i=0;i<ids.length;i+=100){
      const {data,error}=await admin.from("device_tokens").select("fcm_token").in("profile_id",ids.slice(i,i+100));
      if(error)throw new Error("Device token lookup failed");
      for(const row of data??[])if(typeof row.fcm_token==="string"&&row.fcm_token.length>=20)tokens.push(row.fcm_token);
    }
    const access=await googleToken(sa);
    let cursor=0,sent=0,failed=0;
    const workers=Array.from({length:Math.min(10,tokens.length)},async()=>{
      while(cursor<tokens.length){
        const index=cursor++;const token=tokens[index];
        try{await sendPush(sa,token,access,days);sent++;}catch{failed++;}
      }
    });
    await Promise.all(workers);
    await admin.from("admin_broadcast_jobs").update({status:"COMPLETED",tokens_attempted:tokens.length,
      sent,failed,completed_at:new Date().toISOString()}).eq("id",job.id);
    await admin.from("admin_audit_logs").insert({actor_profile_id:userResult.user.id,actor_role:staff.role,
      action:"ADMIN_FCM_REENGAGEMENT_BROADCAST",target_type:"admin_broadcast_jobs",target_id:job.id,
      outcome:failed?"partial":"success",metadata:{inactive_days:days,eligible_profiles:profiles.length,
        tokens_attempted:tokens.length,sent,failed}});
    return json({ok:true,job_id:job.id,eligible_profiles:profiles.length,tokens_attempted:tokens.length,sent,failed},200,origin);
  }catch(error){
    await admin.from("admin_broadcast_jobs").update({status:"FAILED",
      error_summary:"Broadcast failed; inspect server-side logs.",completed_at:new Date().toISOString()}).eq("id",job.id);
    return json({error:error instanceof Error?error.message:"broadcast_failed",job_id:job.id},500,origin);
  }
});
