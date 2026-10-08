import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

type ServiceAccount = {
  client_email: string;
  private_key: string;
  project_id: string;
  token_uri?: string;
};

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
const firebaseServiceAccountJson = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON") ?? "";

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

function base64Url(input: string | Uint8Array): string {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : input;
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function pemToDer(pem: string): ArrayBuffer {
  const clean = pem.replace(/-----BEGIN PRIVATE KEY-----/g, "")
    .replace(/-----END PRIVATE KEY-----/g, "")
    .replace(/\s+/g, "");
  const binary = atob(clean);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

async function createGoogleAccessToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = base64Url(JSON.stringify({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: sa.token_uri ?? "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const unsigned = header + "." + claim;
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(sa.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsigned),
  );
  const assertion = unsigned + "." + base64Url(new Uint8Array(signature));
  const tokenResponse = await fetch(
    sa.token_uri ?? "https://oauth2.googleapis.com/token",
    {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion,
      }),
    },
  );
  if (!tokenResponse.ok) {
    throw new Error("Google OAuth token exchange failed: HTTP " + tokenResponse.status);
  }
  const tokenJson = await tokenResponse.json();
  const accessToken = String(tokenJson.access_token ?? "");
  if (!accessToken) throw new Error("Google OAuth response did not contain access_token");
  return accessToken;
}

async function sendFcm(sa: ServiceAccount, token: string, data: Record<string, string>) {
  const accessToken = await createGoogleAccessToken(sa);
  const response = await fetch(
    "https://fcm.googleapis.com/v1/projects/" + encodeURIComponent(sa.project_id) + "/messages:send",
    {
      method: "POST",
      headers: {
        authorization: "Bearer " + accessToken,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        message: {
          token,
          data,
          android: { priority: "HIGH", ttl: "86400s" },
        },
      }),
    },
  );
  const text = await response.text();
  if (!response.ok) {
    throw new Error("FCM send failed: HTTP " + response.status + " " + text.slice(0, 500));
  }
  return text;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!supabaseUrl || !serviceRoleKey) return json({ error: "server_configuration_incomplete" }, 503);

  const sessionToken = req.headers.get("x-vibesync-session")?.trim() ?? "";
  if (!sessionToken) return json({ error: "vibesync_session_required" }, 401);

  let body: { message_id?: string };
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  const messageId = String(body.message_id ?? "").trim();
  if (!messageId) return json({ error: "message_id_required" }, 400);

  if (!firebaseServiceAccountJson) {
    return json({
      error: "fcm_server_credential_not_configured",
      message: "Set FIREBASE_SERVICE_ACCOUNT_JSON as a Supabase Edge Function secret.",
    }, 503);
  }

  let sa: ServiceAccount;
  try {
    sa = JSON.parse(firebaseServiceAccountJson) as ServiceAccount;
    if (!sa.client_email || !sa.private_key || !sa.project_id) throw new Error("invalid service account");
  } catch {
    return json({ error: "invalid_fcm_server_credential" }, 503);
  }

  const adminClient = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  try {
    const profileId = await resolveProfileId(adminClient, sessionToken);

    const { data: message, error: messageError } = await adminClient
      .from("chat_messages")
      .select("message_id,match_id,sender_id,receiver_id,ciphertext,is_encrypted,media_type")
      .eq("message_id", messageId)
      .maybeSingle();

    if (messageError || !message) return json({ error: "message_not_found" }, 404);
    if (message.sender_id !== profileId) return json({ error: "sender_mismatch" }, 403);
    if (!message.is_encrypted || typeof message.ciphertext !== "string" || !message.ciphertext.startsWith("TINK_ENC:")) {
      return json({ error: "encrypted_payload_required" }, 422);
    }
    if (!message.receiver_id) return json({ error: "recipient_unresolved" }, 422);

    const { data: tokens, error: tokenError } = await adminClient
      .from("device_tokens")
      .select("id,fcm_token")
      .eq("profile_id", message.receiver_id);

    if (tokenError) return json({ error: "token_lookup_failed" }, 500);
    if (!tokens?.length) return json({ ok: true, delivered_to_devices: 0 });

    let delivered = 0;
    const failedTokenIds: string[] = [];
    for (const row of tokens) {
      try {
        await sendFcm(sa, row.fcm_token, {
          type: "NEW_E2EE_MESSAGE",
          message_id: String(message.message_id),
          match_id: String(message.match_id ?? ""),
          sender_id: profileId,
          receiver_id: String(message.receiver_id),
          ciphertext: message.ciphertext,
          media_type: String(message.media_type ?? "TEXT"),
        });
        delivered++;
      } catch (e) {
        const detail = String((e as Error)?.message ?? e);
        if (/registration-token-not-registered|invalid-registration-token|UNREGISTERED/i.test(detail)) {
          failedTokenIds.push(row.id);
        }
      }
    }

    if (failedTokenIds.length) {
      await adminClient.from("device_tokens").delete().in("id", failedTokenIds);
    }

    await adminClient.from("chat_messages")
      .update({
        dispatch_status: delivered > 0 ? "SENT" : "PENDING",
        dispatch_attempts: 1,
      })
      .eq("message_id", messageId)
      .eq("sender_id", profileId);

    return json({
      ok: true,
      message_id: messageId,
      delivered_to_devices: delivered,
      stale_tokens_removed: failedTokenIds.length,
    });
  } catch (error) {
    return json({
      error: "fcm_dispatch_failed",
      detail: String((error as Error)?.message ?? error).slice(0, 300),
    }, 401);
  }
});
