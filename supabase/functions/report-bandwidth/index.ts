import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
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

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
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
  if (!admin) return json({ error: "server_configuration_incomplete" }, 503);
  const profileId = await resolveProfileId(request.headers.get("x-vibesync-session")?.trim() ?? "");
  if (!profileId) return json({ error: "verified_vibesync_session_required" }, 401);

  try {
    const body = await request.json();
    const callId = String(body.call_id ?? "").trim();
    const candidateType = String(body.candidate_type ?? "").toLowerCase();
    const bytesSent = Number(body.bytes_sent);
    const bytesReceived = Number(body.bytes_received);
    const totalBytes = Number(body.total_bytes);
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
