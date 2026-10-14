import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

type ServiceAccount = { client_email: string; private_key: string; project_id: string; token_uri?: string };
const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = (() => {
  try {
    const parsed = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "");
    if (typeof parsed?.default === "string" && parsed.default) return parsed.default;
  } catch { /* legacy env below */ }
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
})();
const SERVICE_ACCOUNT_TEXT = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON") ?? "";
const admin = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, { auth: { persistSession: false, autoRefreshToken: false } });
const cors = {
  "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-vibesync-session",
  "Access-Control-Allow-Methods": "POST, OPTIONS", "Cache-Control": "no-store",
};
function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { ...cors, "content-type": "application/json; charset=utf-8" } });
}
async function sha256(value: string) {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes)).map(v => v.toString(16).padStart(2, "0")).join("");
}
async function resolveProfileId(token: string) {
  if (!token) return null;
  const hash = await sha256(token);
  const { data, error } = await admin.from("vibesync_sessions").select("profile_id")
    .eq("session_hash", hash).gt("expires_at", new Date().toISOString()).maybeSingle();
  if (error || !data?.profile_id) return null;
  return String(data.profile_id);
}
function base64Url(value: string | Uint8Array) {
  const bytes = typeof value === "string" ? new TextEncoder().encode(value) : value;
  let binary = ""; for (const valueByte of bytes) binary += String.fromCharCode(valueByte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
function pemToDer(pem: string) {
  const clean = pem.replace(/-----BEGIN PRIVATE KEY-----/g, "").replace(/-----END PRIVATE KEY-----/g, "").replace(/\s+/g, "");
  const binary = atob(clean); const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}
async function googleAccessToken(sa: ServiceAccount) {
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = base64Url(JSON.stringify({ iss: sa.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: sa.token_uri ?? "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600 }));
  const unsigned = header + "." + claim;
  const key = await crypto.subtle.importKey("pkcs8", pemToDer(sa.private_key), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned));
  const response = await fetch(sa.token_uri ?? "https://oauth2.googleapis.com/token", {
    method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: unsigned + "." + base64Url(new Uint8Array(signature)) }),
  });
  if (!response.ok) throw new Error("Google token exchange failed: " + response.status);
  const result = await response.json(); const token = String(result.access_token ?? "");
  if (!token) throw new Error("Google token missing");
  return token;
}
async function sendFcm(sa: ServiceAccount, token: string, data: Record<string, string>) {
  const accessToken = await googleAccessToken(sa);
  const response = await fetch("https://fcm.googleapis.com/v1/projects/" + encodeURIComponent(sa.project_id) + "/messages:send", {
    method: "POST", headers: { authorization: "Bearer " + accessToken, "content-type": "application/json" },
    body: JSON.stringify({ message: { token, data, android: { priority: "HIGH", ttl: "86400s" },
      apns: { headers: { "apns-priority": "5" }, payload: { aps: { "content-available": 1 } } } } }),
  });
  const body = await response.text();
  if (!response.ok) throw new Error("FCM " + response.status + " " + body.slice(0, 200));
  return body;
}
function parseServiceAccount(): ServiceAccount | null {
  try { const parsed = JSON.parse(SERVICE_ACCOUNT_TEXT) as ServiceAccount; return parsed.client_email && parsed.private_key && parsed.project_id ? parsed : null; }
  catch { return null; }
}
function distanceKm(lat1: number, lon1: number, lat2: number, lon2: number) {
  const rad = (v: number) => v * Math.PI / 180;
  const dLat = rad(lat2-lat1), dLon = rad(lon2-lon1);
  const a = Math.sin(dLat/2)**2 + Math.cos(rad(lat1))*Math.cos(rad(lat2))*Math.sin(dLon/2)**2;
  return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1-a)));
}
Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (request.method !== "POST") return json({ ok: false, error: "method_not_allowed" }, 405);
  if (!SUPABASE_URL || !SERVICE_ROLE_KEY) return json({ ok: false, error: "broadcast_backend_not_configured" }, 503);
  try {
    const profileId = await resolveProfileId(request.headers.get("x-vibesync-session") ?? "");
    if (!profileId) return json({ ok: false, error: "verified_vibesync_session_required" }, 401);
    const body = await request.json(); const broadcastId = String(body.broadcast_id ?? "").trim();
    if (!broadcastId) return json({ ok: false, error: "broadcast_id_required" }, 400);
    const { data: broadcast, error: broadcastError } = await admin.from("business_broadcasts").select("*").eq("id", broadcastId).maybeSingle();
    if (broadcastError || !broadcast) return json({ ok: false, error: "broadcast_not_found" }, 404);
    if (String(broadcast.owner_profile_id) !== profileId) return json({ ok: false, error: "business_owner_required" }, 403);
    if (broadcast.status === "DISPATCHED") return json({ ok: true, already_dispatched: true, broadcast_id: broadcastId });
    const { data: claim, error: claimError } = await admin.from("business_broadcasts")
      .update({ status: "DISPATCHING", dispatch_error: "" }).eq("id", broadcastId).eq("status", "PENDING_DISPATCH").select("id").maybeSingle();
    if (claimError) return json({ ok: false, error: "dispatch_claim_failed" }, 500);
    if (!claim) return json({ ok: false, error: "broadcast_not_pending" }, 409);

    const { data: business } = await admin.from("businesses").select("name,latitude,longitude,banner_url").eq("id", broadcast.business_id).maybeSingle();
    const { data: followers, error: followerError } = await admin.from("business_followers").select("profile_id,interest_tags").eq("business_id", broadcast.business_id);
    if (followerError) {
      await admin.from("business_broadcasts").update({ status: "PENDING_DISPATCH", dispatch_error: "follower_query_failed" }).eq("id", broadcastId);
      return json({ ok: false, error: "audience_lookup_failed" }, 500);
    }
    let eligible = (followers ?? []) as Array<{ profile_id: string; interest_tags: string[] | null }>;
    if (broadcast.target_type === "INTEREST_TAGS" && Array.isArray(broadcast.interest_tags) && broadcast.interest_tags.length) {
      const tags = new Set(broadcast.interest_tags.map((value: string) => value.toLowerCase()));
      eligible = eligible.filter(f => (f.interest_tags ?? []).some(tag => tags.has(String(tag).toLowerCase())));
    }
    if (broadcast.target_type === "NEARBY") {
      const ids = eligible.map(f => f.profile_id);
      if (ids.length) {
        const { data: profiles } = await admin.from("profiles").select("id,latitude,longitude").in("id", ids);
        const map = new Map((profiles ?? []).map((p: any) => [String(p.id), p]));
        eligible = eligible.filter(f => {
          const p: any = map.get(f.profile_id);
          return !!p && p.latitude != null && p.longitude != null &&
            distanceKm(Number(business?.latitude ?? 0), Number(business?.longitude ?? 0), Number(p.latitude), Number(p.longitude)) <= Number(broadcast.radius_km ?? 5);
        });
      } else eligible = [];
    }
    const profileIds = [...new Set(eligible.map(f => String(f.profile_id)))];
    const sa = parseServiceAccount();
    if (!sa) {
      await admin.from("business_broadcasts").update({ status: "PENDING_DISPATCH", dispatch_error: "firebase_service_account_missing" }).eq("id", broadcastId);
      return json({ ok: false, error: "firebase_service_account_missing" }, 503);
    }
    let sentDevices = 0, failedDevices = 0;
    if (profileIds.length) {
      const { data: tokens, error: tokenError } = await admin.from("device_tokens").select("id,profile_id,fcm_token").in("profile_id", profileIds);
      if (tokenError) {
        await admin.from("business_broadcasts").update({ status: "PENDING_DISPATCH", dispatch_error: "token_lookup_failed" }).eq("id", broadcastId);
        return json({ ok: false, error: "token_lookup_failed" }, 500);
      }
      for (const row of tokens ?? []) {
        try {
          await sendFcm(sa, String(row.fcm_token), {
            type: "BUSINESS_BROADCAST", broadcast_id: broadcastId, business_id: String(broadcast.business_id),
            business_name: String(business?.name ?? "Business"), title: String(broadcast.title).slice(0, 120),
            message: String(broadcast.message).slice(0, 900), banner_url: String(broadcast.banner_url ?? business?.banner_url ?? ""),
            cta_label: String(broadcast.cta_label ?? "").slice(0, 50), cta_url: String(broadcast.cta_url ?? "").slice(0, 500),
          });
          sentDevices++;
        } catch (error) { failedDevices++; console.warn("business FCM dispatch failed", String(error).slice(0, 120)); }
      }
    }
    const status = failedDevices > 0 && sentDevices === 0 ? "PENDING_DISPATCH" : "DISPATCHED";
    await admin.from("business_broadcasts").update({ status, dispatched_at: status === "DISPATCHED" ? new Date().toISOString() : null,
      dispatch_error: failedDevices > 0 ? String(failedDevices) + " delivery attempts failed" : "" }).eq("id", broadcastId);
    return json({ ok: true, broadcast_id: broadcastId, status, audience_size: profileIds.length, sent_devices: sentDevices, failed_devices: failedDevices });
  } catch (error) {
    console.error("business broadcast failure", error instanceof Error ? error.message : "unknown");
    return json({ ok: false, error: "broadcast_dispatch_failed" }, 500);
  }
});
