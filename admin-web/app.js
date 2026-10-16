import { createClient } from "https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2.117.3/+esm";

const cfg = window.VIBESYNC_CONFIG || {};
const $ = (id) => document.getElementById(id);
const permissions = {
  super_admin: ["telemetry", "moderation", "payments", "business-governance", "compliance", "reengagement", "preservation"],
  moderator: ["telemetry", "moderation"],
  finance_manager: ["telemetry", "payments", "preservation"],
  compliance_officer: ["telemetry", "compliance", "preservation"]
};
const exportTables = {
  super_admin: ["profiles", "businesses", "business_followers", "business_payment_orders", "business_wallets", "business_wallet_payment_orders", "business_wallet_ledger", "business_broadcasts", "business_analytics_events", "admin_audit_logs", "admin_users"],
  finance_manager: ["business_payment_orders", "business_wallets", "business_wallet_payment_orders", "business_wallet_ledger"],
  compliance_officer: ["profiles", "business_analytics_events", "admin_audit_logs"]
};
const titleMap = {
  telemetry: ["Live telemetry", "LIVE TELEMETRY"], moderation: ["User moderation", "USER MODERATION"],
  payments: ["Payment & ledger", "PAYMENT & LEDGER"],
  "business-governance": ["Business governance", "BUSINESS GOVERNANCE"],
  compliance: ["Compliance & LEA", "COMPLIANCE & LEA"],
  reengagement: ["Re-engagement", "RE-ENGAGEMENT"], preservation: ["Data preservation", "DATA PRESERVATION"]
};
let db = null, user = null, staff = null, authBusy = false, authorizedId = null, poller = null, channel = null, toastTimer = 0;


const chartInstances=Object.create(null);
const chartColors={teal:"#56d9c3",blue:"#83aaff",amber:"#f4b66d",red:"#f17d91",purple:"#bda3ff"};
async function adminRpc(operation,args={}) {
 const {data:sessionData,error:sessionError}=await db.auth.getSession(),session=sessionData?.session;
 if(sessionError||!session?.access_token)return {data:null,error:new Error("A live staff session is required.")};
 try{
  const response=await fetch(`${cfg.supabaseUrl.replace(/\/$/,"")}/functions/v1/admin-portal-api`,{
   method:"POST",headers:{"Content-Type":"application/json",apikey:cfg.supabaseAnonKey,Authorization:`Bearer ${session.access_token}`},
   body:JSON.stringify({operation,args}),cache:"no-store"
  });
  const payload=await response.json().catch(()=>({}));
  if(!response.ok||payload.error)return {data:null,error:new Error(payload.error||`Admin request failed (${response.status}).`)};
  return {data:payload.data,error:null};
 }catch(_){return {data:null,error:new Error("Admin API is temporarily unavailable. Retry after checking the network connection.")};}
}
function drawChart(canvasId,config){
 if(typeof Chart==="undefined")return;const canvas=$(canvasId);if(!canvas)return;
 if(chartInstances[canvasId])chartInstances[canvasId].destroy();
 const base={responsive:true,maintainAspectRatio:false,interaction:{mode:"index",intersect:false},
  plugins:{legend:{labels:{color:"#aec1d8",usePointStyle:true,boxWidth:8,padding:16}},
   tooltip:{backgroundColor:"#132237",titleColor:"#eff8ff",bodyColor:"#c9d9eb",borderColor:"#314861",borderWidth:1}},
  scales:{x:{ticks:{color:"#8da4bd",maxRotation:0,autoSkip:true},grid:{color:"rgba(105,133,165,.12)"}},
   y:{beginAtZero:true,ticks:{color:"#8da4bd",precision:0},grid:{color:"rgba(105,133,165,.16)"}}}};
 const options={...base,...(config.options||{}),plugins:{...base.plugins,...(config.options?.plugins||{})},scales:{...base.scales,...(config.options?.scales||{})}};
 chartInstances[canvasId]=new Chart(canvas.getContext("2d"),{...config,options});
}
function renderTelemetryChart(rows){
 const points=Array.isArray(rows)?rows:[];
 drawChart("telemetry-chart",{type:"line",data:{
  labels:points.map(p=>new Date(p.hour).toLocaleTimeString("en-IN",{hour:"2-digit",minute:"2-digit"})),
  datasets:[
   {label:"Active connections",data:points.map(p=>Number(p.active_connections||0)),borderColor:chartColors.teal,backgroundColor:"rgba(86,217,195,.12)",fill:true,tension:.32,pointRadius:1.5,yAxisID:"y"},
   {label:"Message throughput",data:points.map(p=>Number(p.message_throughput||0)),borderColor:chartColors.blue,backgroundColor:"rgba(131,170,255,.08)",fill:false,tension:.32,pointRadius:1.5,yAxisID:"y1"}
  ]},options:{scales:{
   x:{ticks:{color:"#8da4bd",maxRotation:0,autoSkip:true},grid:{color:"rgba(105,133,165,.12)"}},
   y:{beginAtZero:true,position:"left",ticks:{color:"#8da4bd",precision:0},grid:{color:"rgba(105,133,165,.16)"}},
   y1:{beginAtZero:true,position:"right",ticks:{color:"#8da4bd",precision:0},grid:{drawOnChartArea:false}}
  }}});
}
function renderModerationChart(c={}){
 drawChart("moderation-chart",{type:"line",data:{labels:["Active · 24h","Inactive","Verified badges"],datasets:[{
  label:"Profiles",data:[Number(c.active||0),Number(c.inactive||0),Number(c.verified||0)],
  borderColor:chartColors.purple,backgroundColor:"rgba(189,163,255,.2)",pointBackgroundColor:[chartColors.teal,chartColors.amber,chartColors.purple],pointRadius:5,fill:true,tension:.25
 }]}});
}
function renderPaymentCharts(s={}){
 const daily=Array.isArray(s.daily_transactions)?s.daily_transactions:[];
 drawChart("payment-volume-chart",{type:"bar",data:{labels:daily.map(p=>p.date),datasets:[
  {label:"All transactions",data:daily.map(p=>Number(p.total||0)),type:"line",borderColor:chartColors.blue,backgroundColor:"rgba(131,170,255,.12)",fill:false,tension:.25,pointRadius:2},
  {label:"Success / paid",data:daily.map(p=>Number(p.succeeded||0)),backgroundColor:"rgba(86,217,195,.7)",borderColor:chartColors.teal,borderWidth:1,borderRadius:3},
  {label:"Failed",data:daily.map(p=>Number(p.failed||0)),backgroundColor:"rgba(241,125,145,.65)",borderColor:chartColors.red,borderWidth:1,borderRadius:3}
 ]}});
 const gateways=Array.isArray(s.gateway_outcomes)?s.gateway_outcomes:[];
 drawChart("gateway-outcomes-chart",{type:"bar",data:{labels:gateways.map(p=>p.provider||"Unknown"),datasets:[
  {label:"Successful / paid",data:gateways.map(p=>Number(p.succeeded||0)),backgroundColor:"rgba(86,217,195,.7)",borderColor:chartColors.teal,borderWidth:1,borderRadius:3},
  {label:"Failed",data:gateways.map(p=>Number(p.failed||0)),backgroundColor:"rgba(241,125,145,.65)",borderColor:chartColors.red,borderWidth:1,borderRadius:3}
 ]}});
}

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
  if (section === "business-governance") refreshBusinessGovernance().catch(e => toast(e.message, true));
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
    const { data, error } = await adminRpc("vibesync_get_admin_telemetry");
    if (error) throw error;
    const t = data || {}, c = t.counts || {};
    renderTelemetryChart(t.hourly_activity || []);
    renderModerationChart(t.user_cohorts || {});
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
      const { error } = await adminRpc("vibesync_admin_verify_profile", {p_profile_id:profile.id,p_verified:!verified});
      if (error) throw error; toast("Free verification updated."); await searchProfiles();
    } catch (error) { toast(error.message || "Verification failed.", true); b.disabled = false; }
  }));
  actions.append(actionButton("Mark spam", "", async event => {
    if (!window.confirm("Mark this profile as spam? This action is audit logged.")) return;
    const b = event.currentTarget; b.disabled = true;
    try {
      const { error } = await adminRpc("vibesync_admin_moderate_profile", {p_profile_id:profile.id,p_action:"spam",p_enabled:true,p_reason:"Admin review"});
      if (error) throw error; toast("Spam flag saved."); await searchProfiles();
    } catch (error) { toast(error.message || "Spam action failed.", true); b.disabled = false; }
  }));
  const banned = String(profile.account_status || "").toUpperCase() === "BANNED";
  actions.append(actionButton(banned ? "Restore" : "Ban", banned ? "" : "danger", async event => {
    if (!window.confirm((banned ? "Restore" : "Ban") + " this profile? This action is audit logged.")) return;
    const b = event.currentTarget; b.disabled = true;
    try {
      const { error } = await adminRpc("vibesync_admin_moderate_profile", {p_profile_id:profile.id,p_action:"ban",p_enabled:!banned,p_reason:"Admin review"});
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
  const {data,error}=await adminRpc("vibesync_admin_search_profiles",{p_query:term});
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
function paymentReviewActions(order) {
  const actions=el("div","table-actions");
  for (const [label,decision,cls] of [
    ["Provider recheck","PROVIDER_RECHECK",""],
    ["Needs info","NEEDS_INFO",""],
    ["Mark rejected","REJECTED","danger"]
  ]) {
    actions.append(actionButton(label,cls,async event=>{
      const reason=window.prompt("Add a review reason for order "+order.id+":",decision==="PROVIDER_RECHECK"?"Provider reconciliation requested":"Reviewed by finance operations");
      if(!reason||!reason.trim())return;
      const button=event.currentTarget;button.disabled=true;
      try {
        const {error}=await adminRpc("vibesync_admin_review_payment_order",{p_order_id:order.id,p_decision:decision,p_reason:reason.trim()});
        if(error)throw error;
        toast("Review status saved. The payment settlement status was not changed.");
        await refreshPayments();
      } catch(error) {toast(error.message||"Payment review failed.",true);button.disabled=false;}
    }));
  }
  return actions;
}
async function refreshPayments() {
  requireRole(["super_admin","finance_manager"]);
  empty($("payment-results"),"Loading payment summary…","Server-side finance RPC.");
  const {data,error}=await adminRpc("vibesync_admin_get_payment_summary"); if(error)throw error;
  const p=data||{};
  renderPaymentCharts(p);
  metricCards($("payment-cards"),[
    {label:"Paid orders",value:p.paid_orders??0,detail:"Confirmed provider settlement"},
    {label:"Pending orders",value:p.pending_orders??0,detail:"Awaiting completion"},
    {label:"Gross paid (INR)",value:p.gross_paid_inr??0,detail:"Server-side sum"},
    {label:"Failed / expired",value:(p.failed_orders??0)+(p.expired_orders??0),detail:"Combined order states"}
  ]);
  const rows=(p.recent_orders||[]).map(r=>({...r,amount:"₹"+fmt(r.total_amount_inr),created:date(r.created_at),paid:date(r.paid_at),refs:[r.provider_order_id,r.provider_payment_id].filter(Boolean).join(" / ")||"—",actions:paymentReviewActions(r)}));
  $("payment-results").replaceChildren(table([
    {label:"Order",value:r=>{const c=el("div");c.append(el("strong","",r.id),el("small","",r.refs));return c;}},
    {label:"Provider",value:r=>r.provider},{label:"Type",value:r=>r.purchase_type},{label:"Amount",value:r=>r.amount},
    {label:"Payment status",value:r=>r.status},
    {label:"Admin review",value:r=>r.admin_review_status||"UNREVIEWED"},
    {label:"Created",value:r=>r.created},{label:"Paid",value:r=>r.paid},
    {label:"Review actions",value:r=>r.actions}
  ],rows));
  const wb=p.webhook_observability||{};
  $("webhook-state").textContent=wb.state==="observed"?"Webhook observed":"Webhook telemetry unverified";
  $("webhook-state").className="state-pill "+(wb.state==="observed"?"":"state-warn");
  $("webhook-note").textContent="Review actions only update admin review metadata; only the verified payment webhook may settle an order. "+(wb.message||"Provider delivery events remain unverified until delivery events are persisted.");
}
function businessControlActions(business) {
  const actions=el("div","table-actions");
  const controls=[
    ["VERIFY",business.is_verified===true?"Unverify":"Verify",business.is_verified===true],
    ["PUBLISH",business.is_published===true?"Unpublish":"Publish",business.is_published===true]
  ];
  controls.forEach(([action,label,wasEnabled])=>actions.append(actionButton(label,wasEnabled?"":"good",async event=>{
    const button=event.currentTarget;button.disabled=true;
    try {
      const {error}=await adminRpc("vibesync_admin_set_business_control",{
        p_business_id:String(business.id),p_action:action,p_enabled:!wasEnabled,p_reason:"Admin dashboard governance review"
      });
      if(error)throw error;
      toast(action==="VERIFY"?"Business verification flag updated.":"Business listing publication updated.");
      await searchBusinesses();
    } catch(error) {toast(error.message||"Business control failed.",true);button.disabled=false;}
  })));
  return actions;
}
async function searchBusinesses() {
  requireRole(["super_admin"]);
  const query=$("business-query").value.trim();
  empty($("business-results"),"Searching business listings…","Fetching a role-authorized server projection.");
  const {data,error}=await adminRpc("vibesync_admin_search_businesses",{p_query:query});
  if(error)throw error;
  const records=data?.records||[];
  $("business-results").replaceChildren(table([
    {label:"Business",value:r=>{const c=el("div");c.append(el("strong","",r.name||"(unnamed business)"),el("small","",r.id));return c;}},
    {label:"Category",value:r=>r.category||"—"},
    {label:"City",value:r=>r.city||"—"},
    {label:"Verified",value:r=>r.is_verified===true?"Yes":"No"},
    {label:"Published",value:r=>r.is_published===true?"Yes":"No"},
    {label:"Wallet points",value:r=>fmt(r.wallet_points||0)},
    {label:"Tier",value:r=>r.tier_badge||r.subscription_status||"—"},
    {label:"Actions",value:r=>businessControlActions(r)}
  ],records));
  if(!records.length)toast("No business listings matched that query.");
}
async function refreshStatusStories() {
  requireRole(["super_admin","moderator"]);
  empty($("status-story-results"),"Loading stories…","Server-side moderation projection; story media is not exported.");
  const {data,error}=await adminRpc("vibesync_admin_list_status_stories",{p_limit:80});
  if(error)throw error;
  const records=data?.records||[];
  const rows=records.map(r=>({...r,created:date(r.createdAt),actions:actionButton(
    r.moderation_status==="REMOVED"?"Restore":"Hide",
    r.moderation_status==="REMOVED"?"good":"danger",
    async event=>{
      const reason=window.prompt("Reason for "+(r.moderation_status==="REMOVED"?"restoring":"hiding")+" story "+r.id+":","Admin moderation review");
      if(!reason||!reason.trim())return;
      const button=event.currentTarget;button.disabled=true;
      try{
        const {error}=await adminRpc("vibesync_admin_moderate_status_story",{
          p_story_id:String(r.id),p_action:r.moderation_status==="REMOVED"?"RESTORE":"HIDE",p_reason:reason.trim()
        });
        if(error)throw error;
        toast("Story moderation decision saved.");
        await refreshStatusStories();
      }catch(error){toast(error.message||"Story moderation failed.",true);button.disabled=false;}
    }
  )}));
  $("status-story-results").replaceChildren(table([
    {label:"Story",value:r=>{const c=el("div");c.append(el("strong","",r.userName||"Unknown user"),el("small","",r.id));return c;}},
    {label:"Owner ID",value:r=>r.userId||"—"},
    {label:"Media",value:r=>r.mediaType||"PHOTO"},
    {label:"Caption",value:r=>r.captionText||"—"},
    {label:"Created",value:r=>r.created},
    {label:"Moderation",value:r=>r.moderation_status||"ACTIVE"},
    {label:"Actions",value:r=>r.actions}
  ],rows));
  if(!records.length)empty($("status-story-results"),"No story records","There are no status stories in the current metadata window.");
}
async function refreshBusinessGovernance() {
  requireRole(["super_admin"]);
  const {data,error}=await adminRpc("vibesync_admin_business_analytics_summary");
  if(error)throw error;
  const metrics=data||{};
  metricCards($("business-metrics"),[
    {label:"Published listings",value:metrics.published_businesses??0,detail:(metrics.business_count??0)+" total businesses"},
    {label:"Profile views · 30 days",value:metrics.profile_views_30d??0,detail:"Business directory and profile traffic"},
    {label:"Impressions · 30 days",value:metrics.impressions_30d??0,detail:"Server-recorded listing impressions"},
    {label:"Chat inquiries · 30 days",value:metrics.inquiries_30d??0,detail:"Contact-intent events"},
    {label:"Wallet points",value:fmt(metrics.wallet_points??0),detail:"Across business wallets"},
    {label:"Dispatched broadcasts",value:metrics.broadcasts_dispatched??0,detail:(metrics.broadcasts_total??0)+" total broadcasts"}
  ]);
  await searchBusinesses();
  await refreshStatusStories();
}
async function applyBusinessOperationalSettings(event) {
  event.preventDefault();requireRole(["super_admin"]);
  const businessId=$("settings-business-id").value.trim();
  const openingHours=$("settings-opening-hours").value.trim();
  const offerSummary=$("settings-active-offer").value.trim();
  const reason=$("settings-reason").value.trim();
  if(!businessId||!reason)throw new Error("A business ID and audit reason are required.");
  const button=event.submitter||$("business-settings-form").querySelector('button[type="submit"]');
  button.disabled=true;text($("business-settings-status"),"Saving audited operational settings…");
  try {
    const {data,error}=await adminRpc("vibesync_admin_update_business_settings",{
      p_business_id:businessId,p_opening_hours:openingHours,p_active_offer_summary:offerSummary,p_reason:reason
    });
    if(error)throw error;
    text($("business-settings-status"),"Operational settings saved for "+(data?.business_id||businessId)+".");
    $("settings-reason").value="";
    await searchBusinesses();
  } catch(error) {
    text($("business-settings-status"),error.message||"Could not save operational settings.",true);
    throw error;
  } finally {button.disabled=false;}
}
async function applyWalletAdjustment(event) {
  event.preventDefault();requireRole(["super_admin"]);
  const businessId=$("wallet-business-id").value.trim();
  const delta=Number($("wallet-points-delta").value);
  const reason=$("wallet-adjustment-reason").value.trim();
  if(!businessId||!Number.isInteger(delta)||delta===0||Math.abs(delta)>1000000||!reason)throw new Error("Enter a business ID, non-zero whole-point adjustment and reason.");
  const button=event.submitter||$("wallet-adjustment-form").querySelector('button[type="submit"]');
  button.disabled=true;text($("wallet-adjustment-status"),"Applying wallet and ledger update atomically…");
  try{
    const {data,error}=await adminRpc("vibesync_admin_adjust_business_wallet",{p_business_id:businessId,p_points_delta:delta,p_reason:reason});
    if(error)throw error;
    text($("wallet-adjustment-status"),"Adjustment committed · new balance "+fmt(data?.balance)+" points · ledger reference "+(data?.ledger_reference||"saved"));
    $("wallet-points-delta").value="";
    $("wallet-adjustment-reason").value="";
    await searchBusinesses();
  }catch(error){
    text($("wallet-adjustment-status"),error.message||"Wallet adjustment failed.",true);
    throw error;
  }finally{button.disabled=false;}
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
  const {data,error}=await adminRpc("vibesync_admin_search_lea_metadata",{
    p_query:$("lea-query").value.trim(),p_case_reference:$("lea-case").value.trim(),p_legal_basis:$("lea-basis").value
  });
  if(error)throw error;
  const rows=data.records||[],generated=data.generated_at||new Date().toISOString();
  const payload={product:"VibeSync compliance metadata export",legal_basis:data.legal_basis,case_reference:data.case_reference,
    request_id:data.request_id,generated_at:generated,record_count:rows.length,
    content_scope:"Non-content account metadata only; no message contents or encrypted payloads.",records:rows};
  const canonical=JSON.stringify(payload),digest=await sha256(canonical);
  const {error:sealError}=await adminRpc("vibesync_admin_seal_compliance_export",{
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
    const {data,error}=await adminRpc("vibesync_admin_export_table_page",{p_table:name,p_offset:offset,p_limit:1000});
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
$("refresh-business").addEventListener("click",()=>refreshBusinessGovernance().catch(e=>toast(e.message,true)));
$("refresh-status-stories").addEventListener("click",()=>refreshStatusStories().catch(e=>toast(e.message,true)));
$("business-search-form").addEventListener("submit",e=>{e.preventDefault();searchBusinesses().catch(error=>empty($("business-results"),"Search failed",error.message));});
$("wallet-adjustment-form").addEventListener("submit",e=>applyWalletAdjustment(e).catch(error=>text($("wallet-adjustment-status"),error.message||"Wallet adjustment failed.",true)));
$("business-settings-form").addEventListener("submit",e=>applyBusinessOperationalSettings(e).catch(error=>text($("business-settings-status"),error.message||"Operational settings update failed.",true)));
$("profile-search-form").addEventListener("submit",e=>{e.preventDefault();searchProfiles().catch(error=>empty($("profile-results"),"Search failed",error.message));});
$("lea-form").addEventListener("submit",e=>leaSearch(e).catch(error=>text($("lea-status"),error.message||"Compliance search failed.",true)));
document.querySelectorAll("[data-broadcast-days]").forEach(b=>b.addEventListener("click",()=>broadcast(Number(b.dataset.broadcastDays)).catch(e=>text($("broadcast-status"),e.message,true))));
$("export-snapshot").addEventListener("click",()=>snapshot().catch(e=>{text($("export-status"),e.message||"Export failed.",true);toast("Snapshot failed.",true);}));

function applyAdminTheme(theme) {
  const normalized=theme==="light"?"light":"dark";
  document.documentElement.dataset.theme=normalized;
  const button=$("theme-toggle");
  if(button){
    button.textContent=normalized==="light"?"☾ Dark theme":"☼ Light theme";
    button.setAttribute("aria-pressed",String(normalized==="light"));
  }
}
applyAdminTheme(localStorage.getItem("vibesync-admin-theme")||"dark");
$("theme-toggle").addEventListener("click",()=>{
  const next=document.documentElement.dataset.theme==="light"?"dark":"light";
  localStorage.setItem("vibesync-admin-theme",next);
  applyAdminTheme(next);
});

if(!configured()){
  $("config-warning").hidden=false;$("login-submit").disabled=true;
  text($("auth-message"),"Configuration incomplete. Sign-in remains disabled until a public Supabase key is configured.",true);
}else{
  db=createClient(cfg.supabaseUrl,cfg.supabaseAnonKey,{auth:{persistSession:true,autoRefreshToken:true,detectSessionInUrl:true}});
  db.auth.onAuthStateChange((_event,session)=>queueMicrotask(()=>authorize(session)));
  db.auth.getSession().then(({data,error})=>error?text($("auth-message"),error.message,true):authorize(data.session))
    .catch(e=>text($("auth-message"),e.message||"Session check failed.",true));
}
