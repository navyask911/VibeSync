import { createClient } from "https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2.117.3/+esm";

const cfg = window.VIBESYNC_CONFIG || {};
const $ = (id) => document.getElementById(id);
const permissions = {
  super_admin: ["telemetry", "moderation", "payments", "compliance", "reengagement", "preservation"],
  moderator: ["telemetry", "moderation"],
  finance_manager: ["telemetry", "payments", "preservation"],
  compliance_officer: ["telemetry", "compliance", "preservation"]
};
const exportTables = {
  super_admin: ["profiles", "businesses", "business_payment_orders", "business_wallet_ledger", "admin_audit_logs", "admin_users"],
  finance_manager: ["business_payment_orders", "business_wallet_ledger"],
  compliance_officer: ["profiles", "admin_audit_logs"]
};
const titleMap = {
  telemetry: ["Live telemetry", "LIVE TELEMETRY"], moderation: ["User moderation", "USER MODERATION"],
  payments: ["Payment & ledger", "PAYMENT & LEDGER"], compliance: ["Compliance & LEA", "COMPLIANCE & LEA"],
  reengagement: ["Re-engagement", "RE-ENGAGEMENT"], preservation: ["Data preservation", "DATA PRESERVATION"]
};
let db = null, user = null, staff = null, authBusy = false, authorizedId = null, poller = null, channel = null, toastTimer = 0;

function configured() {
  return typeof cfg.supabaseUrl === "string" && cfg.supabaseUrl.startsWith("https://") &&
    typeof cfg.supabaseAnonKey === "string" && cfg.supabaseAnonKey.trim().length > 20 &&
    !cfg.supabaseAnonKey.includes("REPLACE_WITH");
}
function text(el, value, error = false) {
  el.textContent = value || "";
  el.classList.toggle("is-error", Boolean(error));
}
function toast(message, error = false) {
  $("toast").textContent = message;
  $("toast").classList.toggle("is-error", error);
  $("toast").classList.add("is-visible");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => $("toast").classList.remove("is-visible"), 4000);
}
function fmt(value) {
  if (value === null || value === undefined || value === "") return "—";
  if (typeof value === "number") return new Intl.NumberFormat("en-IN", { maximumFractionDigits: 2 }).format(value);
  return String(value);
}
function date(value) {
  if (!value) return "—";
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? String(value) : d.toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });
}
function el(tag, cls, value) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (value !== undefined) node.textContent = String(value);
  return node;
}
function empty(target, heading, detail = "") {
  const node = el("div", "empty-state");
  node.append(el("strong", "", heading), el("p", "", detail));
  target.replaceChildren(node);
}
function table(headers, rows) {
  if (!rows.length) {
    const node = el("div", "empty-state");
    node.append(el("strong", "", "No records found"), el("p", "", "Try another search or refresh."));
    return node;
  }
  const root = el("div", "results-wrap"), t = el("table", "data-table"), thead = el("thead"), hr = el("tr"), body = el("tbody");
  headers.forEach(h => hr.append(el("th", "", h.label)));
  thead.append(hr);
  rows.forEach(row => {
    const tr = el("tr");
    headers.forEach(h => {
      const td = el("td");
      const val = h.value(row);
      if (val instanceof HTMLElement) td.append(val); else td.textContent = fmt(val);
      tr.append(td);
    });
    body.append(tr);
  });
  t.append(thead, body); root.append(t); return root;
}
function metricCards(target, items) {
  target.replaceChildren();
  items.forEach(item => {
    const card = el("article", "metric-card");
    card.append(el("span", "metric-label", item.label), el("strong", "metric-value", fmt(item.value)), el("span", "metric-detail", item.detail || "Server-reported"));
    target.append(card);
  });
}
function requireRole(roles, message = "Your assigned role cannot perform this action.") {
  if (!staff || !roles.includes(staff.role)) throw new Error(message);
}
function stopPolling() {
  if (poller) clearInterval(poller);
  poller = null;
  if (channel && db) db.removeChannel(channel).catch(() => {});
  channel = null;
}
function signOutView() {
  stopPolling(); user = null; staff = null; authorizedId = null;
  $("auth-view").hidden = false; $("admin-app").hidden = true;
}
function setGlobal(message, error = false) {
  $("global-notice").textContent = message || "";
  $("global-notice").hidden = !message;
  $("global-notice").classList.toggle("is-error", error);
}
function updateRoleUI() {
  const allowed = permissions[staff.role] || [];
  document.querySelectorAll(".nav-item").forEach(b => {
    b.hidden = !allowed.includes(b.dataset.section);
    b.disabled = b.hidden;
  });
  const tables = exportTables[staff.role] || [];
  $("export-scope").textContent = tables.length ? "Approved tables: " + tables.join(" · ") : "Your role has no export access.";
  $("export-snapshot").disabled = !tables.length;
  $("lea-form").querySelector('button[type="submit"]').disabled = !allowed.includes("compliance");
  document.querySelectorAll("[data-broadcast-days]").forEach(b => b.disabled = staff.role !== "super_admin");
}
async function authorize(session) {
  if (authBusy) return;
  if (!session?.user) { signOutView(); return; }
  if (authorizedId === session.user.id && staff) return;
  authBusy = true; user = session.user; $("admin-app").hidden = true;
  try {
    // Only this self-membership row is requested before showing the dashboard or loading its state.
    const { data, error } = await db.from("admin_users")
      .select("user_id,role,is_active,display_name,email")
      .eq("user_id", session.user.id).maybeSingle();
    if (error) throw new Error("Staff roster validation failed. Check the admin_users migration and RLS policy.");
    if (!data || data.is_active !== true || !permissions[data.role]) {
      await db.auth.signOut(); signOutView();
      text($("auth-message"), "Access denied. This account is not an active, provisioned VibeSync staff member.", true);
      return;
    }
    staff = data; authorizedId = session.user.id;
    $("auth-view").hidden = true; $("admin-app").hidden = false;
    $("staff-display").textContent = data.display_name || data.email || session.user.email || "Authorized staff";
    $("role-badge").textContent = data.role.replaceAll("_", " ");
    text($("auth-message"), ""); setGlobal(""); updateRoleUI();
    await refreshTelemetry(); startPolling(); showSection("telemetry");
  } catch (error) {
    await db.auth.signOut().catch(() => {}); signOutView();
    text($("auth-message"), error.message || "Staff authorization could not be verified.", true);
  } finally { authBusy = false; }
}
function showSection(section) {
  if (!staff || !(permissions[staff.role] || []).includes(section)) {
    toast("Your role does not permit this section.", true); return;
  }
  document.querySelectorAll(".page-section").forEach(s => s.classList.toggle("is-visible", s.id === "section-" + section));
  document.querySelectorAll(".nav-item").forEach(b => b.classList.toggle("is-active", b.dataset.section === section));
  $("page-title").textContent = titleMap[section][0]; $("section-breadcrumb").textContent = titleMap[section][1];
  if (section === "telemetry") refreshTelemetry();
  if (section === "payments") refreshPayments().catch(e => toast(e.message, true));
}
function startPolling() {
  stopPolling();
  poller = setInterval(() => { if (staff && $("section-telemetry").classList.contains("is-visible")) refreshTelemetry(); }, 30000);
  try {
    channel = db.channel("vibesync-admin-live")
      .on("postgres_changes", { event: "*", schema: "public", table: "profiles" }, () => {
        if (staff && $("section-telemetry").classList.contains("is-visible")) refreshTelemetry();
      }).subscribe();
  } catch (_) { /* Periodic RPC refresh remains available if realtime is disabled. */ }
}
async function refreshTelemetry() {
  if (!staff) return;
  $("telemetry-state").textContent = "Refreshing";
  try {
    const { data, error } = await db.rpc("vibesync_get_admin_telemetry");
    if (error) throw error;
    const t = data || {}, c = t.counts || {};
    metricCards($("telemetry-cards"), [
      {label:"Registered profiles",value:c.profiles ?? 0,detail:"Profile records"},
      {label:"Active · last 24h",value:t.active_24h ?? 0,detail:"Known app-resume heartbeat"},
      {label:"Active · last 7d",value:t.active_7d ?? 0,detail:"Known app-resume heartbeat"},
      {label:"Payment orders",value:c.business_payment_orders ?? 0,detail:"All order states"}
    ]);
    $("telemetry-json").textContent = JSON.stringify(t, null, 2);
    $("telemetry-state").textContent = "Live · server RPC"; $("telemetry-state").className = "state-pill";
    $("last-refreshed").textContent = "Last refresh " + new Date().toLocaleTimeString("en-IN"); setGlobal("");
  } catch (error) {
    $("telemetry-state").textContent = "Unavailable"; $("telemetry-state").className = "state-pill state-bad";
    $("telemetry-json").textContent = error.message || "Telemetry request failed.";
    setGlobal("Telemetry unavailable. Confirm migration deployment and role permissions.", true);
  }
}
function actionButton(label, cls, action) {
  const b = el("button", "btn btn-mini " + (cls || ""), label); b.type = "button"; b.addEventListener("click", action); return b;
}
function moderationActions(profile) {
  const actions = el("div", "table-actions");
  const verified = profile.is_verified === true;
  actions.append(actionButton(verified ? "Unverify" : "Verify free", verified ? "" : "good", async event => {
    const b = event.currentTarget; b.disabled = true;
    try {
      const { error } = await db.rpc("vibesync_admin_verify_profile", {p_profile_id:profile.id,p_verified:!verified});
      if (error) throw error; toast("Free verification updated."); await searchProfiles();
    } catch (error) { toast(error.message || "Verification failed.", true); b.disabled = false; }
  }));
  actions.append(actionButton("Mark spam", "", async event => {
    if (!window.confirm("Mark this profile as spam? This action is audit logged.")) return;
    const b = event.currentTarget; b.disabled = true;
    try {
      const { error } = await db.rpc("vibesync_admin_moderate_profile", {p_profile_id:profile.id,p_action:"spam",p_enabled:true,p_reason:"Admin review"});
      if (error) throw error; toast("Spam flag saved."); await searchProfiles();
    } catch (error) { toast(error.message || "Spam action failed.", true); b.disabled = false; }
  }));
  const banned = String(profile.account_status || "").toUpperCase() === "BANNED";
  actions.append(actionButton(banned ? "Restore" : "Ban", banned ? "" : "danger", async event => {
    if (!window.confirm((banned ? "Restore" : "Ban") + " this profile? This action is audit logged.")) return;
    const b = event.currentTarget; b.disabled = true;
    try {
      const { error } = await db.rpc("vibesync_admin_moderate_profile", {p_profile_id:profile.id,p_action:"ban",p_enabled:!banned,p_reason:"Admin review"});
      if (error) throw error; toast(banned ? "Profile restored." : "Profile banned."); await searchProfiles();
    } catch (error) { toast(error.message || "Ban action failed.", true); b.disabled = false; }
  }));
  return actions;
}
async function searchProfiles() {
  requireRole(["super_admin","moderator"]);
  const term = $("profile-query").value.trim();
  if (term.length < 2) throw new Error("Enter at least two characters.");
  const target = $("profile-results");
  empty(target,"Searching profiles…","Querying the authorized server-side directory.");
  const {data,error}=await db.rpc("vibesync_admin_search_profiles",{p_query:term});
  if(error) throw error;
  const records=data?.records||[];
  const rows=records.map(profile=>({...profile,actions:moderationActions(profile)}));
  target.replaceChildren(table([
    {label:"Profile",value:r=>{const c=el("div");c.append(el("strong","",r.name||"(unnamed profile)"),el("small","",r.id));return c;}},
    {label:"Location",value:r=>[r.city,r.country].filter(Boolean).join(", ")||"—"},
    {label:"Account status",value:r=>String(r.account_status||"UNKNOWN")+(r.is_deleted?" · deleted":"")},
    {label:"Verified",value:r=>r.is_verified?"Yes":"No"},
    {label:"Updated",value:r=>date(r.updated_at)},
    {label:"Actions",value:r=>r.actions}
  ],rows));
  if(!records.length) toast("No profiles matched that search.");
}
async function refreshPayments() {
  requireRole(["super_admin","finance_manager"]);
  empty($("payment-results"),"Loading payment summary…","Server-side finance RPC.");
  const {data,error}=await db.rpc("vibesync_admin_get_payment_summary"); if(error)throw error;
  const p=data||{};
  metricCards($("payment-cards"),[
    {label:"Paid orders",value:p.paid_orders??0,detail:"Confirmed order state"},
    {label:"Pending orders",value:p.pending_orders??0,detail:"Awaiting completion"},
    {label:"Gross paid (INR)",value:p.gross_paid_inr??0,detail:"Server-side sum"},
    {label:"Failed / expired",value:(p.failed_orders??0)+(p.expired_orders??0),detail:"Combined order states"}
  ]);
  const rows=(p.recent_orders||[]).map(r=>({...r,amount:"₹"+fmt(r.total_amount_inr),created:date(r.created_at),paid:date(r.paid_at),refs:[r.provider_order_id,r.provider_payment_id].filter(Boolean).join(" / ")||"—"}));
  $("payment-results").replaceChildren(table([
    {label:"Order",value:r=>{const c=el("div");c.append(el("strong","",r.id),el("small","",r.refs));return c;}},
    {label:"Provider",value:r=>r.provider},{label:"Type",value:r=>r.purchase_type},{label:"Amount",value:r=>r.amount},
    {label:"Status",value:r=>r.status},{label:"Created",value:r=>r.created},{label:"Paid",value:r=>r.paid}
  ],rows));
  const wb=p.webhook_observability||{};
  $("webhook-state").textContent=wb.state==="observed"?"Webhook observed":"Webhook telemetry unverified";
  $("webhook-state").className="state-pill "+(wb.state==="observed"?"":"state-warn");
  $("webhook-note").textContent=wb.message||"Provider delivery events are not persisted by the current schema. Confirm health in the payment provider console.";
}
async function sha256(value) {
  const bytes=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes)).map(b=>b.toString(16).padStart(2,"0")).join("");
}
function download(name,data,type) {
  const url=URL.createObjectURL(new Blob([data],{type})),a=document.createElement("a");
  a.href=url;a.download=name;document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);
}
function safeName(value){return String(value||"case").replace(/[^a-zA-Z0-9_-]+/g,"_").slice(0,60)||"case";}
async function leaSearch(event) {
  event.preventDefault();requireRole(["super_admin","compliance_officer"]);
  if(!$("lea-confirm").checked)throw new Error("Confirm legal-process authorization.");
  text($("lea-status"),"Searching non-content metadata…");
  const {data,error}=await db.rpc("vibesync_admin_search_lea_metadata",{
    p_query:$("lea-query").value.trim(),p_case_reference:$("lea-case").value.trim(),p_legal_basis:$("lea-basis").value
  });
  if(error)throw error;
  const rows=data.records||[],generated=data.generated_at||new Date().toISOString();
  const payload={product:"VibeSync compliance metadata export",legal_basis:data.legal_basis,case_reference:data.case_reference,
    request_id:data.request_id,generated_at:generated,record_count:rows.length,
    content_scope:"Non-content account metadata only; no message contents or encrypted payloads.",records:rows};
  const canonical=JSON.stringify(payload),digest=await sha256(canonical);
  const {error:sealError}=await db.rpc("vibesync_admin_seal_compliance_export",{
    p_export_id:data.request_id,p_sha256:digest,p_record_count:rows.length
  });
  if(sealError)throw new Error("Server could not persist the SHA-256 seal; export not downloaded. "+sealError.message);
  payload.integrity_seal={algorithm:"SHA-256",digest,covers:"canonical JSON before integrity_seal was added"};
  download("vibesync-lea-"+safeName($("lea-case").value)+"-"+generated.replace(/[:.]/g,"-")+".json",JSON.stringify(payload,null,2),"application/json");
  $("lea-results").replaceChildren(table([
    {label:"Profile ID",value:r=>r.id},{label:"Name",value:r=>r.name},
    {label:"City / country",value:r=>[r.city,r.country].filter(Boolean).join(", ")||"—"},
    {label:"Account status",value:r=>r.account_status},{label:"Verified",value:r=>r.is_verified?"Yes":"No"},
    {label:"Created",value:r=>date(r.created_at)},{label:"Updated",value:r=>date(r.updated_at)}
  ],rows));
  text($("lea-status"),"Metadata exported and sealed. Request ID: "+data.request_id+" · SHA-256: "+digest);
}
async function broadcast(days) {
  requireRole(["super_admin"]);
  if(!window.confirm("Send a generic VibeSync reminder to users whose recorded activity is at least "+days+" days old? This will send real push notifications."))return;
  text($("broadcast-status"),"Submitting server-side FCM job…");
  const {data,error}=await db.functions.invoke("admin-fcm-broadcast",{body:{inactiveDays:days}});
  if(error)throw error;
  text($("broadcast-status"),"Job "+fmt(data.job_id)+" · Eligible profiles "+fmt(data.eligible_profiles)+" · Tokens attempted "+fmt(data.tokens_attempted)+" · Sent "+fmt(data.sent)+" · Failed "+fmt(data.failed));
}
function csvCell(v){
  let s=v===null||v===undefined?"":(typeof v==="object"?JSON.stringify(v):String(v));
  if(/^[=+@-]/.test(s))s="'"+s;
  return '"'+s.replace(/"/g,'""')+'"';
}
function toCsv(rows){
  const keys=[...new Set(rows.flatMap(r=>Object.keys(r)))];
  return [keys.map(csvCell).join(","),...rows.map(r=>keys.map(k=>csvCell(r[k])).join(","))].join("\r\n");
}
async function exportTable(name) {
  const rows=[];let offset=0;
  for(;;){
    const {data,error}=await db.rpc("vibesync_admin_export_table_page",{p_table:name,p_offset:offset,p_limit:1000});
    if(error)throw new Error("Export of "+name+" failed: "+error.message);
    const page=data.records||[];rows.push(...page);offset+=page.length;
    text($("export-status"),"Exporting "+name+" · "+rows.length.toLocaleString("en-IN")+" rows");
    if(page.length<1000)break;
  }
  return rows.map(r=>({table:name,...r}));
}
async function snapshot() {
  requireRole(["super_admin","finance_manager","compliance_officer"]);
  const tables=exportTables[staff.role]||[];if(!tables.length)throw new Error("No export tables are approved for your role.");
  const button=$("export-snapshot");button.disabled=true;$("export-progress").style.width="2%";
  try{
    const records=[];
    for(let i=0;i<tables.length;i++){records.push(...await exportTable(tables[i]));$("export-progress").style.width=Math.round((i+1)/tables.length*85)+"%";}
    const generated=new Date().toISOString();
    const excluded=["message contents and encrypted payloads","auth credentials and private keys","FCM tokens","phone numbers and contact hashes"];
    const base={product:"VibeSync operational data preservation snapshot",generated_at:generated,generated_by:user.id,role:staff.role,tables,record_count:records.length,exclusions:excluded,records};
    const name="vibesync-preservation-"+generated.replace(/[:.]/g,"-");
    if($("export-format").value==="csv"){
      const csv=toCsv(records),digest=await sha256(csv);
      download(name+".csv",csv,"text/csv;charset=utf-8");
      download(name+".seal.json",JSON.stringify({algorithm:"SHA-256",digest,covers:"CSV UTF-8 bytes",generated_at:generated,generated_by:user.id,role:staff.role,tables,record_count:records.length},null,2),"application/json");
    }else{
      const digest=await sha256(JSON.stringify(base));
      base.integrity_seal={algorithm:"SHA-256",digest,covers:"canonical JSON before integrity_seal was added"};
      download(name+".json",JSON.stringify(base,null,2),"application/json");
    }
    $("export-progress").style.width="100%";
    text($("export-status"),"Snapshot downloaded · "+records.length.toLocaleString("en-IN")+" rows · "+tables.length+" tables · "+generated);
  }finally{button.disabled=false;}
}

$("login-form").addEventListener("submit",async event=>{
  event.preventDefault();if(!db||!configured())return;
  const b=$("login-submit");b.disabled=true;text($("auth-message"),"Requesting a one-time sign-in link…");
  try{
    const {error}=await db.auth.signInWithOtp({email:$("staff-email").value.trim(),options:{
      emailRedirectTo:location.origin+location.pathname,shouldCreateUser:false
    }});
    if(error)throw error;
    text($("auth-message"),"If this email belongs to an invited staff member, a secure sign-in link has been sent.");
  }catch(e){text($("auth-message"),e.message||"Sign-in link could not be sent.",true);}
  finally{b.disabled=false;}
});
$("sign-out").addEventListener("click",async()=>{if(db)await db.auth.signOut();signOutView();text($("auth-message"),"Signed out.");});
document.querySelectorAll(".nav-item").forEach(b=>b.addEventListener("click",()=>showSection(b.dataset.section)));
document.querySelectorAll("[data-home]").forEach(a=>a.addEventListener("click",e=>{e.preventDefault();if(staff)showSection("telemetry");}));
$("refresh-telemetry").addEventListener("click",refreshTelemetry);
$("refresh-payments").addEventListener("click",()=>refreshPayments().catch(e=>toast(e.message,true)));
$("profile-search-form").addEventListener("submit",e=>{e.preventDefault();searchProfiles().catch(error=>empty($("profile-results"),"Search failed",error.message));});
$("lea-form").addEventListener("submit",e=>leaSearch(e).catch(error=>text($("lea-status"),error.message||"Compliance search failed.",true)));
document.querySelectorAll("[data-broadcast-days]").forEach(b=>b.addEventListener("click",()=>broadcast(Number(b.dataset.broadcastDays)).catch(e=>text($("broadcast-status"),e.message,true))));
$("export-snapshot").addEventListener("click",()=>snapshot().catch(e=>{text($("export-status"),e.message||"Export failed.",true);toast("Snapshot failed.",true);}));

if(!configured()){
  $("config-warning").hidden=false;$("login-submit").disabled=true;
  text($("auth-message"),"Configuration incomplete. Sign-in remains disabled until a public Supabase key is configured.",true);
}else{
  db=createClient(cfg.supabaseUrl,cfg.supabaseAnonKey,{auth:{persistSession:true,autoRefreshToken:true,detectSessionInUrl:true}});
  db.auth.onAuthStateChange((_event,session)=>queueMicrotask(()=>authorize(session)));
  db.auth.getSession().then(({data,error})=>error?text($("auth-message"),error.message,true):authorize(data.session))
    .catch(e=>text($("auth-message"),e.message||"Session check failed.",true));
}
