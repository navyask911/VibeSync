const { onRequest } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const crypto = require("node:crypto");
const admin = require("firebase-admin");

admin.initializeApp();

function constantTimeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let result = 0;
  for (let i = 0; i < a.length; i += 1) {
    result |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return result === 0;
}

// Firebase boundary: FCM delivery only. No Firestore/Realtime/Storage access.
// The internal key must be supplied through server-side secret configuration.
exports.sendFcm = onRequest(async (req, res) => {
  try {
    if (req.method !== "POST") {
      return res.status(405).json({ error: "Method not allowed" });
    }

    const expectedKey = process.env.VIBESYNC_FCM_INTERNAL_KEY;
    const suppliedKey = req.get("x-vibesync-internal-key") || "";

    if (!expectedKey || !constantTimeEqual(suppliedKey, expectedKey)) {
      return res.status(401).json({ error: "Unauthorized" });
    }

    const { token, data } = req.body || {};
    if (typeof token !== "string" || !token.trim()) {
      return res.status(400).json({ error: "Missing FCM token" });
    }

    const sanitizedData = Object.fromEntries(
      Object.entries(data || {}).slice(0, 40).map(([k, v]) => [String(k).slice(0, 80), String(v ?? "").slice(0, 4000)])
    );

    const response = await admin.messaging().send({
      token: token.trim(),
      data: sanitizedData,
      android: { priority: "high", ttl: 86400000 }
    });

    return res.status(200).json({ ok: true, messageId: response });
  } catch (error) {
    console.error("FCM delivery error:", error);
    return res.status(500).json({ error: "FCM delivery failed" });
  }
});


const SUPABASE_WEBHOOK_SECRET = defineSecret("SUPABASE_WEBHOOK_SECRET");

function secureCompare(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  const left = Buffer.from(a, "utf8");
  const right = Buffer.from(b, "utf8");
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}

/**
 * Receives row-change webhooks from Supabase pg_net.
 *
 * The matching secret is stored in Firebase Secret Manager and bound only to
 * this function. The handler writes a small audit/event record to Firestore.
 * It also supports an optional record.fcm_token for direct FCM delivery.
 */
exports.supabaseRowWebhook = onRequest(
  {
    region: "asia-south1",
    secrets: [SUPABASE_WEBHOOK_SECRET],
    cors: false,
    timeoutSeconds: 60,
    maxInstances: 10
  },
  async (req, res) => {
    if (req.method !== "POST") {
      return res.status(405).json({ error: "Method not allowed" });
    }

    const expectedSecret = SUPABASE_WEBHOOK_SECRET.value();
    const suppliedSecret = req.get("x-webhook-secret") || "";
    if (!expectedSecret || !secureCompare(suppliedSecret, expectedSecret)) {
      return res.status(401).json({ error: "Unauthorized" });
    }

    const payload = req.body || {};
    const type = typeof payload.type === "string" ? payload.type.toUpperCase() : "";
    const table = typeof payload.table === "string" ? payload.table : "";
    const schema = typeof payload.schema === "string" ? payload.schema : "public";
    const record = payload.record && typeof payload.record === "object" ? payload.record : null;

    if (!["INSERT", "UPDATE"].includes(type) || !table || !record) {
      return res.status(400).json({ error: "Invalid webhook payload" });
    }

    try {
      await admin.firestore().collection("supabase_webhook_events").add({
        type,
        table,
        schema,
        record,
        receivedAt: admin.firestore.FieldValue.serverTimestamp()
      });

      const fcmToken =
        typeof record.fcm_token === "string" && record.fcm_token.trim()
          ? record.fcm_token.trim()
          : "";

      if (fcmToken) {
        await admin.messaging().send({
          token: fcmToken,
          data: {
            type,
            table,
            record_id: String(record.id || record.message_id || "")
          },
          android: {
            priority: "high"
          }
        });
      }

      return res.status(200).json({ ok: true });
    } catch (error) {
      console.error("Supabase webhook handling error:", error);
      return res.status(500).json({ error: "Webhook processing failed" });
    }
  }
);
