const { onRequest } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");

admin.initializeApp();

// Firebase responsibility: FCM delivery only. No Firestore/Realtime/Storage access.
exports.sendFcm = onRequest(async (req, res) => {
  try {
    if (req.method !== "POST") {
      return res.status(405).json({ error: "Method not allowed" });
    }

    const { token, data } = req.body || {};
    if (typeof token !== "string" || !token.trim()) {
      return res.status(400).json({ error: "Missing FCM token" });
    }

    const sanitizedData = Object.fromEntries(
      Object.entries(data || {}).map(([k, v]) => [String(k), String(v ?? "")])
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
