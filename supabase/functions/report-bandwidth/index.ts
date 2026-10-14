import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const JWT_SECRET = Deno.env.get("JWT_SECRET") || Deno.env.get("APP_JWT_SECRET") || "";
const rateBuckets = new Map<string, { count: number; resetAt: number }>();
const SERVICE_ROLE_KEY = (() => {
  try {
    const keys = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "");
    if (typeof keys?.default === "string" && keys.default) return keys.default;
  } catch { /* use legacy environment variable only as a compatibility fallback */ }
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
})();
const admin = SUPABASE_URL && SERVICE_ROLE_KEY
  ? createClient(SUPABASE_URL, SERVICE_ROLE_KEY, { auth: { persistSession: false, autoRefreshToken: false } })
  : null;

function json(body: unknown, status = 200, extraHeaders: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...extraHeaders },
  });
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
    if (header.alg !== "HS256" || claims.iss !== "supabase" || claims.role !== "authenticated" ||
        claims.aud !== "authenticated" || typeof claims.sub !== "string" ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(claims.sub) ||
        typeof claims.exp !== "number" || claims.exp <= Math.floor(Date.now() / 1000) ||
        typeof claims.profile_id !== "string" || typeof claims.jti !== "string") return null;
    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(JWT_SECRET),
      { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
    const valid = await crypto.subtle.verify("HMAC", key, decodeBase64Url(parts[2]),
      new TextEncoder().encode(parts[0] + "." + parts[1]));
    return valid ? claims : null;
  } catch { return null; }
}

async function resolveProfileId(sessionToken: string): Promise<string | null> {
  if (!admin || !sessionToken) return null;
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(sessionToken));
  const hash = Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
  const { data, error } = await admin.from("vibesync_sessions")
    .select("profile_id").eq("session_hash", hash)
    .gt("expires_at", new Date().toISOString()).maybeSingle();
  if (error || !data?.profile_id) return null;
  return String(data.profile_id);
}

Deno.serve(async (request: Request) => {
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  // Reject floods at the Edge boundary before a Postgres lookup.
  const ipRetry = takeRateLimit("bandwidth:ip:" + clientIp(request), 60, 60_000);
  if (ipRetry > 0) return json({ error: "rate_limited", retry_after_seconds: ipRetry }, 429, { "retry-after": String(ipRetry) });
  if (!admin) return json({ error: "server_configuration_incomplete" }, 503);
  const jwt = (request.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "").trim();
  const claims = await verifyCustomJwt(jwt);
  if (!claims) return json({ error: JWT_SECRET ? "verified_vibesync_jwt_required" : "server_configuration_incomplete" }, JWT_SECRET ? 401 : 503);
  const accountRetry = takeRateLimit("bandwidth:profile:" + String(claims.sub), 20, 60_000);
  if (accountRetry > 0) return json({ error: "rate_limited", retry_after_seconds: accountRetry }, 429, { "retry-after": String(accountRetry) });
  const profileId = await resolveProfileId(request.headers.get("x-vibesync-session")?.trim() ?? "");
  if (!profileId || profileId !== String(claims.profile_id)) return json({ error: "verified_vibesync_session_required" }, 401);
  const { data: identityMap, error: identityMapError } = await admin.from("vibesync_auth_identity_map")
    .select("profile_id").eq("auth_uid", String(claims.sub)).maybeSingle();
  if (identityMapError || String(identityMap?.profile_id ?? "") !== profileId) {
    return json({ error: "verified_vibesync_identity_required" }, 401);
  }

  try {
    const body = await request.json();
    const callId = String(body.call_id ?? "").trim();
    const candidateType = String(body.candidate_type ?? "").toLowerCase();
    const bytesSent = Number(body.bytes_sent);
    const bytesReceived = Number(body.bytes_received);
    const totalBytes = Number(body.total_bytes);
    const callParticipants = callId.split(":");
    if (callParticipants.length !== 2 || !callParticipants.includes(profileId)) {
      return json({ error: "call_participant_mismatch" }, 403);
    }
    if (!callId || callId.length > 256 || candidateType !== "relay" ||
        !Number.isSafeInteger(bytesSent) || bytesSent < 0 ||
        !Number.isSafeInteger(bytesReceived) || bytesReceived < 0 ||
        !Number.isSafeInteger(totalBytes) || totalBytes !== bytesSent + bytesReceived) {
      return json({ error: "invalid_relay_usage_report" }, 400);
    }

    // Only selected relay-pair reports can reach this write. Direct/STUN calls must not post.
    const { error } = await admin.from("call_bandwidth_reports").upsert({
      profile_id: profileId,
      call_id: callId,
      candidate_type: "relay",
      bytes_sent: bytesSent,
      bytes_received: bytesReceived,
      reported_at: new Date().toISOString(),
    }, { onConflict: "profile_id,call_id" });
    if (error) {
      console.error("Relay usage persistence failed:", error.code);
      return json({ error: "usage_report_failed" }, 500);
    }
    return json({ ok: true, candidate_type: "relay", bytes_sent: bytesSent, bytes_received: bytesReceived, total_bytes: totalBytes });
  } catch {
    return json({ error: "invalid_json" }, 400);
  }
});
