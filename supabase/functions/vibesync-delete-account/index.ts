import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const serviceRoleKey =
  (() => {
    try {
      const parsed = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "");
      if (typeof parsed?.default === "string" && parsed.default) return parsed.default;
    } catch {
      // Compatibility while projects migrate to the secret-key format.
    }
    return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  })();

async function sha256Hex(value: string): Promise<string> {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!supabaseUrl || !serviceRoleKey) return json({ error: "server_configuration_incomplete" }, 503);

  const sessionToken = req.headers.get("x-vibesync-session")?.trim() ?? "";
  if (!sessionToken) return json({ error: "vibesync_session_required" }, 401);

  const adminClient = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  try {
    const sessionHash = await sha256Hex(sessionToken);
    const { data: session, error: sessionError } = await adminClient
      .from("vibesync_sessions")
      .select("profile_id,expires_at")
      .eq("session_hash", sessionHash)
      .gt("expires_at", new Date().toISOString())
      .maybeSingle();

    if (sessionError || !session?.profile_id) return json({ error: "invalid_vibesync_session" }, 401);

    const profileId = String(session.profile_id);

    await adminClient.from("device_tokens").delete().eq("profile_id", profileId);
    await adminClient.from("phone_sync").delete().eq("user_id", profileId);
    await adminClient.from("chat_messages").delete().eq("sender_id", profileId);
    await adminClient.from("chat_messages").delete().eq("receiver_id", profileId);
    await adminClient.from("registered_accounts").delete().eq("id", profileId);
    await adminClient.from("vibesync_sessions").delete().eq("profile_id", profileId);

    const { error: profileError } = await adminClient
      .from("profiles")
      .delete()
      .eq("id", profileId);
    if (profileError) throw profileError;

    return json({ ok: true, deleted_profile_id: profileId });
  } catch (error) {
    return json({
      error: "account_deletion_failed",
      detail: String((error as Error)?.message ?? error).slice(0, 300),
    }, 500);
  }
});
