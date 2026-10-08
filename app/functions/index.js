const { onRequest } = require("firebase-functions/v2/https");
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
