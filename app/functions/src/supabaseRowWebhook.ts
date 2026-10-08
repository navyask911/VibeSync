import { onRequest } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import * as admin from "firebase-admin";
import { timingSafeEqual } from "node:crypto";

const SUPABASE_WEBHOOK_SECRET = defineSecret("SUPABASE_WEBHOOK_SECRET");

export interface SupabaseRowChangePayload {
  type: "INSERT" | "UPDATE";
  table: string;
  schema?: string;
  record: Record<string, unknown>;
}

function secureCompare(a: string, b: string): boolean {
  const left = Buffer.from(a, "utf8");
  const right = Buffer.from(b, "utf8");
  return left.length === right.length && timingSafeEqual(left, right);
}

/**
 * TypeScript / Firebase Functions v2 implementation of the Supabase row webhook.
 *
 * The current VibeSync Firebase codebase uses a CommonJS JavaScript entrypoint,
 * so app/functions/index.js exports the runtime-compatible mirror. This file
 * is the canonical TypeScript implementation for future full TS migration.
 */
export const supabaseRowWebhook = onRequest(
  {
    region: "asia-south1",
    secrets: [SUPABASE_WEBHOOK_SECRET],
    cors: false,
    timeoutSeconds: 60,
    maxInstances: 10,
  },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).json({ error: "Method not allowed" });
      return;
    }

    const suppliedSecret = req.get("x-webhook-secret") ?? "";
    const expectedSecret = SUPABASE_WEBHOOK_SECRET.value();

    if (!expectedSecret || !secureCompare(suppliedSecret, expectedSecret)) {
      res.status(401).json({ error: "Unauthorized" });
      return;
    }

    const payload = (req.body ?? {}) as Partial<SupabaseRowChangePayload>;
    const type = payload.type;
    const table = typeof payload.table === "string" ? payload.table : "";
    const schema = typeof payload.schema === "string" ? payload.schema : "public";
    const record =
      payload.record && typeof payload.record === "object"
        ? payload.record
        : null;

    if (!type || !["INSERT", "UPDATE"].includes(type) || !table || !record) {
      res.status(400).json({ error: "Invalid webhook payload" });
      return;
    }

    try {
      await admin.firestore().collection("supabase_webhook_events").add({
        type,
        table,
        schema,
        record,
        receivedAt: admin.firestore.FieldValue.serverTimestamp(),
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
            record_id: String(record.id ?? record.message_id ?? ""),
          },
          android: { priority: "high" },
        });
      }

      res.status(200).json({ ok: true });
    } catch (error) {
      console.error("Supabase webhook handling error:", error);
      res.status(500).json({ error: "Webhook processing failed" });
    }
  },
);
