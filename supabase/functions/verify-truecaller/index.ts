import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";
import * as djwt from "https://deno.land/x/djwt@v2.8/mod.ts";

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const TRUECALLER_CLIENT_ID = "qkpfffgkiebypf8hvifhkkknj2msgud5hfjqg_ti1wu";
const TRUECALLER_PARTNER_KEY = Deno.env.get("TRUECALLER_PARTNER_KEY") ?? "";
const TRUECALLER_TOKEN_ENDPOINT = "https://oauth-account-noneu.truecaller.com/v1/token";
const TRUECALLER_USERINFO_ENDPOINT = "https://oauth-account-noneu.truecaller.com/v1/userinfo";
const REQUEST_STATE_TTL_SECONDS = 600;
const SESSION_TTL_SECONDS = 30 * 24 * 60 * 60;

const corsHeaders = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "authorization, x-client-info, apikey, content-type",
  "access-control-allow-methods": "POST, OPTIONS",
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};

class HttpError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = "HttpError";
  }
}

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: corsHeaders });
}

function getSupabaseSecretKey(): string {
  const raw = Deno.env.get("SUPABASE_SECRET_KEYS") ?? "";
  try {
    const keys = JSON.parse(raw);
    const defaultKey = typeof keys?.default === "string" ? keys.default : "";
    if (defaultKey) return defaultKey;
  } catch {
    // Support legacy service-role key while projects migrate to the new secret keys.
  }
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
}

function normalizePhone(value: unknown): { e164: string; last10: string } {
  const digits = String(value ?? "").replace(/[^0-9]/g, "");
  if (digits.length < 10 || digits.length > 15) {
    throw new HttpError("truecaller_phone_invalid", 401);
  }
  return { e164: "+" + digits, last10: digits.slice(-10) };
}

async function sha256Hex(value: string): Promise<string> {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function randomHex(byteCount: number): string {
  const bytes = new Uint8Array(byteCount);
  crypto.getRandomValues(bytes);
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function exchangeAuthorizationCode(code: string, codeVerifier: string): Promise<string> {
  const response = await fetch(TRUECALLER_TOKEN_ENDPOINT, {
    method: "POST",
    headers: {
      "content-type": "application/x-www-form-urlencoded",
      accept: "application/json",
    },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      client_id: TRUECALLER_CLIENT_ID,
      client_secret: TRUECALLER_PARTNER_KEY,
      code,
      code_verifier: codeVerifier,
    }).toString(),
  });
  const responseText = await response.text();
  if (!response.ok) {
    throw new HttpError(
      "truecaller_token_exchange_failed_http_" + response.status + ": " + responseText.slice(0, 300),
      502,
    );
  }
  let token: Record<string, unknown>;
  try {
    token = JSON.parse(responseText);
  } catch {
    throw new HttpError("truecaller_token_response_invalid_json", 502);
  }
  const accessToken = String(token.access_token ?? "");
  if (!accessToken) throw new HttpError("truecaller_access_token_missing", 502);
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
  const responseText = await response.text();
  if (!response.ok) {
    throw new HttpError(
      "truecaller_userinfo_failed_http_" + response.status + ": " + responseText.slice(0, 300),
      502,
    );
  }
  let profile: Record<string, unknown>;
  try {
    profile = JSON.parse(responseText);
  } catch {
    throw new HttpError("truecaller_userinfo_invalid_json", 502);
  }
  if (profile.phone_number_verified !== true) {
    throw new HttpError("truecaller_phone_not_verified", 401);
  }
  const phone = normalizePhone(profile.phone_number);
  return {
    phone: phone.e164,
    last10: phone.last10,
    firstName: String(profile.given_name ?? "").slice(0, 80),
    lastName: String(profile.family_name ?? "").slice(0, 80),
    avatarUrl: String(profile.picture ?? "").slice(0, 2048),
  };
}

async function consumeRequestState(admin: ReturnType<typeof createClient>, state: string) {
  const cutoff = new Date(Date.now() - REQUEST_STATE_TTL_SECONDS * 1000).toISOString();
  // Cleanup is opportunistic; failure to clean old rows should not affect authentication.
  await admin.from("truecaller_login_nonces").delete().lt("used_at", cutoff);
  const { error } = await admin.from("truecaller_login_nonces").insert({ request_nonce: state });
  if (error) throw new HttpError("truecaller_response_already_used_or_state_store_failed", 409);
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { status: 200, headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  const serviceRoleKey = getSupabaseSecretKey();
  const jwtSecret = Deno.env.get("APP_JWT_SECRET") || Deno.env.get("JWT_SECRET") || "";
  if (!supabaseUrl || !serviceRoleKey || !jwtSecret || !TRUECALLER_PARTNER_KEY) {
    return json({
      error: "server_configuration_incomplete",
      detail: "Required Supabase, APP_JWT_SECRET, or TRUECALLER_PARTNER_KEY configuration is missing.",
    }, 503);
  }

  let body: Record<string, unknown>;
  try {
    const parsed = await req.json();
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return json({ error: "invalid_json_object" }, 400);
    }
    body = parsed as Record<string, unknown>;
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  // "code" is the canonical Android contract. Accept the older spelling during transition.
  const authorizationCode = String(body.code ?? body.authorization_code ?? "");
  const codeVerifier = String(body.code_verifier ?? "");
  const state = String(body.state ?? "");
  if (!authorizationCode || !codeVerifier || !state) {
    return json({
      error: "invalid_truecaller_oauth_request",
      detail: "Required fields: code, code_verifier, state.",
    }, 400);
  }
  if (authorizationCode.length > 4096 || state.length < 20 || state.length > 256) {
    return json({ error: "invalid_truecaller_code_or_state" }, 400);
  }
  if (codeVerifier.length < 43 || codeVerifier.length > 128) {
    return json({ error: "invalid_truecaller_code_verifier" }, 400);
  }

  const admin = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  try {
    // Correlate and consume state before exchanging the single-use authorization code.
    await consumeRequestState(admin, state);
    const accessToken = await exchangeAuthorizationCode(authorizationCode, codeVerifier);
    const identity = await fetchTruecallerUserInfo(accessToken);

    const fullPhoneHash = await sha256Hex(identity.last10);
    const profileId = fullPhoneHash.slice(0, 16);
    const sessionToken = randomHex(32);
    const sessionHash = await sha256Hex(sessionToken);
    const jwtId = crypto.randomUUID();
    const nowSec = Math.floor(Date.now() / 1000);
    const expiresAtSec = nowSec + SESSION_TTL_SECONDS;
    const expiresAt = new Date(expiresAtSec * 1000).toISOString();
    const displayName = [identity.firstName, identity.lastName].filter(Boolean).join(" ").slice(0, 60) || "VibeSync Member";

    const profileUpsert = await admin.from("profiles").upsert({
      id: profileId,
      name: displayName,
      avatar_url: identity.avatarUrl,
      is_verified: true,
      is_deleted: false,
      account_status: "ACTIVE",
      updated_at: Date.now(),
    }, { onConflict: "id" });
    if (profileUpsert.error) {
      throw new HttpError("profile_provision_failed: " + profileUpsert.error.message.slice(0, 200), 500);
    }

    const phoneSyncUpsert = await admin.from("phone_sync").upsert({
      user_id: profileId,
      phone_hash: "\\x" + fullPhoneHash,
    }, { onConflict: "user_id,phone_hash" });
    if (phoneSyncUpsert.error) {
      throw new HttpError("phone_sync_provision_failed: " + phoneSyncUpsert.error.message.slice(0, 200), 500);
    }

    // Invalidate prior device sessions for this identity, matching the current VibeSync session model.
    const deleteSessions = await admin.from("vibesync_sessions").delete().eq("profile_id", profileId);
    if (deleteSessions.error) {
      throw new HttpError("previous_session_cleanup_failed: " + deleteSessions.error.message.slice(0, 200), 500);
    }

    const sessionInsert = await admin.from("vibesync_sessions").insert({
      session_hash: sessionHash,
      profile_id: profileId,
      jwt_id: jwtId,
      expires_at: expiresAt,
      last_seen_at: new Date().toISOString(),
    });
    if (sessionInsert.error) {
      throw new HttpError("session_provision_failed: " + sessionInsert.error.message.slice(0, 200), 500);
    }

    const signingKey = await crypto.subtle.importKey(
      "raw",
      new TextEncoder().encode(jwtSecret),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign"],
    );
    const jwt = await djwt.create(
      { alg: "HS256", typ: "JWT" },
      {
        sub: profileId,
        aud: "authenticated",
        role: "authenticated",
        phone: identity.phone,
        jti: jwtId,
        profile_id: profileId,
        iss: "supabase",
        iat: nowSec,
        exp: expiresAtSec,
      },
      signingKey,
    );

    const { data: superAdminRow, error: superAdminLookupError } = await admin
      .from("super_admins")
      .select("profile_id")
      .eq("profile_id", profileId)
      .maybeSingle();
    if (superAdminLookupError) {
      // Fail closed for elevation without breaking standard Truecaller sign-in if migration is absent.
      console.warn("super_admins lookup failed closed", superAdminLookupError.message);
    }
    const isSuperAdmin = !superAdminLookupError && superAdminRow?.profile_id === profileId;

    return json({
      verified: true,
      is_super_admin: isSuperAdmin,
      phone: identity.phone,
      profile_id: profileId,
      // Canonical VibeSync session field; keep access_token for older clients.
      access_jwt: jwt,
      access_token: jwt,
      token_type: "Bearer",
      expires_in: SESSION_TTL_SECONDS,
      session_token: sessionToken,
      session_jti: jwtId,
      expires_at: expiresAtSec,
      first_name: identity.firstName,
      last_name: identity.lastName,
      avatar_url: identity.avatarUrl,
    });
  } catch (error) {
    const status = error instanceof HttpError ? error.status : 500;
    const detail = error instanceof Error ? error.message : String(error);
    console.error("verify-truecaller failed", { status, detail: detail.slice(0, 400) });
    return json({
      error: "truecaller_verification_failed",
      detail: detail.slice(0, 400),
    }, status);
  }
});
