import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = (() => {
  try {
    const parsed = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "");
    if (typeof parsed?.default === "string" && parsed.default) return parsed.default;
  } catch { /* legacy key below */ }
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
})();
const JWT_SECRET = Deno.env.get("JWT_SECRET") || Deno.env.get("APP_JWT_SECRET") || "";
const admin = SUPABASE_URL && SERVICE_ROLE_KEY
  ? createClient(SUPABASE_URL, SERVICE_ROLE_KEY, { auth: { persistSession: false, autoRefreshToken: false } })
  : null;
const rateBuckets = new Map<string, { count: number; resetAt: number }>();
const cors = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "authorization, apikey, content-type, x-client-info, x-vibesync-session",
  "access-control-allow-methods": "POST, OPTIONS",
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};

function json(body: unknown, status = 200, extraHeaders: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), { status, headers: { ...cors, ...extraHeaders } });
}
function clientIp(request: Request): string {
  return request.headers.get("cf-connecting-ip")?.trim() ||
    request.headers.get("x-real-ip")?.trim() ||
    request.headers.get("x-forwarded-for")?.split(",").map((v) => v.trim()).filter(Boolean).pop() || "unknown";
}
function takeRateLimit(key: string, limit: number, windowMs: number): number {
  const now = Date.now();
  let bucket = rateBuckets.get(key);
  if (!bucket || bucket.resetAt <= now) { bucket = { count: 0, resetAt: now + windowMs }; rateBuckets.set(key, bucket); }
  if (bucket.count >= limit) return Math.max(1, Math.ceil((bucket.resetAt - now) / 1000));
  bucket.count += 1;
  if (rateBuckets.size > 10000) {
    for (const [candidate, value] of rateBuckets) if (value.resetAt <= now) rateBuckets.delete(candidate);
    while (rateBuckets.size > 12000) rateBuckets.delete(rateBuckets.keys().next().value!);
  }
  return 0;
}
function decodeBase64Url(value: string): Uint8Array {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, (ch) => ch.charCodeAt(0));
}
async function verifyCustomJwt(token: string): Promise<Record<string, unknown> | null> {
  if (!JWT_SECRET) return null;
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const header = JSON.parse(new TextDecoder().decode(decodeBase64Url(parts[0])));
    const claims = JSON.parse(new TextDecoder().decode(decodeBase64Url(parts[1]))) as Record<string, unknown>;
    const now = Math.floor(Date.now() / 1000);
    if (header.alg !== "HS256" || claims.iss !== "supabase" || claims.role !== "authenticated" ||
        claims.aud !== "authenticated" || typeof claims.sub !== "string" ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(claims.sub) ||
        typeof claims.exp !== "number" || claims.exp <= now ||
        typeof claims.profile_id !== "string" || typeof claims.jti !== "string" || !claims.jti) return null;
    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(JWT_SECRET),
      { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
    const valid = await crypto.subtle.verify("HMAC", key, decodeBase64Url(parts[2]),
      new TextEncoder().encode(parts[0] + "." + parts[1]));
    return valid ? claims : null;
  } catch { return null; }
}
async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}
async function resolveVerifiedProfile(sessionToken: string, claims: Record<string, unknown>): Promise<string | null> {
  if (!admin || !sessionToken) return null;
  const sessionHash = await sha256Hex(sessionToken);
  const [sessionResult, mapResult] = await Promise.all([
    admin.from("vibesync_sessions").select("profile_id,jwt_id")
      .eq("session_hash", sessionHash).gt("expires_at", new Date().toISOString()).maybeSingle(),
    admin.from("vibesync_auth_identity_map").select("profile_id")
      .eq("auth_uid", String(claims.sub)).maybeSingle(),
  ]);
  const sessionProfile = String(sessionResult.data?.profile_id ?? "");
  if (sessionResult.error || mapResult.error || !sessionProfile ||
      sessionProfile !== String(claims.profile_id) ||
      String(sessionResult.data?.jwt_id ?? "") !== String(claims.jti) ||
      String(mapResult.data?.profile_id ?? "") !== sessionProfile) return null;
  return sessionProfile;
}

Deno.serve(async (request: Request) => {
  if (request.method === "OPTIONS") return new Response("ok", { status: 200, headers: cors });
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  const ipRetry = takeRateLimit("call:ip-minute:" + clientIp(request), 40, 60_000);
  if (ipRetry > 0) return json({ error: "rate_limited", retry_after_seconds: ipRetry }, 429, { "retry-after": String(ipRetry) });
  if (!admin) return json({ error: "server_configuration_incomplete" }, 503);
  if (!JWT_SECRET) return json({ error: "jwt_secret_not_configured" }, 503);

  const token = (request.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "").trim();
  const claims = await verifyCustomJwt(token);
  if (!claims) return json({ error: "verified_vibesync_jwt_required" }, 401);
  const profileRetry = takeRateLimit("call:profile-minute:" + String(claims.sub), 8, 60_000);
  if (profileRetry > 0) return json({ error: "rate_limited", retry_after_seconds: profileRetry }, 429, { "retry-after": String(profileRetry) });

  let body: Record<string, unknown>;
  try {
    const parsed = await request.json();
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return json({ error: "invalid_json_object" }, 400);
    body = parsed as Record<string, unknown>;
  } catch { return json({ error: "invalid_json" }, 400); }
  const recipient = String(body.recipient_profile_id ?? "").trim();
  const callId = String(body.call_id ?? "").trim();
  if (!recipient || recipient.length > 128 || recipient === String(claims.profile_id) ||
      callId !== [String(claims.profile_id), recipient].sort().join(":")) {
    return json({ error: "invalid_call_participants" }, 400);
  }
  const peerRetry = takeRateLimit("call:peer-minute:" + recipient, 4, 60_000);
  if (peerRetry > 0) return json({ error: "recipient_rate_limited", retry_after_seconds: peerRetry }, 429, { "retry-after": String(peerRetry) });

  const sessionToken = request.headers.get("x-vibesync-session")?.trim() ?? "";
  if (!await resolveVerifiedProfile(sessionToken, claims)) {
    return json({ error: "verified_vibesync_session_required" }, 401);
  }
  return json({ ok: true, admitted: true, call_id: callId });
});
