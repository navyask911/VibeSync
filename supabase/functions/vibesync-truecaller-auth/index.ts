import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

// Truecaller OAuth client identifier. Client IDs are public identifiers; the
// PKCE code_verifier protects the authorization-code exchange.
const TRUECALLER_CLIENT_ID = "z3rfwgowtpkt-4_5hmsogbcjf2wc8igmb_bjpvzk8xc";
const TRUECALLER_TOKEN_ENDPOINT = "https://oauth-account-noneu.truecaller.com/v1/token";
const TRUECALLER_USERINFO_ENDPOINT = "https://oauth-account-noneu.truecaller.com/v1/userinfo";
const registeredPackage = "com.aistudio.freedating.mkwvxq";
const requestStateTtlSeconds = 600;

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function randomPassword() {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary)
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
}

function normalizePhone(value: unknown): string {
  const digits = String(value ?? "").replace(/[^0-9]/g, "");
  if (digits.length < 7 || digits.length > 15) {
    throw new Error("truecaller_phone_invalid");
  }
  return "+" + digits;
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
      `truecaller_token_exchange_failed_${response.status}:${bodyText.slice(0, 200)}`,
    );
  }

  const token = JSON.parse(bodyText);
  const accessToken = String(token?.access_token ?? "");
  if (!accessToken) throw new Error("truecaller_access_token_missing");

  return {
    accessToken,
    tokenType: String(token?.token_type ?? "Bearer"),
  };
}

async function fetchTruecallerUserInfo(accessToken: string) {
  const response = await fetch(TRUECALLER_USERINFO_ENDPOINT, {
    headers: {
      accept: "application/json",
      authorization: `Bearer ${accessToken}`,
      "cache-control": "no-cache",
    },
  });

  const bodyText = await response.text();
  if (!response.ok) {
    throw new Error(
      `truecaller_userinfo_failed_${response.status}:${bodyText.slice(0, 200)}`,
    );
  }

  const profile = JSON.parse(bodyText);
  if (profile?.phone_number_verified !== true) {
    throw new Error("truecaller_phone_not_verified");
  }

  return {
    phone: normalizePhone(profile.phone_number),
    firstName: String(profile.given_name ?? "").slice(0, 80),
    lastName: String(profile.family_name ?? "").slice(0, 80),
    avatarUrl: String(profile.picture ?? "").slice(0, 2048),
    truecallerId: String(profile.sub ?? "").slice(0, 128),
  };
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!supabaseUrl || !serviceRoleKey) {
    return json({ error: "server_configuration_incomplete" }, 503);
  }

  let body: any;
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

  try {
    const issued = await exchangeAuthorizationCode(authorizationCode, codeVerifier);
    const profile = await fetchTruecallerUserInfo(issued.accessToken);

    const admin = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });

    const cutoff = new Date(Date.now() - requestStateTtlSeconds * 1000).toISOString();
    await admin.from("truecaller_login_nonces").delete().lt("used_at", cutoff);

    const { error: stateError } = await admin
      .from("truecaller_login_nonces")
      .insert({ request_nonce: state });
    if (stateError) {
      return json({ error: "truecaller_response_already_used" }, 409);
    }

    const profileLookup = await admin
      .from("profiles")
      .select("auth_user_id")
      .eq("phone_number_e164", profile.phone)
      .maybeSingle();

    const registeredLookup = profileLookup.data?.auth_user_id
      ? profileLookup
      : await admin
        .from("registered_accounts")
        .select("auth_user_id")
        .eq("phone_number", profile.phone)
        .maybeSingle();

    const authUserId = registeredLookup.data?.auth_user_id ?? null;
    const password = randomPassword();

    if (authUserId) {
      const update = await admin.auth.admin.updateUserById(authUserId, {
        phone: profile.phone,
        phone_confirm: true,
        password,
        user_metadata: {
          truecaller_verified: true,
          truecaller_id: profile.truecallerId,
          truecaller_name: [profile.firstName, profile.lastName].filter(Boolean).join(" "),
          truecaller_avatar_url: profile.avatarUrl,
        },
      });
      if (update.error) throw update.error;
    } else {
      const created = await admin.auth.admin.createUser({
        phone: profile.phone,
        phone_confirm: true,
        password,
        user_metadata: {
          truecaller_verified: true,
          truecaller_id: profile.truecallerId,
          truecaller_name: [profile.firstName, profile.lastName].filter(Boolean).join(" "),
          truecaller_avatar_url: profile.avatarUrl,
        },
      });
      if (created.error || !created.data.user) {
        throw created.error ?? new Error("supabase_auth_user_create_failed");
      }
    }

    const signedIn = await admin.auth.signInWithPassword({
      phone: profile.phone,
      password,
    });
    if (signedIn.error || !signedIn.data.session || !signedIn.data.user) {
      throw signedIn.error ?? new Error("supabase_auth_session_failed");
    }

    return json({
      access_token: signedIn.data.session.access_token,
      refresh_token: signedIn.data.session.refresh_token,
      expires_at: signedIn.data.session.expires_at,
      expires_in: signedIn.data.session.expires_in,
      user: {
        id: signedIn.data.user.id,
        phone: signedIn.data.user.phone ?? profile.phone,
        email: signedIn.data.user.email ?? null,
      },
      truecaller_profile: {
        first_name: profile.firstName,
        last_name: profile.lastName,
        avatar_url: profile.avatarUrl,
      },
    });
  } catch (error) {
    return json({
      error: "truecaller_auth_failed",
      detail: String((error as Error)?.message ?? error).slice(0, 300),
    }, 401);
  }
});
