import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const serviceRoleKey = (() => {
  try {
    const parsed = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "");
    if (typeof parsed?.default === "string" && parsed.default) return parsed.default;
  } catch {
    // Compatibility while the project migrates to the secret-key format.
  }
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
})();

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

async function sha256Hex(value: string): Promise<string> {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function resolveProfileId(admin: ReturnType<typeof createClient>, sessionToken: string): Promise<string> {
  if (!sessionToken) throw new Error("vibesync_session_required");
  const sessionHash = await sha256Hex(sessionToken);
  const { data, error } = await admin
    .from("vibesync_sessions")
    .select("profile_id,expires_at")
    .eq("session_hash", sessionHash)
    .gt("expires_at", new Date().toISOString())
    .maybeSingle();
  if (error || !data?.profile_id) throw new Error("invalid_vibesync_session");
  return String(data.profile_id);
}

function parseStorageObject(mediaUrl: string): { bucket: string; path: string } | null {
  let target: URL;
  let root: URL;
  try {
    target = new URL(mediaUrl);
    root = new URL(supabaseUrl);
  } catch {
    return null;
  }
  if (target.protocol !== "https:" || target.host !== root.host) return null;
  const rawSegments = target.pathname.split("/").filter(Boolean);
  let segments: string[];
  try {
    segments = rawSegments.map((part) => decodeURIComponent(part));
  } catch {
    return null;
  }
  if (segments.length < 5 ||
      segments[0] !== "storage" || segments[1] !== "v1" || segments[2] !== "object") return null;

  let index = 3;
  if (["public", "sign", "authenticated"].includes(segments[index])) index++;
  const bucket = segments[index] ?? "";
  const objectPath = segments.slice(index + 1).join("/");
  if (!bucket || !objectPath || objectPath.split("/").some((part) => part === ".." || part === ".")) return null;
  return { bucket, path: objectPath };
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!supabaseUrl || !serviceRoleKey) return json({ error: "server_configuration_incomplete" }, 503);

  let body: Record<string, unknown>;
  try {
    const parsed = await req.json();
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return json({ error: "invalid_json_object" }, 400);
    body = parsed as Record<string, unknown>;
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  const messageId = String(body.message_id ?? "").trim();
  if (!messageId) return json({ error: "message_id_required" }, 400);

  const admin = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const suppliedWebhookSecret = req.headers.get("x-webhook-secret")?.trim() ?? "";
  let trustedCleanupRequest = false;
  let requestingProfileId = "";
  if (suppliedWebhookSecret) {
    const { data: authorized, error } = await admin.rpc(
      "verify_vibesync_fcm_webhook_secret",
      { p_supplied_secret: suppliedWebhookSecret },
    );
    if (error || authorized !== true) return json({ error: "unauthorized_webhook" }, 401);
    trustedCleanupRequest = true;
    if (String(body.mode ?? "") !== "retry_media") return json({ error: "invalid_cleanup_mode" }, 400);
  } else {
    const session = req.headers.get("x-vibesync-session")?.trim() ?? "";
    try {
      requestingProfileId = await resolveProfileId(admin, session);
    } catch {
      return json({ error: "invalid_vibesync_session" }, 401);
    }
  }

  const { data: participant, error: participantError } = await admin
    .from("chat_message_receipt_participants")
    .select("message_id,match_id,receiver_id,created_at,media_url")
    .eq("message_id", messageId)
    .maybeSingle();
  if (participantError || !participant) return json({ error: "message_mapping_not_found" }, 404);

  const { data: receipts, error: receiptError } = await admin
    .from("chat_message_receipts")
    .select("status")
    .eq("message_id", messageId);
  if (receiptError) return json({ error: "receipt_lookup_failed" }, 500);
  const hasDeliveredReceipt = (receipts ?? []).some((receipt) =>
    ["DELIVERED", "READ"].includes(String(receipt.status ?? "").toUpperCase())
  );
  const isOlderThanTtl = Date.parse(String(participant.created_at ?? "")) < Date.now() - 30 * 24 * 60 * 60 * 1000;

  if (trustedCleanupRequest) {
    if (!hasDeliveredReceipt && !isOlderThanTtl) {
      return json({ error: "message_not_eligible_for_media_cleanup" }, 403);
    }
  } else {
    if (String(participant.receiver_id ?? "") !== requestingProfileId) {
      return json({ error: "message_not_addressed_to_current_profile" }, 403);
    }
    if (!hasDeliveredReceipt) return json({ error: "delivery_receipt_required" }, 403);
  }

  const mediaUrl = String(participant.media_url ?? "").trim();
  if (!mediaUrl) return json({ ok: true, skipped: "no_media", message_id: messageId });

  const object = parseStorageObject(mediaUrl);
  if (!object) {
    // Do not attempt DELETE against an arbitrary host or unknown bucket/path shape.
    return json({ error: "unsupported_storage_object_url", message_id: messageId }, 422);
  }

  const { error: removalError } = await admin.storage.from(object.bucket).remove([object.path]);
  if (removalError) {
    console.error("Storage deletion did not complete for message:", messageId, removalError.message);
    return json({ error: "storage_delete_failed", message_id: messageId }, 502);
  }

  const { error: clearError } = await admin
    .from("chat_message_receipt_participants")
    .update({ media_url: "" })
    .eq("message_id", messageId);
  if (clearError) {
    console.error("Storage object removed but media mapping cleanup failed for message:", messageId);
    return json({ error: "storage_deleted_mapping_cleanup_failed", message_id: messageId }, 502);
  }

  return json({ ok: true, message_id: messageId, match_id: participant.match_id, storage_deleted: true });
});
