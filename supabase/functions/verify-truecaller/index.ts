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
// Fixed application namespace for UUID v5 identity: stable across devices and deploys.
const UUID_V5_NAMESPACE = "e3d4a2d1-66d5-5f26-8c2b-29f27b7e4a19";
const rateBuckets = new Map<string, { count: number; resetAt: number }>();

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

function json(data: unknown, status = 200, extraHeaders: Record<string, string> = {}) {
  return new Response(JSON.stringify(data), { status, headers: { ...corsHeaders, ...extraHeaders } });
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

function normalizePhone(value: unknown): { e164: string; last10: string; candidates: string[] } {
  const raw = String(value ?? "").trim();
  const digits = raw.replace(/[^0-9]/g, "");
  if (digits.length < 10 || digits.length > 15) {
    throw new HttpError("truecaller_phone_invalid", 401);
  }
  let e164: string;
  if (raw.startsWith("+")) e164 = "+" + digits;
  else if (digits.length === 10) e164 = "+91" + digits;
  else if (digits.length === 11 && digits.startsWith("0")) e164 = "+91" + digits.slice(1);
  else if (digits.length === 12 && digits.startsWith("91")) e164 = "+" + digits;
  else e164 = "+" + digits;
  const internationalDigits = e164.slice(1);
  const last10 = internationalDigits.slice(-10);
  const candidateSet = new Set([e164, internationalDigits, "+" + digits, digits]);
  // Only add ten-digit / trunk-prefix aliases for normalized Indian (+91) numbers.
  // Do not let a non-Indian international number collide with an Indian admin's last ten digits.
  if (e164.startsWith("+91") && internationalDigits.length === 12) {
    [
      "+91" + last10,
      "91" + last10,
      last10,
      "0" + last10,
    ].forEach((candidate) => candidateSet.add(candidate));
  }
  return { e164, last10, candidates: Array.from(candidateSet) };
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

function clientIp(request: Request): string {
  return request.headers.get("cf-connecting-ip")?.trim() ||
    request.headers.get("x-real-ip")?.trim() ||
    request.headers.get("x-forwarded-for")?.split(",").map((v) => v.trim()).filter(Boolean).pop() ||
    "unknown";
}

function takeRateLimit(key: string, limit: number, windowMs: number): number {
  const now = Date.now();
  let bucket = rateBuckets.get(key);
  if (!bucket || bucket.resetAt <= now) {
    bucket = { count: 0, resetAt: now + windowMs };
    rateBuckets.set(key, bucket);
  }
  if (bucket.count >= limit) return Math.max(1, Math.ceil((bucket.resetAt - now) / 1000));
  bucket.count += 1;
  if (rateBuckets.size > 10000) {
    for (const [candidate, value] of rateBuckets) {
      if (value.resetAt <= now) rateBuckets.delete(candidate);
    }
    while (rateBuckets.size > 12000) rateBuckets.delete(rateBuckets.keys().next().value!);
  }
  return 0;
}

function uuidBytes(value: string): Uint8Array {
  return new Uint8Array(value.replace(/-/g, "").match(/.{2}/g)!.map((part) => parseInt(part, 16)));
}

async function uuidV5(name: string): Promise<string> {
  const namespace = uuidBytes(UUID_V5_NAMESPACE);
  const nameBytes = new TextEncoder().encode(name);
  const input = new Uint8Array(namespace.length + nameBytes.length);
  input.set(namespace, 0);
  input.set(nameBytes, namespace.length);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-1", input));
  const bytes = digest.slice(0, 16);
  bytes[6] = (bytes[6] & 0x0f) | 0x50;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes).map((b) => b.toString(16).padStart(2, "0")).join("");
  return hex.slice(0, 8) + "-" + hex.slice(8, 12) + "-" + hex.slice(12, 16) +
    "-" + hex.slice(16, 20) + "-" + hex.slice(20);
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
    phoneCandidates: phone.candidates,
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

  // Per-isolate in-memory limits run before any database access. The gateway IP is preferred;
  // forwarded-for falls back to the last proxy-added hop rather than the client-supplied first hop.
  const ip = clientIp(req);
  const minuteRetry = takeRateLimit("verify:ip-minute:" + ip, 8, 60_000);
  const hourRetry = takeRateLimit("verify:ip-hour:" + ip, 35, 60 * 60_000);
  const ipRetry = Math.max(minuteRetry, hourRetry);
  if (ipRetry > 0) return json({ error: "rate_limited", retry_after_seconds: ipRetry }, 429, { "retry-after": String(ipRetry) });

  const serviceRoleKey = getSupabaseSecretKey();
  // JWT_SECRET is canonical because PostgREST/Realtime verify this signature with the project JWT secret.
  const jwtSecret = Deno.env.get("JWT_SECRET") || Deno.env.get("APP_JWT_SECRET") || "";
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

    // Do not allow verified-phone churn to bypass the IP bucket. This hashed in-memory key is
    // deliberately not persisted and is checked before profile/session writes.
    const phoneRateKey = await sha256Hex(identity.phone);
    const phoneRetry = takeRateLimit("verify:phone-hour:" + phoneRateKey, 5, 60 * 60_000);
    if (phoneRetry > 0) return json({ error: "rate_limited", retry_after_seconds: phoneRetry }, 429, { "retry-after": String(phoneRetry) });

    const fullPhoneHash = await sha256Hex(identity.last10);
    const profileId = fullPhoneHash.slice(0, 16);
    const authUid = await uuidV5("vibesync:phone:" + identity.phone);
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

    const identityMapUpsert = await admin.from("vibesync_auth_identity_map").upsert({
      auth_uid: authUid,
      profile_id: profileId,
      updated_at: new Date().toISOString(),
    }, { onConflict: "auth_uid" });
    if (identityMapUpsert.error) {
      throw new HttpError("auth_identity_map_provision_failed: " + identityMapUpsert.error.message.slice(0, 200), 500);
    }

    const phoneLookupUpsert = await admin.from("phone_lookup_index").upsert({
      profile_id: profileId,
      phone_e164: identity.phone,
      updated_at: new Date().toISOString(),
    }, { onConflict: "profile_id" });
    if (phoneLookupUpsert.error) {
      throw new HttpError("phone_lookup_index_provision_failed: " + phoneLookupUpsert.error.message.slice(0, 200), 500);
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

    let configuredSuperAdminPhone: string;
    try {
      configuredSuperAdminPhone = normalizePhone(
        Deno.env.get("SUPERADMIN_PHONE")?.trim() || "+919743040335",
      ).e164;
    } catch {
      throw new HttpError("superadmin_phone_configuration_invalid", 503);
    }
    const isSuperAdmin = identity.phone === configuredSuperAdminPhone;

    // Persist this role only after Truecaller has verified the exact configured E.164 number.
    // User input and unverified request fields are never used as an elevation signal.
    if (isSuperAdmin) {
      const [byProfile, byPhone] = await Promise.all([
        admin.from("super_admins").select("profile_id,phone")
          .eq("profile_id", profileId).limit(1).maybeSingle(),
        admin.from("super_admins").select("profile_id,phone")
          .in("phone", identity.phoneCandidates).limit(1).maybeSingle(),
      ]);
      if (byProfile.error || byPhone.error) {
        throw new HttpError("super_admin_membership_lookup_failed", 503);
      }
      const existingAdmin = byProfile.data ?? byPhone.data;
      let membershipWrite;
      if (existingAdmin) {
        const update = admin.from("super_admins").update({
          profile_id: profileId, user_id: profileId, phone: identity.phone,
        });
        membershipWrite = existingAdmin.profile_id
          ? await update.eq("profile_id", existingAdmin.profile_id).select("profile_id").maybeSingle()
          : await update.eq("phone", existingAdmin.phone ?? identity.phone).select("profile_id").maybeSingle();
      } else {
        membershipWrite = await admin.from("super_admins").insert({
          profile_id: profileId, user_id: profileId, phone: identity.phone,
        }).select("profile_id").single();
      }
      if (membershipWrite.error || !membershipWrite.data) {
        throw new HttpError("super_admin_membership_persist_failed", 503);
      }
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
        sub: authUid,
        aud: "authenticated",
        // The top-level role must remain a valid PostgREST database role.
        role: "authenticated",
        is_superadmin: isSuperAdmin,
        app_metadata: {
          role: isSuperAdmin ? "superadmin" : "member",
          is_superadmin: isSuperAdmin,
        },
        phone: identity.phone,
        jti: jwtId,
        profile_id: profileId,
        iss: "supabase",
        iat: nowSec,
        exp: expiresAtSec,
      },
      signingKey,
    );

    return json({
      verified: true,
      is_super_admin: isSuperAdmin,
      is_superadmin: isSuperAdmin,
      role: isSuperAdmin ? "superadmin" : "member",
      admin_role: isSuperAdmin ? "SUPER_ADMIN" : "MEMBER",
      phone: identity.phone,
      profile_id: profileId,
      auth_uid: authUid,
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
