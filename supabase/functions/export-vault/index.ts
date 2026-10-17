import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
function getSecretKey(): string {
  try {
    const keys = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "");
    if (typeof keys?.default === "string" && keys.default) return keys.default;
  } catch { /* legacy service key below */ }
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
}
const SERVICE_ROLE_KEY = getSecretKey();
const JWT_SECRET = Deno.env.get("JWT_SECRET") || Deno.env.get("APP_JWT_SECRET") || "";
const admin = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, { auth: { persistSession: false, autoRefreshToken: false } });
const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, x-client-info",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
  "Cache-Control": "no-store",
};
function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { ...corsHeaders, "content-type": "application/json; charset=utf-8" } });
}
function decodeBase64Url(value: string): Uint8Array {
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/");
  return Uint8Array.from(atob(base64 + "=".repeat((4 - base64.length % 4) % 4)), c => c.charCodeAt(0));
}
function parsePart<T>(value: string): T {
  return JSON.parse(new TextDecoder().decode(decodeBase64Url(value))) as T;
}
async function authenticateSuperadmin(request: Request): Promise<{ profileId: string; claims: Record<string, unknown> } | null> {
  const match = (request.headers.get("authorization") ?? "").match(/^Bearer\s+([^\s]+)$/i);
  if (!match || !JWT_SECRET || !SUPABASE_URL || !SERVICE_ROLE_KEY) return null;
  const parts = match[1].split(".");
  if (parts.length !== 3) return null;
  try {
    const header = parsePart<Record<string, unknown>>(parts[0]);
    const claims = parsePart<Record<string, unknown>>(parts[1]);
    if (header.alg !== "HS256" || claims.iss !== "supabase" || claims.role !== "authenticated" ||
        claims.aud !== "authenticated" || Number(claims.exp) <= Math.floor(Date.now() / 1000) ||
        claims.is_superadmin !== true) return null;
    const metadata = claims.app_metadata as Record<string, unknown> | undefined;
    if (metadata?.role !== "superadmin" || metadata?.is_superadmin !== true) return null;
    const profileId = String(claims.profile_id ?? "");
    const jti = String(claims.jti ?? "");
    if (!profileId || !jti) return null;
    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(JWT_SECRET),
      { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
    if (!await crypto.subtle.verify("HMAC", key, decodeBase64Url(parts[2]),
      new TextEncoder().encode(parts[0] + "." + parts[1]))) return null;

    const now = new Date().toISOString();
    const [session, member] = await Promise.all([
      admin.from("vibesync_sessions").select("profile_id")
        .eq("jwt_id", jti).eq("profile_id", profileId).gt("expires_at", now).maybeSingle(),
      admin.from("super_admins").select("profile_id").eq("profile_id", profileId).limit(1).maybeSingle(),
    ]);
    if (session.error || member.error || !session.data || !member.data ||
        session.data.profile_id !== profileId || member.data.profile_id !== profileId) return null;
    return { profileId, claims };
  } catch {
    return null;
  }
}
function csvCell(value: unknown): string {
  const raw = typeof value === "string" ? value : JSON.stringify(value ?? "");
  return '"' + raw.replace(/"/g, '""') + '"';
}
type Dataset = { label: string; table: string; key: string };
const datasets: Record<string, Dataset[]> = {
  profiles: [{ label: "profiles", table: "profiles", key: "id" }],
  businesses: [{ label: "businesses", table: "businesses", key: "id" }],
  wallets: [
    { label: "wallets", table: "business_wallets", key: "business_id" },
    { label: "wallet_ledger", table: "business_wallet_ledger", key: "id" },
  ],
  all: [
    { label: "profiles", table: "profiles", key: "id" },
    { label: "businesses", table: "businesses", key: "id" },
    { label: "wallets", table: "business_wallets", key: "business_id" },
    { label: "wallet_ledger", table: "business_wallet_ledger", key: "id" },
  ],
};
Deno.serve(async (request: Request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
  const actor = await authenticateSuperadmin(request);
  if (!actor) return json({ error: "verified_superadmin_jwt_required" }, 403);
  const requestedDataset = new URL(request.url).searchParams.get("dataset") ?? "all";
  const selected = datasets[requestedDataset];
  if (!selected) return json({ error: "invalid_dataset", supported: Object.keys(datasets) }, 400);

  const auditStart = await admin.from("admin_audit_logs").insert({
    actor_profile_id: actor.profileId, actor_role: "superadmin", action: "VAULT_EXPORT_STARTED",
    target_type: "vault_export", target_id: requestedDataset, outcome: "started",
    metadata: { datasets: selected.map(s => s.label), jwt_id: String(actor.claims.jti) },
  }).select("id").single();
  if (auditStart.error || !auditStart.data?.id) return json({ error: "audit_log_write_failed" }, 500);
  const auditId = String(auditStart.data.id);
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    async start(controller) {
      let totalRows = 0;
      try {
        controller.enqueue(encoder.encode("dataset,record_id,exported_at,payload_json\r\n"));
        const exportedAt = new Date().toISOString();
        for (const dataset of selected) {
          let cursor: string | null = null;
          while (true) {
            let query = admin.from(dataset.table).select("*").order(dataset.key, { ascending: true }).limit(500);
            if (cursor !== null) query = query.gt(dataset.key, cursor);
            const { data, error } = await query;
            if (error) throw new Error("vault_query_failed:" + dataset.table + ":" + error.message);
            const rows = data ?? [];
            for (const row of rows) {
              const recordId = String((row as Record<string, unknown>)[dataset.key] ?? "");
              controller.enqueue(encoder.encode([
                csvCell(dataset.label), csvCell(recordId), csvCell(exportedAt), csvCell(row),
              ].join(",") + "\r\n"));
              totalRows++;
            }
            if (rows.length < 500) break;
            cursor = String((rows[rows.length - 1] as Record<string, unknown>)[dataset.key] ?? "");
            if (!cursor) throw new Error("vault_cursor_missing:" + dataset.table);
          }
        }
        const complete = await admin.from("admin_audit_logs").update({
          outcome: "success", metadata: { datasets: selected.map(s => s.label), exported_rows: totalRows },
        }).eq("id", auditId);
        if (complete.error) throw new Error("vault_audit_completion_failed");
        controller.close();
      } catch (error) {
        await admin.from("admin_audit_logs").update({
          outcome: "failed", metadata: { datasets: selected.map(s => s.label), exported_rows_before_failure: totalRows },
        }).eq("id", auditId);
        console.error("vault export stream failed", error instanceof Error ? error.message : "unknown");
        controller.error(error);
      }
    },
  });
  return new Response(stream, {
    status: 200,
    headers: {
      ...corsHeaders,
      "content-type": "text/csv; charset=utf-8",
      "content-disposition": 'attachment; filename="vibesync-vault-' + requestedDataset + '.csv"',
      "x-content-type-options": "nosniff",
    },
  });
});
