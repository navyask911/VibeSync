import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const TRUECALLER_CLIENT_ID = "qkpfffgkiebypf8hvifhkkknj2msgud5hfjqg_ti1wu";
const TRUECALLER_TOKEN_ENDPOINT = "https://oauth-account-noneu.truecaller.com/v1/token";
const TRUECALLER_USERINFO_ENDPOINT = "https://oauth-account-noneu.truecaller.com/v1/userinfo";
const requestStateTtlSeconds = 600;

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

function normalizePhone(value: unknown): string {
  const digits = String(value ?? "").replace(/[^0-9]/g, "");
  if (digits.length < 7 || digits.length > 15) throw new Error("truecaller_phone_invalid");
  return "+" + digits;
}

async function exchangeAuthorizationCode(authorizationCode: string, codeVerifier: string) {
  const response = await fetch(TRUECALLER_TOKEN_ENDPOINT, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded", accept: "application/json" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      client_id: TRUECALLER_CLIENT_ID,
      code: authorizationCode,
      code_verifier: codeVerifier,
    }).toString(),
  });
  const bodyText = await response.text();
  if (!response.ok) throw new Error("truecaller_token_exchange_failed_" + response.status + ":" + bodyText.slice(0, 200));
  const token = JSON.parse(bodyText);
  const accessToken = String(token?.access_token ?? "");
  if (!accessToken) throw new Error("truecaller_access_token_missing");
  return accessToken;
}

async function fetchTruecallerUserInfo(accessToken: string) {
  const response = await fetch(TRUECALLER_USERINFO_ENDPOINT, {
    headers: { accept: "application/json", authorization: "Bearer " + accessToken, "cache-control": "no-cache" },
  });
  const bodyText = await response.text();
  if (!response.ok) throw new Error("truecaller_userinfo_failed_" + response.status + ":" + bodyText.slice(0, 200));
  const profile = JSON.parse(bodyText);
  if (profile?.phone_number_verified !== true) throw new Error("truecaller_phone_not_verified");
  return {
    phone: normalizePhone(profile.phone_number),
    firstName: String(profile.given_name ?? "").slice(0, 80),
    lastName: String(profile.family_name ?? "").slice(0, 80),
    avatarUrl: String(profile.picture ?? "").slice(0, 2048),
  };
}

async function consumeRequestState(state: string) {
  const admin = createClient(supabaseUrl, serviceRoleKey, { auth: { persistSession: false, autoRefreshToken: false } });
  const cutoff = new Date(Date.now() - requestStateTtlSeconds * 1000).toISOString();
  await admin.from("truecaller_login_nonces").delete().lt("used_at", cutoff);
  const { error } = await admin.from("truecaller_login_nonces").insert({ request_nonce: state });
  if (error) throw new Error("truecaller_response_already_used");
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!supabaseUrl || !serviceRoleKey) return json({ error: "server_configuration_incomplete" }, 503);
  let body: any;
  try { body = await req.json(); } catch { return json({ error: "invalid_json" }, 400); }
  const authorizationCode = String(body?.authorization_code ?? "");
  const codeVerifier = String(body?.code_verifier ?? "");
  const state = String(body?.state ?? "");
  if (!authorizationCode || !codeVerifier || !state) return json({ error: "invalid_truecaller_oauth_request" }, 400);
  if (state.length < 20 || state.length > 256) return json({ error: "invalid_truecaller_state" }, 400);
  if (codeVerifier.length < 43 || codeVerifier.length > 128) return json({ error: "invalid_truecaller_code_verifier" }, 400);
  try {
    const accessToken = await exchangeAuthorizationCode(authorizationCode, codeVerifier);
    const profile = await fetchTruecallerUserInfo(accessToken);
    await consumeRequestState(state);
    return json({ verified: true, phone: profile.phone, first_name: profile.firstName, last_name: profile.lastName, avatar_url: profile.avatarUrl });
  } catch (error) {
    return json({ error: "truecaller_verification_failed", detail: String(error?.message ?? error).slice(0, 300) }, 401);
  }
});
