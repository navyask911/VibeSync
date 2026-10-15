import { createClient } from "https://esm.sh/@supabase/supabase-js@2.57.4";
const supabaseUrl=Deno.env.get("SUPABASE_URL")??"";
const anonKey=Deno.env.get("SUPABASE_ANON_KEY")??"";
const serviceRoleKey=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")??"";
const allowedOrigin="https://navyask911.github.io";
const rolesByOperation:Record<string,string[]> = {
 vibesync_get_admin_telemetry:["super_admin","moderator","finance_manager","compliance_officer"],
 vibesync_admin_verify_profile:["super_admin","moderator"],
 vibesync_admin_moderate_profile:["super_admin","moderator"],
 vibesync_admin_search_profiles:["super_admin","moderator"],
 vibesync_admin_get_payment_summary:["super_admin","finance_manager"],
 vibesync_admin_search_lea_metadata:["super_admin","compliance_officer"],
 vibesync_admin_seal_compliance_export:["super_admin","compliance_officer"],
 vibesync_admin_export_table_page:["super_admin","finance_manager","compliance_officer"]
};
const cors={"Access-Control-Allow-Origin":allowedOrigin,"Access-Control-Allow-Headers":"authorization, apikey, content-type, x-client-info","Access-Control-Allow-Methods":"POST, OPTIONS","Vary":"Origin"};
function reply(status:number,body:Record<string,unknown>):Response{return new Response(JSON.stringify(body),{status,headers:{...cors,"Content-Type":"application/json; charset=utf-8","Cache-Control":"no-store"}});}
function isRecord(v:unknown):v is Record<string,unknown>{return v!==null&&typeof v==="object"&&!Array.isArray(v);}
function textArg(args:Record<string,unknown>,key:string,max:number):string{const v=args[key];if(typeof v!=="string"||v.length>max)throw new Error(`Invalid ${key}.`);return v;}
Deno.serve(async(req:Request)=>{
 if(req.method==="OPTIONS")return new Response("ok",{headers:cors});
 if(req.method!=="POST")return reply(405,{error:"Method not allowed."});
 if(!supabaseUrl||!anonKey||!serviceRoleKey)return reply(503,{error:"Admin API configuration unavailable."});
 try{
  const authorization=req.headers.get("Authorization")??"";
  if(!/^Bearer\s+\S+$/i.test(authorization))return reply(401,{error:"A valid staff session is required."});
  const token=authorization.replace(/^Bearer\s+/i,"").trim();
  const authClient=createClient(supabaseUrl,anonKey,{auth:{persistSession:false,autoRefreshToken:false},global:{headers:{Authorization:`Bearer ${token}`}}});
  const {data:authData,error:authError}=await authClient.auth.getUser(token);
  if(authError||!authData.user)return reply(401,{error:"Staff session is invalid or expired."});
  const service=createClient(supabaseUrl,serviceRoleKey,{auth:{persistSession:false,autoRefreshToken:false}});
  const {data:staff,error:staffError}=await service.from("admin_users").select("user_id,role,is_active").eq("user_id",authData.user.id).maybeSingle();
  if(staffError||!staff||staff.is_active!==true)return reply(403,{error:"Active staff membership is required."});
  const body:unknown=await req.json();
  if(!isRecord(body)||typeof body.operation!=="string")return reply(400,{error:"Invalid admin operation request."});
  const operation=body.operation,allowed=rolesByOperation[operation];
  if(!allowed||!allowed.includes(String(staff.role)))return reply(403,{error:"Your assigned staff role cannot perform this action."});
  const args=isRecord(body.args)?body.args:{};
  let params:Record<string,unknown>={p_actor_id:authData.user.id};
  switch(operation){
   case "vibesync_get_admin_telemetry":case "vibesync_admin_get_payment_summary":break;
   case "vibesync_admin_verify_profile":{
    const profileId=textArg(args,"p_profile_id",160).trim();if(!profileId||typeof args.p_verified!=="boolean")throw new Error("Invalid verification request.");
    params={...params,p_profile_id:profileId,p_verified:args.p_verified};break;
   }
   case "vibesync_admin_moderate_profile":{
    const profileId=textArg(args,"p_profile_id",160).trim(),action=textArg(args,"p_action",12),reason=typeof args.p_reason==="string"?args.p_reason.slice(0,500):"Admin review";
    if(!profileId||!["ban","spam"].includes(action)||typeof args.p_enabled!=="boolean")throw new Error("Invalid moderation request.");
    params={...params,p_profile_id:profileId,p_action:action,p_enabled:args.p_enabled,p_reason:reason};break;
   }
   case "vibesync_admin_search_profiles":{
    const query=textArg(args,"p_query",80).trim();if(query.length<2)throw new Error("Enter at least two characters.");params={...params,p_query:query};break;
   }
   case "vibesync_admin_search_lea_metadata":{
    const query=textArg(args,"p_query",120).trim(),caseReference=textArg(args,"p_case_reference",120).trim(),basis=textArg(args,"p_legal_basis",24);
    if(query.length<2||caseReference.length<3||!["SEC_94_BNSS","MLAT"].includes(basis))throw new Error("Invalid compliance search request.");
    params={...params,p_query:query,p_case_reference:caseReference,p_legal_basis:basis};break;
   }
   case "vibesync_admin_seal_compliance_export":{
    const exportId=textArg(args,"p_export_id",60),digest=textArg(args,"p_sha256",64),count=args.p_record_count;
    if(!/^[0-9a-f]{64}$/.test(digest)||!Number.isInteger(count)||Number(count)<0)throw new Error("Invalid compliance integrity seal.");
    params={...params,p_export_id:exportId,p_sha256:digest,p_record_count:Number(count)};break;
   }
   case "vibesync_admin_export_table_page":{
    const tableName=textArg(args,"p_table",80),offset=Number(args.p_offset??0),limit=Number(args.p_limit??1000);
    if(!Number.isInteger(offset)||offset<0||!Number.isInteger(limit)||limit<1||limit>1000)throw new Error("Invalid export page.");
    params={...params,p_table:tableName,p_offset:offset,p_limit:limit};break;
   }
   default:return reply(400,{error:"Unsupported admin operation."});
  }
  const {data,error}=await service.rpc(operation,params);
  if(error){console.error("admin-portal-api RPC failure",operation,error.code);return reply(error.code==="42501"?403:400,{error:"The authorized admin operation could not be completed."});}
  return reply(200,{data});
 }catch(error){return reply(400,{error:error instanceof Error?error.message:"Admin request could not be validated."});}
});
