import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";
import * as jose from "jsr:@panva/jose@6";

const supabaseUrl = (Deno.env.get("SUPABASE_URL") ?? "").replace(/\/$/, "");
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? Deno.env.get("SUPABASE_SECRET_KEY") ?? "";
const jwtSecret = Deno.env.get("APP_JWT_SECRET") || Deno.env.get("JWT_SECRET") || "";
const truecallerPartnerKey = Deno.env.get("TRUECALLER_PARTNER_KEY") ?? "";
const truecallerClientId = Deno.env.get("TRUECALLER_CLIENT_ID") ??
  "qkpfffgkiebypf8hvifhkkknj2msgud5hfjqg_ti1wu";

const TRUECALLER_TOKEN_ENDPOINT = "https://oauth-account-noneu.truecaller.com/v1/token";
const TRUECALLER_USERINFO_ENDPOINT = "https://oauth-account-noneu.truecaller.com/v1/userinfo";
const TRUECALLER_LEGACY_TOKEN_ENDPOINT =
  "https://sdk-otp-verification-noneu.truecaller.com/v1/otp/installation/phoneNumberDetail/";
const NONCE_TTL_SECONDS = 24 * 60 * 60;
const SESSION_TTL_SECONDS = 30 * 24 * 60 * 60;

const corsHeaders = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "POST, OPTIONS",
  "access-control-allow-headers": "authorization, apikey, content-type, x-client-info",
  "access-control-max-age": "86400",
  "cache-control": "no-store",
  "content-type": "application/json; charset=utf-8",
  "vary": "Origin",
};

class VerificationError extends Error {
  constructor(message: string, readonly status = 401) {
    super(message);
  }
}

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: corsHeaders });
}

function normalizePhone(value: unknown, countryCode = ""): { e164: string; last10: string } {
  let digits = String(value ?? "").replace(/[^0-9]/g, "");
  if (digits.length === 10 && countryCode.toUpperCase() === "IN") digits = "91" + digits;
  if (digits.length < 10 || digits.length > 15) {
    throw new VerificationError("truecaller_phone_invalid", 401);
  }
  return { e164: "+" + digits, last10: digits.slice(-10) };
}

async function sha256Hex(value: string): Promise<string> {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

function randomHex(byteCount: number): string {
  const bytes = new Uint8Array(byteCount);
  crypto.getRandomValues(bytes);
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** OAuth SDK flow: exchange the one-time code and PKCE verifier on the server. */
async function exchangeAuthorizationCode(code: string, codeVerifier: string): Promise<string> {
  const response = await fetch(TRUECALLER_TOKEN_ENDPOINT, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded", accept: "application/json" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      client_id: truecallerClientId,
      code,
      code_verifier: codeVerifier,
    }).toString(),
    signal: AbortSignal.timeout(12_000),
  });
  if (!response.ok) throw new VerificationError("truecaller_token_exchange_failed", 401);
  const token = await response.json();
  const accessToken = String(token?.access_token ?? "");
  if (!accessToken || accessToken.length > 8192) {
    throw new VerificationError("truecaller_access_token_missing", 401);
  }
  return accessToken;
}

/** OAuth userinfo is the authority for phone, name and verification claims. */
async function fetchTruecallerUserInfo(accessToken: string) {
  const response = await fetch(TRUECALLER_USERINFO_ENDPOINT, {
    headers: {
      accept: "application/json",
      authorization: "Bearer " + accessToken,
      "cache-control": "no-cache",
    },
    signal: AbortSignal.timeout(12_000),
  });
  if (!response.ok) throw new VerificationError("truecaller_userinfo_verification_failed", 401);
  const data = await response.json();
  if (data?.phone_number_verified !== true) {
    throw new VerificationError("truecaller_phone_not_verified", 401);
  }
  const phone = normalizePhone(data.phone_number, String(data.phone_number_country_code ?? ""));
  return {
    phone: phone.e164,
    last10: phone.last10,
    firstName: String(data.given_name ?? "").slice(0, 80),
    lastName: String(data.family_name ?? "").slice(0, 80),
    avatarUrl: String(data.picture ?? "").slice(0, 2048),
  };
}

/**
 * Legacy SDK access tokens are verified with the fixed phone-number-detail endpoint.
 * TRUECALLER_PARTNER_KEY never leaves this function and is never used to sign the app JWT.
 */
async function verifyLegacyPartnerToken(accessToken: string) {
  if (!truecallerPartnerKey) {
    throw new VerificationError("truecaller_partner_key_not_configured", 503);
  }
  const response = await fetch(
    TRUECALLER_LEGACY_TOKEN_ENDPOINT + encodeURIComponent(accessToken),
    {
      method: "GET",
      headers: { appKey: truecallerPartnerKey, accept: "application/json", "cache-control": "no-cache" },
      signal: AbortSignal.timeout(12_000),
    },
  );
  if (!response.ok) throw new VerificationError("truecaller_legacy_token_invalid", 401);
  const data = await response.json();
  const phone = normalizePhone(data?.phoneNumber, String(data?.countryCode ?? ""));
  return { phone: phone.e164, last10: phone.last10, firstName: "", lastName: "", avatarUrl: "" };
}

async function consumeRequestProof(
  admin: ReturnType<typeof createClient>,
  state: string,
  providerProof: string,
) {
  const cutoff = new Date(Date.now() - NONCE_TTL_SECONDS * 1000).toISOString();
  await admin.from("truecaller_login_nonces").delete().lt("used_at", cutoff);
  const { error } = await admin.from("truecaller_login_nonces").insert([
    { request_nonce: "state:" + await sha256Hex(state) },
    { request_nonce: "proof:" + await sha256Hex(providerProof) },
  ]);
  if (error) throw new VerificationError("truecaller_response_already_used", 409);
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { status: 204, headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  if (!supabaseUrl || !serviceRoleKey || jwtSecret.trim().length < 32) {
    console.error("verify-truecaller server configuration is incomplete");
    return json({ error: "server_configuration_incomplete" }, 503);
  }

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  // Caller-controlled endpoint URLs and caller-supplied identity fields are deliberately ignored.
  const code = String(body?.authorization_code ?? "").trim();
  const verifier = String(body?.code_verifier ?? "").trim();
  const suppliedAccessToken = String(body?.access_token ?? "").trim();
  const state = String(body?.state ?? "").trim();

  if (state.length < 20 || state.length > 256) return json({ error: "invalid_truecaller_state" }, 400);
  if (suppliedAccessToken.length > 8192 || code.length > 4096) {
    return json({ error: "invalid_truecaller_credential" }, 400);
  }
  if (!suppliedAccessToken && (!code || verifier.length < 43 || verifier.length > 128)) {
    return json({ error: "invalid_truecaller_oauth_request" }, 400);
  }

  const admin = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  try {
    await consumeRequestProof(admin, state, suppliedAccessToken || code);
    const verified = suppliedAccessToken
      ? await verifyLegacyPartnerToken(suppliedAccessToken)
      : await fetchTruecallerUserInfo(await exchangeAuthorizationCode(code, verifier));

    const phoneHash = await sha256Hex(verified.last10);
    const profileId = phoneHash.slice(0, 16);
    const displayName = [verified.firstName, verified.lastName]
      .filter(Boolean).join(" ").slice(0, 60) || "VibeSync Member";

    const profileUpsert = await admin.from("profiles").upsert({
      id: profileId,
      name: displayName,
      avatar_url: verified.avatarUrl,
      is_verified: true,
      is_deleted: false,
      account_status: "ACTIVE",
      updated_at: Date.now(),
    }, { onConflict: "id" });
    if (profileUpsert.error) {
      console.error("verify-truecaller profile provisioning failed:", profileUpsert.error.message);
      throw new Error("profile_provision_failed");
    }

    const accountUpsert = await admin.from("registered_accounts").upsert({
      id: profileId,
      is_verified: true,
      account_status: "ACTIVE",
    }, { onConflict: "id" });
    if (accountUpsert.error) {
      console.error("verify-truecaller account provisioning failed:", accountUpsert.error.message);
      throw new Error("registered_account_provision_failed");
    }

    const phoneSyncUpsert = await admin.from("phone_sync").upsert({
      user_id: profileId,
      phone_hash: "\\x" + phoneHash,
    }, { onConflict: "user_id,phone_hash" });
    if (phoneSyncUpsert.error) {
      console.error("verify-truecaller phone-hash provisioning failed:", phoneSyncUpsert.error.message);
      throw new Error("phone_sync_provision_failed");
    }

    const opaqueSessionToken = randomHex(32);
    const sessionHash = await sha256Hex(opaqueSessionToken);
    const jwtId = crypto.randomUUID();
    const issuedAt = Math.floor(Date.now() / 1000);
    const expiresAtEpoch = issuedAt + 30 * 24 * 60 * 60;
    const expiresAt = new Date(expiresAtEpoch * 1000).toISOString();

    const accessToken = await new jose.SignJWT({
      role: "authenticated",
      phone: verified.phone,
    })
      .setProtectedHeader({ alg: "HS256", typ: "JWT" })
      .setIssuer(`${supabaseUrl}/auth/v1`)
      .setAudience("authenticated")
      .setSubject(profileId)
      .setIssuedAt(issuedAt)
      .setNotBefore(issuedAt - 5)
      .setExpirationTime(expiresAtEpoch)
      .setJti(jwtId)
      .sign(new TextEncoder().encode(jwtSecret));

    const cleared = await admin.from("vibesync_sessions").delete().eq("profile_id", profileId);
    if (cleared.error) {
      console.error("verify-truecaller session cleanup failed:", cleared.error.message);
      throw new Error("session_cleanup_failed");
    }
    const created = await admin.from("vibesync_sessions").insert({
      session_hash: sessionHash,
      profile_id: profileId,
      jwt_id: jwtId,
      expires_at: expiresAt,
      last_seen_at: new Date().toISOString(),
    });
    if (created.error) {
      console.error("verify-truecaller session creation failed:", created.error.message);
      throw new Error("session_provision_failed");
    }

    return json({
      verified: true,
      access_token: accessToken,
      profile_id: profileId,
      session_token: opaqueSessionToken,
      phone: verified.phone,
      expires_at: expiresAtEpoch,
      first_name: verified.firstName,
      last_name: verified.lastName,
      avatar_url: verified.avatarUrl,
    });
  } catch (error) {
    const status = error instanceof VerificationError ? error.status : 500;
    if (!(error instanceof VerificationError)) {
      console.error("verify-truecaller provisioning failure:", error instanceof Error ? error.message : "unknown");
    }
    return json({
      error: "truecaller_verification_failed",
      detail: error instanceof VerificationError ? error.message : "server_error",
    }, status);
  }
});
