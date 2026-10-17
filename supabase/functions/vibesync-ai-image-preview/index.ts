import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const serviceRoleKey = (() => {
  try {
    const parsed = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "");
    if (typeof parsed?.default === "string" && parsed.default) return parsed.default;
  } catch {
    // Older deployments may still use the legacy secret name.
  }
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
})();
const providerUrl = Deno.env.get("CHAT_IMAGE_PROVIDER_URL") ?? "";
const providerApiKey = Deno.env.get("CHAT_IMAGE_PROVIDER_API_KEY") ?? "";
const providerModel = Deno.env.get("CHAT_IMAGE_PROVIDER_MODEL") ?? "";
const DAILY_LIMIT = 8;
const MAX_PROMPT_LENGTH = 800;

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function resolveProfileId(admin: ReturnType<typeof createClient>, sessionToken: string): Promise<string> {
  if (!sessionToken) throw new Error("session_required");
  const sessionHash = await sha256Hex(sessionToken);
  const { data, error } = await admin.from("vibesync_sessions")
    .select("profile_id,expires_at")
    .eq("session_hash", sessionHash)
    .gt("expires_at", new Date().toISOString())
    .maybeSingle();
  if (error || !data?.profile_id) throw new Error("invalid_session");
  return String(data.profile_id);
}

function safeHttpsUrl(value: unknown): string | null {
  if (typeof value !== "string" || !value.trim()) return null;
  try {
    const url = new URL(value.trim());
    if (url.protocol !== "https:" || !url.hostname || url.username || url.password) return null;
    return url.toString();
  } catch {
    return null;
  }
}

function extractPreviewUrls(payload: Record<string, unknown>): string[] {
  const candidates = [
    payload.preview_urls,
    payload.images,
    payload.data,
    payload.output,
  ].find((value) => Array.isArray(value)) as unknown[] | undefined;
  if (!candidates) return [];
  const urls = new Set<string>();
  for (const item of candidates) {
    const raw = typeof item === "string"
      ? item
      : item && typeof item === "object"
        ? String((item as Record<string, unknown>).url ?? (item as Record<string, unknown>).image_url ?? "")
        : "";
    const url = safeHttpsUrl(raw);
    if (url) urls.add(url);
    if (urls.size >= 4) break;
  }
  return Array.from(urls);
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!supabaseUrl || !serviceRoleKey) return json({ error: "server_configuration_incomplete" }, 503);

  let body: Record<string, unknown>;
  try {
    const value = await req.json();
    if (!value || typeof value !== "object" || Array.isArray(value)) return json({ error: "invalid_request" }, 400);
    body = value as Record<string, unknown>;
  } catch {
    return json({ error: "invalid_json" }, 400);
  }
  const prompt = String(body.prompt ?? "").trim();
  if (!prompt || prompt.length > MAX_PROMPT_LENGTH) {
    return json({ error: "prompt_required_or_too_long", max_length: MAX_PROMPT_LENGTH }, 400);
  }
  if (!providerUrl || !providerApiKey) return json({ error: "image_provider_not_configured" }, 503);
  try {
    if (new URL(providerUrl).protocol !== "https:") return json({ error: "secure_provider_url_required" }, 503);
  } catch {
    return json({ error: "invalid_provider_url" }, 503);
  }

  const admin = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  let profileId: string;
  try {
    profileId = await resolveProfileId(admin, req.headers.get("x-vibesync-session")?.trim() ?? "");
  } catch {
    return json({ error: "invalid_vibesync_session" }, 401);
  }

  const { data: consumed, error: quotaError } = await admin.rpc("consume_chat_ai_image_quota", {
    p_profile_id: profileId,
    p_daily_limit: DAILY_LIMIT,
  });
  if (quotaError) {
    console.error("AI preview quota could not be checked:", quotaError.message);
    return json({ error: "quota_service_unavailable" }, 503);
  }
  if (consumed === null || consumed === undefined) {
    return json({ error: "daily_quota_exhausted", daily_limit: DAILY_LIMIT }, 429);
  }

  let providerResponse: Response;
  try {
    providerResponse = await fetch(providerUrl, {
      method: "POST",
      headers: {
        authorization: "Bearer " + providerApiKey,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        prompt,
        model: providerModel || undefined,
        n: 4,
        width: 1024,
        height: 1024,
      }),
    });
  } catch {
    return json({ error: "image_provider_unreachable" }, 502);
  }
  if (!providerResponse.ok) {
    console.error("AI image provider returned HTTP", providerResponse.status);
    return json({ error: "image_provider_failed" }, 502);
  }

  let providerPayload: Record<string, unknown>;
  try {
    const parsed = await providerResponse.json();
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return json({ error: "invalid_provider_response" }, 502);
    providerPayload = parsed as Record<string, unknown>;
  } catch {
    return json({ error: "invalid_provider_response" }, 502);
  }
  const previews = extractPreviewUrls(providerPayload);
  if (previews.length < 2) return json({ error: "insufficient_preview_options" }, 502);

  return json({
    original_prompt: prompt,
    preview_urls: previews,
    daily_limit: DAILY_LIMIT,
    used_today: Number(consumed),
    remaining_today: Math.max(0, DAILY_LIMIT - Number(consumed)),
  });
});
