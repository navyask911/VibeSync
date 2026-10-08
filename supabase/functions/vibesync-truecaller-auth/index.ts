import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const EXPECTED_PACKAGE_VERIFIER = "WQuYgMzFxBPqeqLHW3WdXRth1KR7FZv/l9Ci6SabNvI=";
const registeredPackage = "com.aistudio.freedating.mkwvxq";
const requestTtlSeconds = 600;

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

function b64(value: string): Uint8Array {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

function b64encode(value: Uint8Array): string {
  let binary = "";
  for (const byte of value) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function hex(value: Uint8Array): string {
  return Array.from(value).map((v) => v.toString(16).padStart(2, "0")).join("");
}

function equal(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

async function verifyProfile(input: any) {
  if (!truecallerAppKey) throw new Error("TRUECALLER_APP_KEY is not configured");
  if (!input?.payload || !input?.signature || input?.signature_algorithm !== "SHA512withRSA") {
    throw new Error("invalid_truecaller_response");
  }

  const payloadBytes = b64(String(input.payload));
  const payload = JSON.parse(new TextDecoder().decode(payloadBytes));
  const now = Math.floor(Date.now() / 1000);
  const age = now - Number(payload.requestTime ?? 0);
  if (!Number.isFinite(age) || age < -60 || age > requestTtlSeconds) {
    throw new Error("truecaller_response_expired");
  }

  const requestNonce = String(input.request_nonce ?? "");
  if (!requestNonce || String(payload.requestNonce ?? "") !== requestNonce) {
    throw new Error("truecaller_nonce_mismatch");
  }

  const verifier = String(payload.verifier ?? "");
  const normalizedVerifier = verifier.replace(/-/g, "+").replace(/_/g, "/").replace(/=+$/g, "");
  const expectedVerifier = EXPECTED_PACKAGE_VERIFIER.replace(/=+$/g, "");
  if (!equal(verifier, EXPECTED_PACKAGE_VERIFIER) && !equal(normalizedVerifier, expectedVerifier)) {
    throw new Error("truecaller_package_verifier_mismatch");
  }
  const keyResponse = await fetch("https://api4.truecaller.com/v1/key", {
    headers: { accept: "application/json" },
  });
  if (!keyResponse.ok) throw new Error("truecaller_keys_unavailable");

  const keyList = await keyResponse.json();
  const signature = b64(String(input.signature));
  let valid = false;

  for (const entry of Array.isArray(keyList) ? keyList : []) {
    const encodedKey = String(entry?.key ?? "");
    if (!encodedKey) continue;
    const key = await crypto.subtle.importKey(
      "spki",
      b64(encodedKey),
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-512" },
      false,
      ["verify"],
    );
    if (await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, signature, payloadBytes)) {
      valid = true;
      break;
    }
  }

  if (!valid) throw new Error("truecaller_signature_invalid");

  const phoneDigits = String(payload.phoneNumber ?? "").replace(/[^0-9]/g, "");
  if (phoneDigits.length < 7 || phoneDigits.length > 15) throw new Error("truecaller_phone_invalid");

  return {
    phone: "+" + phoneDigits,
    firstName: String(payload.firstName ?? "").slice(0, 80),
    lastName: String(payload.lastName ?? "").slice(0, 80),
    avatarUrl: String(payload.avatarUrl ?? "").slice(0, 2048),
    requestNonce,
  };
}

function randomPassword() {
  return b64encode(crypto.getRandomValues(new Uint8Array(32)))
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!supabaseUrl || !serviceRoleKey) return json({ error: "server_configuration_incomplete" }, 503);

  let body: any;
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  try {
    const profile = await verifyProfile(body);
    const admin = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });

    const cutoff = new Date(Date.now() - 86400000).toISOString();
    await admin.from("truecaller_login_nonces").delete().lt("used_at", cutoff);

    const { error: nonceError } = await admin
      .from("truecaller_login_nonces")
      .insert({ request_nonce: profile.requestNonce });
    if (nonceError) return json({ error: "truecaller_response_already_used" }, 409);

    const profileLookup = await admin
      .from("profiles")
      .select("auth_user_id")
      .eq("phone_number_e164", profile.phone)
      .maybeSingle();

    const registeredLookup = profileLookup.data?.auth_user_id
      ? profileLookup
      : await admin.from("registered_accounts")
        .select("auth_user_id")
        .eq("phone_number", profile.phone)
        .maybeSingle();

    const password = randomPassword();
    const authUserId = registeredLookup.data?.auth_user_id ?? null;

    if (authUserId) {
      const update = await admin.auth.admin.updateUserById(authUserId, {
        phone: profile.phone,
        phone_confirm: true,
        password,
      });
      if (update.error) throw update.error;
    } else {
      const created = await admin.auth.admin.createUser({
        phone: profile.phone,
        phone_confirm: true,
        password,
        user_metadata: {
          truecaller_verified: true,
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
