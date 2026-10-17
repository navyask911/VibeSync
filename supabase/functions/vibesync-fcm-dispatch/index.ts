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


function parseFirebaseServiceAccount(): ServiceAccount | null {
  if (!firebaseServiceAccountJson) return null;
  try {
    const sa = JSON.parse(firebaseServiceAccountJson) as ServiceAccount;
    if (!sa.client_email || !sa.private_key || !sa.project_id) return null;
    return sa;
  } catch {
    return null;
  }
}

async function dispatchStoredMessage(
  adminClient: ReturnType<typeof createClient>,
  sa: ServiceAccount,
  messageId: string,
  expectedSenderId: string,
  webhookMode: boolean,
): Promise<Response> {
  const { data: message, error: messageError } = await adminClient
    .from("chat_messages")
    .select("message_id,match_id,sender_id,receiver_id,ciphertext,is_encrypted,media_type,dispatch_status,dispatch_attempts")
    .eq("message_id", messageId)
    .maybeSingle();

  if (messageError || !message) return json({ error: "message_not_found" }, 404);
  if (String(message.sender_id ?? "") !== expectedSenderId) {
    return json({ error: "sender_mismatch" }, 403);
  }
  if (!message.is_encrypted || typeof message.ciphertext !== "string" || !message.ciphertext.startsWith("TINK_ENC:")) {
    return json({ error: "encrypted_payload_required" }, 422);
  }
  if (!message.receiver_id) return json({ error: "recipient_unresolved" }, 422);

  let attempts = Number(message.dispatch_attempts ?? 0);
  let claimed = false;

  // pg_net/webhook delivery is at-least-once. Atomically claim PENDING messages
  // so duplicate callbacks cannot send the same notification twice.
  if (webhookMode) {
    if (message.dispatch_status === "SENT") {
      return json({ ok: true, message_id: messageId, skipped: "already_sent" });
    }
    const { data: claim, error: claimError } = await adminClient
      .from("chat_messages")
      .update({ dispatch_status: "SENDING", dispatch_attempts: attempts + 1 })
      .eq("message_id", messageId)
      .eq("dispatch_status", "PENDING")
      .select("message_id")
      .maybeSingle();

    if (claimError) return json({ error: "dispatch_claim_failed" }, 500);
    if (!claim) return json({ ok: true, message_id: messageId, skipped: "already_claimed_or_dispatched" });
    claimed = true;
    attempts += 1;
  }

  const { data: tokens, error: tokenError } = await adminClient
    .from("device_tokens")
    .select("id,fcm_token")
    .eq("profile_id", message.receiver_id);

  if (tokenError) {
    if (claimed) {
      await adminClient.from("chat_messages")
        .update({ dispatch_status: "PENDING" })
        .eq("message_id", messageId)
        .eq("dispatch_status", "SENDING");
    }
    return json({ error: "token_lookup_failed" }, 500);
  }

  if (!tokens?.length) {
    if (claimed) {
      await adminClient.from("chat_messages")
        .update({ dispatch_status: "PENDING", dispatch_attempts: attempts })
        .eq("message_id", messageId)
        .eq("dispatch_status", "SENDING");
    }
    return json({ ok: true, message_id: messageId, delivered_to_devices: 0 });
  }

  let delivered = 0;
  const failedTokenIds: string[] = [];
  try {
    for (const row of tokens) {
      try {
        // FCM is a wake-up signal only. Message ciphertext stays in Supabase and is fetched
        // by the recipient's authenticated sync worker; never put encrypted chat content in push data.
        await sendFcm(sa, row.fcm_token, {
          type: "new_message",
          message_id: String(message.message_id),
          match_id: String(message.match_id ?? ""),
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

    let statusUpdate = adminClient.from("chat_messages")
      .update({
        dispatch_status: delivered > 0 ? "SENT" : "PENDING",
        dispatch_attempts: attempts + (webhookMode ? 0 : 1),
      })
      .eq("message_id", messageId);

    if (webhookMode) {
      statusUpdate = statusUpdate.eq("dispatch_status", "SENDING");
    } else {
      statusUpdate = statusUpdate.eq("sender_id", expectedSenderId);
    }
    const { error: statusError } = await statusUpdate;
    if (statusError) console.error("Could not update FCM dispatch status:", statusError.message);

    return json({
      ok: true,
      message_id: messageId,
      delivered_to_devices: delivered,
      stale_tokens_removed: failedTokenIds.length,
    });
  } catch (e) {
    if (claimed) {
      await adminClient.from("chat_messages")
        .update({ dispatch_status: "PENDING", dispatch_attempts: attempts })
        .eq("message_id", messageId)
        .eq("dispatch_status", "SENDING");
    }
    console.error("FCM dispatch error:", String((e as Error)?.message ?? e));
    return json({ error: "fcm_dispatch_failed" }, 502);
  }
}



async function dispatchStoredGroupEnvelope(
  adminClient: ReturnType<typeof createClient>,
  sa: ServiceAccount,
  groupId: string,
  messageId: string,
  recipientProfileId: string,
  expectedSenderId: string,
): Promise<Response> {
  const { data: envelope, error: envelopeError } = await adminClient
    .from("chat_group_message_envelopes")
    .select("group_id,message_id,recipient_profile_id,ciphertext,dispatch_status,dispatch_attempts")
    .eq("group_id", groupId).eq("message_id", messageId)
    .eq("recipient_profile_id", recipientProfileId).maybeSingle();
  if (envelopeError || !envelope) return json({ error: "group_envelope_not_found" }, 404);
  if (!String(envelope.ciphertext ?? "").startsWith("TINK_ENC:")) {
    return json({ error: "encrypted_group_payload_required" }, 422);
  }

  const { data: metadata, error: metadataError } = await adminClient
    .from("chat_group_messages").select("group_id,message_id,sender_profile_id")
    .eq("group_id", groupId).eq("message_id", messageId).maybeSingle();
  if (metadataError || !metadata) return json({ error: "group_message_not_found" }, 404);
  const senderProfileId = String(metadata.sender_profile_id ?? "");
  if (!senderProfileId || senderProfileId !== expectedSenderId) return json({ error: "group_sender_mismatch" }, 403);
  if (senderProfileId === recipientProfileId) {
    return json({ ok: true, message_id: messageId, skipped: "sender_device_not_notified" });
  }

  const { data: membership, error: membershipError } = await adminClient
    .from("chat_group_members").select("profile_id").eq("group_id", groupId)
    .eq("profile_id", recipientProfileId).is("left_at", null).maybeSingle();
  if (membershipError) return json({ error: "group_membership_lookup_failed" }, 500);
  if (!membership) return json({ ok: true, message_id: messageId, skipped: "recipient_not_active_member" });

  const attempts = Number(envelope.dispatch_attempts ?? 0);
  const { data: claim, error: claimError } = await adminClient
    .from("chat_group_message_envelopes")
    .update({ dispatch_status: "SENDING", dispatch_attempts: attempts + 1 })
    .eq("group_id", groupId).eq("message_id", messageId)
    .eq("recipient_profile_id", recipientProfileId).eq("dispatch_status", "PENDING")
    .select("message_id").maybeSingle();
  if (claimError) return json({ error: "group_dispatch_claim_failed" }, 500);
  if (!claim) return json({ ok: true, message_id: messageId, skipped: "already_claimed_or_dispatched" });

  const { data: tokens, error: tokenError } = await adminClient
    .from("device_tokens").select("id,fcm_token").eq("profile_id", recipientProfileId);
  if (tokenError || !tokens?.length) {
    await adminClient.from("chat_group_message_envelopes")
      .update({ dispatch_status: "PENDING" })
      .eq("group_id", groupId).eq("message_id", messageId)
      .eq("recipient_profile_id", recipientProfileId).eq("dispatch_status", "SENDING");
    return tokenError ? json({ error: "group_token_lookup_failed" }, 500)
      : json({ ok: true, message_id: messageId, delivered_to_devices: 0 });
  }

  let delivered = 0;
  const staleTokenIds: string[] = [];
  try {
    for (const tokenRow of tokens) {
      try {
        await sendFcm(sa, tokenRow.fcm_token, {
          type: "GROUP_CHAT_SYNC_WAKEUP",
          group_id: groupId, message_id: messageId,
          sender_id: senderProfileId, recipient_profile_id: recipientProfileId,
        });
        delivered++;
      } catch (error) {
        const detail = String((error as Error)?.message ?? error);
        if (/registration-token-not-registered|invalid-registration-token|UNREGISTERED/i.test(detail)) {
          staleTokenIds.push(String(tokenRow.id));
        }
      }
    }
    if (staleTokenIds.length) await adminClient.from("device_tokens").delete().in("id", staleTokenIds);
    const { error: statusError } = await adminClient.from("chat_group_message_envelopes")
      .update({ dispatch_status: delivered > 0 ? "SENT" : "PENDING",
        delivered_at: delivered > 0 ? new Date().toISOString() : null })
      .eq("group_id", groupId).eq("message_id", messageId)
      .eq("recipient_profile_id", recipientProfileId).eq("dispatch_status", "SENDING");
    if (statusError) console.error("Could not update group FCM dispatch status:", statusError.message);
    return json({ ok: true, message_id: messageId, group_id: groupId,
      delivered_to_devices: delivered, stale_tokens_removed: staleTokenIds.length });
  } catch (error) {
    await adminClient.from("chat_group_message_envelopes")
      .update({ dispatch_status: "PENDING", dispatch_attempts: attempts })
      .eq("group_id", groupId).eq("message_id", messageId)
      .eq("recipient_profile_id", recipientProfileId).eq("dispatch_status", "SENDING");
    console.error("Group FCM dispatch error:", String((error as Error)?.message ?? error));
    return json({ error: "group_fcm_dispatch_failed" }, 502);
  }
}


/**
 * Receipt rows are durable and contain no content. Deliver only IDs and the bounded status to
 * the sender's devices so a backgrounded app can update its local Room copy after Realtime pauses.
 */
async function dispatchStoredReceipt(
  adminClient: ReturnType<typeof createClient>,
  sa: ServiceAccount,
  messageId: string,
  matchId: string,
  status: string,
  targetDeviceTokenId = "",
): Promise<Response> {
  const safeStatus = status.toUpperCase();
  if (!messageId || !matchId || !["DELIVERED", "READ", "FAILED"].includes(safeStatus)) {
    return json({ error: "invalid_receipt_payload" }, 400);
  }

  const { data: receipt, error: receiptError } = await adminClient
    .from("chat_message_receipts")
    .select("message_id,match_id,status")
    .eq("message_id", messageId)
    .eq("match_id", matchId)
    .eq("status", safeStatus)
    .maybeSingle();
  if (receiptError) return json({ error: "receipt_lookup_failed" }, 500);
  if (!receipt) return json({ ok: true, message_id: messageId, skipped: "receipt_not_persisted" });

  const { data: participants, error: participantError } = await adminClient
    .from("chat_message_receipt_participants")
    .select("sender_id,match_id")
    .eq("message_id", messageId)
    .maybeSingle();
  if (participantError) return json({ error: "receipt_participant_lookup_failed" }, 500);
  if (!participants || String(participants.match_id) !== matchId || !String(participants.sender_id ?? "")) {
    return json({ error: "receipt_participant_mapping_not_found" }, 404);
  }

  let tokenQuery = adminClient
    .from("device_tokens")
    .select("id,fcm_token")
    .eq("profile_id", String(participants.sender_id));
  if (targetDeviceTokenId) tokenQuery = tokenQuery.eq("id", targetDeviceTokenId);
  const { data: tokens, error: tokenError } = await tokenQuery;
  if (tokenError) return json({ error: "receipt_token_lookup_failed" }, 500);
  if (!tokens?.length) return json({ ok: true, message_id: messageId, delivered_to_devices: 0 });

  let sent = 0;
  const staleTokenIds: string[] = [];
  let transientFailure = false;
  for (const row of tokens) {
    try {
      await sendFcm(sa, String(row.fcm_token), {
        type: "receipt_update",
        message_id: messageId,
        match_id: matchId,
        status: safeStatus,
      });
      sent++;
    } catch (error) {
      const detail = String((error as Error)?.message ?? error);
      if (/registration-token-not-registered|invalid-registration-token|UNREGISTERED/i.test(detail)) {
        staleTokenIds.push(String(row.id));
      } else {
        transientFailure = true;
      }
    }
  }

  if (staleTokenIds.length) {
    const { error } = await adminClient.from("device_tokens").delete().in("id", staleTokenIds);
    if (error) console.error("Could not clean invalid receipt FCM tokens:", error.message);
  }
  if (sent === 0 && transientFailure) return json({ error: "receipt_fcm_dispatch_failed" }, 502);
  return json({
    ok: true,
    message_id: messageId,
    status: safeStatus,
    delivered_to_devices: sent,
    stale_tokens_removed: staleTokenIds.length,
  });
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!supabaseUrl || !serviceRoleKey) return json({ error: "server_configuration_incomplete" }, 503);

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

  const adminClient = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  // Database-trigger mode: pg_net sends the row-change envelope and x-webhook-secret.
  // The secret is validated against Vault via an RPC available only to service_role.
  const webhookSecret = req.headers.get("x-webhook-secret")?.trim() ?? "";
  if (webhookSecret) {
    const { data: authorized, error: authError } = await adminClient.rpc(
      "verify_vibesync_fcm_webhook_secret",
      { p_supplied_secret: webhookSecret },
    );
    if (authError || authorized !== true) return json({ error: "unauthorized_webhook" }, 401);

    const eventType = typeof body.type === "string" ? body.type.toUpperCase() : "";
    const schema = typeof body.schema === "string" ? body.schema : "public";
    const table = typeof body.table === "string" ? body.table : "";
    const record = body.record && typeof body.record === "object" && !Array.isArray(body.record)
      ? body.record as Record<string, unknown>
      : null;

    if (!["INSERT", "UPDATE"].includes(eventType) || schema !== "public" || !record) {
      return json({ error: "invalid_row_change_payload" }, 400);
    }
    const sa = parseFirebaseServiceAccount();
    if (!sa) return json({ error: "invalid_fcm_server_credential" }, 503);

    if (table === "chat_group_message_envelopes") {
      const groupId = String(record.group_id ?? "").trim();
      const messageId = String(record.message_id ?? "").trim();
      const recipientProfileId = String(record.recipient_profile_id ?? "").trim();
      const senderProfileId = String(record.sender_profile_id ?? "").trim();
      if (!groupId || !messageId || !recipientProfileId || !senderProfileId) {
        return json({ error: "group_id_message_id_recipient_and_sender_required" }, 400);
      }
      return await dispatchStoredGroupEnvelope(adminClient, sa, groupId, messageId, recipientProfileId, senderProfileId);
    }
    if (table === "chat_message_receipts") {
      const messageId = String(record.message_id ?? "").trim();
      const matchId = String(record.match_id ?? "").trim();
      const status = String(record.status ?? "").trim().toUpperCase();
      const targetDeviceTokenId = String(record.target_device_token_id ?? "").trim();
      return await dispatchStoredReceipt(adminClient, sa, messageId, matchId, status, targetDeviceTokenId);
    }
    if (table !== "chat_messages") return json({ error: "unsupported_row_change_table" }, 400);

    const messageId = String(record.message_id ?? "").trim();
    const senderId = String(record.sender_id ?? "").trim();
    if (!messageId || !senderId) return json({ error: "message_id_and_sender_id_required" }, 400);
    return await dispatchStoredMessage(adminClient, sa, messageId, senderId, true);
  }

  // Existing Android dispatch path: custom VibeSync session plus message_id.
  const sessionToken = req.headers.get("x-vibesync-session")?.trim() ?? "";
  if (!sessionToken) return json({ error: "vibesync_session_required" }, 401);
  const messageId = String(body.message_id ?? "").trim();
  if (!messageId) return json({ error: "message_id_required" }, 400);

  const sa = parseFirebaseServiceAccount();
  if (!sa) return json({ error: "invalid_fcm_server_credential" }, 503);

  try {
    const profileId = await resolveProfileId(adminClient, sessionToken);
    return await dispatchStoredMessage(adminClient, sa, messageId, profileId, false);
  } catch (error) {
    return json({
      error: "fcm_dispatch_failed",
      detail: String((error as Error)?.message ?? error).slice(0, 300),
    }, 401);
  }
});
