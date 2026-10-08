import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const TRUECALLER_CLIENT_ID = "qkpfffgkiebypf8hvifhkkknj2msgud5hfjqg_ti1wu";
const TRUECALLER_TOKEN_ENDPOINT = "https://oauth-account-noneu.truecaller.com/v1/token";
const TRUECALLER_USERINFO_ENDPOINT = "https://oauth-account-noneu.truecaller.com/v1/userinfo";
const requestStateTtlSeconds = 600;
const sessionTtlSeconds = 30 * 24 * 60 * 60;

function getSecretKey(): string {
  const raw = Deno.env.get("SUPABASE_SECRET_KEYS") ?? "";
  try {
    const keys = JSON.parse(raw);
    const defaultKey = typeof keys?.default === "string" ? keys.default : "";
    if (defaultKey) return defaultKey;
  } catch {
    // Fall back to the legacy secret name while projects migrate keys.
  }
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
}

const serviceRoleKey = getSecretKey();

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function normalizePhone(value: unknown): { e164: string; last10: string } {
  const digits = String(value ?? "").replace(/[^0-9]/g, "");
  if (digits.length < 10 || digits.length > 15) throw new Error("truecaller_phone_invalid");
  return { e164: "+" + digits, last10: digits.slice(-10) };
}

function sha256Hex(value: string): Promise<string> {
  return crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  ).then((bytes) =>
    Array.from(new Uint8Array(bytes))
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("")
  );
}

function randomHex(byteCount: number): string {
  const bytes = new Uint8Array(byteCount);
  crypto.getRandomValues(bytes);
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function exchangeAuthorizationCode(authorizationCode: string, codeVerifier: string) {
  const response = await fetch(TRUECALLER_TOKEN_ENDPOINT, {
    method: "POST",
    headers: {
      "content-type": "application/x-www-form-urlencoded",
      accept: "application/json",
    },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      client_id: TRUECALLER_CLIENT_ID,
      code: authorizationCode,
      code_verifier: codeVerifier,
    }).toString(),
  });
  const bodyText = await response.text();
  if (!response.ok) {
    throw new Error(
      "truecaller_token_exchange_failed_" + response.status + ":" + bodyText.slice(0, 200),
    );
  }
  const token = JSON.parse(bodyText);
  const accessToken = String(token?.access_token ?? "");
  if (!accessToken) throw new Error("truecaller_access_token_missing");
  return accessToken;
}

async function fetchTruecallerUserInfo(accessToken: string) {
  const response = await fetch(TRUECALLER_USERINFO_ENDPOINT, {
    headers: {
      accept: "application/json",
      authorization: "Bearer " + accessToken,
      "cache-control": "no-cache",
    },
  });
  const bodyText = await response.text();
  if (!response.ok) {
    throw new Error(
      "truecaller_userinfo_failed_" + response.status + ":" + bodyText.slice(0, 200),
    );
  }
  const profile = JSON.parse(bodyText);
  if (profile?.phone_number_verified !== true) {
    throw new Error("truecaller_phone_not_verified");
  }
  const phone = normalizePhone(profile.phone_number);
  return {
    phone: phone.e164,
    last10: phone.last10,
    truecallerSubject: String(profile.sub ?? "").slice(0, 255),
    firstName: String(profile.given_name ?? "").slice(0, 80),
    lastName: String(profile.family_name ?? "").slice(0, 80),
    avatarUrl: String(profile.picture ?? "").slice(0, 2048),
  };
}

async function consumeRequestState(admin: ReturnType<typeof createClient>, state: string) {
  const cutoff = new Date(Date.now() - requestStateTtlSeconds * 1000).toISOString();
  await admin.from("truecaller_login_nonces").delete().lt("used_at", cutoff);
  const { error } = await admin.from("truecaller_login_nonces").insert({ request_nonce: state });
  if (error) throw new Error("truecaller_response_already_used");
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!supabaseUrl || !serviceRoleKey) {
    return json({ error: "server_configuration_incomplete" }, 503);
  }

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  const authorizationCode = String(body?.authorization_code ?? "");
  const codeVerifier = String(body?.code_verifier ?? "");
  const state = String(body?.state ?? "");
  if (!authorizationCode || !codeVerifier || !state) {
    return json({ error: "invalid_truecaller_oauth_request" }, 400);
  }
  if (state.length < 20 || state.length > 256) {
    return json({ error: "invalid_truecaller_state" }, 400);
  }
  if (codeVerifier.length < 43 || codeVerifier.length > 128) {
    return json({ error: "invalid_truecaller_code_verifier" }, 400);
  }

  const admin = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  try {
    const accessToken = await exchangeAuthorizationCode(authorizationCode, codeVerifier);
    const profile = await fetchTruecallerUserInfo(accessToken);
    await consumeRequestState(admin, state);

    const fullPhoneHash = await sha256Hex(profile.last10);
    const profileId = fullPhoneHash.slice(0, 16);
    const sessionToken = randomHex(32);
    const sessionHash = await sha256Hex(sessionToken);
    const expiresAt = new Date(Date.now() + sessionTtlSeconds * 1000).toISOString();

    const profileUpsert = await admin.from("profiles").upsert({
      id: profileId,
      name: [profile.firstName, profile.lastName].filter(Boolean).join(" ").slice(0, 60) || "VibeSync Member",
      avatar_url: profile.avatarUrl,
      is_verified: true,
      is_deleted: false,
      account_status: "ACTIVE",
      updated_at: Date.now(),
    }, { onConflict: "id" });
    if (profileUpsert.error) throw new Error("profile_provision_failed");

    const phoneSyncUpsert = await admin.from("phone_sync").upsert({
      user_id: profileId,
      phone_hash: "\\x" + fullPhoneHash,
    }, { onConflict: "user_id,phone_hash" });
    if (phoneSyncUpsert.error) throw new Error("phone_sync_provision_failed");

    // One active device session per VibeSync identity keeps the session model simple
    // and makes logout/re-authentication deterministic.
    await admin.from("vibesync_sessions").delete().eq("profile_id", profileId);
    const sessionInsert = await admin.from("vibesync_sessions").insert({
      session_hash: sessionHash,
      profile_id: profileId,
      expires_at: expiresAt,
      last_seen_at: new Date().toISOString(),
    });
    if (sessionInsert.error) throw new Error("session_provision_failed");

    return json({
      verified: true,
      phone: profile.phone,
      profile_id: profileId,
      session_token: sessionToken,
      expires_at: Math.floor(new Date(expiresAt).getTime() / 1000),
      first_name: profile.firstName,
      last_name: profile.lastName,
      avatar_url: profile.avatarUrl,
    });
  } catch (error) {
    return json({
      error: "truecaller_verification_failed",
      detail: String((error as Error)?.message ?? error).slice(0, 300),
    }, 401);
  }
});
