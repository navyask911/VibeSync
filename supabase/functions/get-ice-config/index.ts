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
const TURN_KEY_ID = Deno.env.get("CLOUDFLARE_TURN_KEY_ID") ?? "";
const TURN_API_TOKEN = Deno.env.get("CLOUDFLARE_TURN_API_TOKEN") ?? "";
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
  if (!admin || !SUPABASE_URL) return json({ error: "server_configuration_incomplete" }, 503);
  const sessionToken = request.headers.get("x-vibesync-session")?.trim() ?? "";
  const profileId = await resolveProfileId(sessionToken);
  if (!profileId) return json({ error: "verified_vibesync_session_required" }, 401);

  // TURN API credentials remain server-only; clients receive short-lived ICE username/credential.
  if (!TURN_KEY_ID || !TURN_API_TOKEN) {
    return json({ error: "turn_service_not_configured", profile_id: profileId }, 503);
  }
  try {
    const response = await fetch(
      "https://rtc.live.cloudflare.com/v1/turn/keys/" + encodeURIComponent(TURN_KEY_ID) + "/credentials/generate-ice-servers",
      {
        method: "POST",
        headers: { authorization: "Bearer " + TURN_API_TOKEN, "content-type": "application/json" },
        body: JSON.stringify({ ttl: 3600 }),
      },
    );
    if (!response.ok) {
      console.warn("TURN credential request failed with HTTP", response.status);
      return json({ error: "turn_credentials_unavailable" }, 502);
    }
    const result = await response.json();
    const iceServers = Array.isArray(result.iceServers) ? result.iceServers : [];
    if (!iceServers.length) return json({ error: "turn_credentials_missing" }, 502);
    return json({ iceServers, expires_in_seconds: 3600 });
  } catch {
    return json({ error: "turn_credentials_unavailable" }, 502);
  }
});
