import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = (() => {
  try {
    const parsed = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "");
    if (typeof parsed?.default === "string" && parsed.default) return parsed.default;
  } catch { /* legacy env below */ }
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
})();
const RAZORPAY_KEY_ID = Deno.env.get("RAZORPAY_KEY_ID") ?? "";
const RAZORPAY_KEY_SECRET = Deno.env.get("RAZORPAY_KEY_SECRET") ?? "";
const admin = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, { auth: { persistSession: false, autoRefreshToken: false } });
const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-vibesync-session",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Cache-Control": "no-store",
};
function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { ...cors, "content-type": "application/json; charset=utf-8" } });
}
async function sha256(value: string) {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes)).map(v => v.toString(16).padStart(2, "0")).join("");
}
async function resolveProfileId(token: string): Promise<string | null> {
  if (!token) return null;
  const hash = await sha256(token);
  const { data, error } = await admin.from("vibesync_sessions").select("profile_id")
    .eq("session_hash", hash).gt("expires_at", new Date().toISOString()).maybeSingle();
  if (error || !data?.profile_id) return null;
  return String(data.profile_id);
}
function constantTimeEquals(left: string, right: string) {
  if (left.length !== right.length) return false;
  let diff = 0;
  for (let i = 0; i < left.length; i++) diff |= left.charCodeAt(i) ^ right.charCodeAt(i);
  return diff === 0;
}
async function signHmacHex(secret: string, value: string) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const signature = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(value));
  return Array.from(new Uint8Array(signature)).map(v => v.toString(16).padStart(2, "0")).join("");
}
function computeBaseAmount(type: string, points: number, tier: string) {
  if (type === "WALLET_RECHARGE") return Math.round(points * 5) / 100;
  if (type === "VERIFICATION_BADGE") return tier === "GOLD" ? 499 : tier === "SILVER" ? 299 : tier === "BLUE_TICK" ? 199 : 0;
  return 0;
}
async function createOrder(profileId: string, body: Record<string, unknown>) {
  if (!RAZORPAY_KEY_ID || !RAZORPAY_KEY_SECRET) return json({ ok: false, error: "razorpay_keys_not_configured" }, 503);
  const businessId = String(body.business_id ?? "").trim();
  const type = String(body.purchase_type ?? "").trim().toUpperCase();
  const points = Number(body.points ?? 0);
  const tier = String(body.verification_tier ?? "").trim().toUpperCase();
  if (!businessId || !["WALLET_RECHARGE", "VERIFICATION_BADGE"].includes(type)) return json({ ok: false, error: "invalid_order" }, 400);
  if (type === "WALLET_RECHARGE" && (!Number.isInteger(points) || points < 100 || points > 1000000)) return json({ ok: false, error: "invalid_points" }, 400);
  if (type === "VERIFICATION_BADGE" && !["GOLD", "SILVER", "BLUE_TICK"].includes(tier)) return json({ ok: false, error: "invalid_tier" }, 400);

  const { data: business, error: businessError } = await admin.from("businesses").select("id,owner_profile_id")
    .eq("id", businessId).eq("owner_profile_id", profileId).eq("is_published", true).maybeSingle();
  if (businessError || !business) return json({ ok: false, error: "business_owner_required" }, 403);

  const base = computeBaseAmount(type, points, tier);
  if (!Number.isFinite(base) || base <= 0) return json({ ok: false, error: "invalid_amount" }, 400);
  const gst = Number((base * 0.18).toFixed(2));
  const total = Number((base + gst).toFixed(2));
  const amountPaise = Math.round(total * 100);
  const { data: orderRow, error: insertError } = await admin.from("business_payment_orders").insert({
    business_id: businessId, owner_profile_id: profileId, purchase_type: type,
    points: type === "WALLET_RECHARGE" ? points : 0,
    verification_tier: type === "VERIFICATION_BADGE" ? tier : "",
    base_amount_inr: base, gst_amount_inr: gst, total_amount_inr: total, provider: "RAZORPAY", status: "PENDING",
  }).select("id").single();
  if (insertError || !orderRow?.id) return json({ ok: false, error: "payment_order_store_failed" }, 500);

  const providerResponse = await fetch("https://api.razorpay.com/v1/orders", {
    method: "POST",
    headers: { "Authorization": "Basic " + btoa(RAZORPAY_KEY_ID + ":" + RAZORPAY_KEY_SECRET), "Content-Type": "application/json" },
    body: JSON.stringify({
      amount: amountPaise, currency: "INR", receipt: String(orderRow.id).replace(/-/g, "").slice(0, 40),
      notes: { vibesync_payment_order_id: String(orderRow.id), business_id: businessId, purchase_type: type },
    }),
  });
  const providerText = await providerResponse.text();
  if (!providerResponse.ok) {
    await admin.from("business_payment_orders").update({ status: "FAILED" }).eq("id", orderRow.id);
    return json({ ok: false, error: "razorpay_order_creation_failed", provider_status: providerResponse.status }, 502);
  }
  const providerOrder = JSON.parse(providerText);
  const { error: linkError } = await admin.from("business_payment_orders").update({ provider_order_id: String(providerOrder.id) })
    .eq("id", orderRow.id).eq("status", "PENDING");
  if (linkError) return json({ ok: false, error: "payment_order_link_failed" }, 500);
  return json({
    ok: true, order_record_id: String(orderRow.id), razorpay_order_id: String(providerOrder.id),
    key_id: RAZORPAY_KEY_ID, currency: "INR", amount_paise: amountPaise,
    base_amount_inr: base, gst_amount_inr: gst, total_amount_inr: total,
    purchase_type: type, points: type === "WALLET_RECHARGE" ? points : 0, verification_tier: tier,
  });
}
async function verifyPayment(profileId: string, body: Record<string, unknown>) {
  if (!RAZORPAY_KEY_ID || !RAZORPAY_KEY_SECRET) return json({ ok: false, error: "razorpay_keys_not_configured" }, 503);
  const recordId = String(body.order_record_id ?? "").trim();
  const paymentId = String(body.razorpay_payment_id ?? "").trim();
  const signature = String(body.razorpay_signature ?? "").trim();
  if (!recordId || !paymentId || !signature) return json({ ok: false, error: "payment_verification_fields_required" }, 400);
  const { data: order, error: orderError } = await admin.from("business_payment_orders").select("*")
    .eq("id", recordId).eq("owner_profile_id", profileId).maybeSingle();
  if (orderError || !order || !order.provider_order_id) return json({ ok: false, error: "payment_order_not_found" }, 404);
  if (order.status === "PAID" && order.provider_payment_id === paymentId) return json({ ok: true, already_paid: true, transaction_id: paymentId });

  const expectedSignature = await signHmacHex(RAZORPAY_KEY_SECRET, String(order.provider_order_id) + "|" + paymentId);
  if (!constantTimeEquals(expectedSignature, signature)) return json({ ok: false, error: "razorpay_signature_invalid" }, 403);

  // Never trust the client success callback alone. Confirm captured status, order and amount against
  // Razorpay's authenticated API before the database RPC credits points or activates a badge.
  const providerResponse = await fetch("https://api.razorpay.com/v1/payments/" + encodeURIComponent(paymentId), {
    headers: { "Authorization": "Basic " + btoa(RAZORPAY_KEY_ID + ":" + RAZORPAY_KEY_SECRET) },
  });
  if (!providerResponse.ok) return json({ ok: false, error: "razorpay_payment_lookup_failed" }, 502);
  const payment = await providerResponse.json();
  if (String(payment.order_id) !== String(order.provider_order_id) || payment.status !== "captured" ||
      Number(payment.amount) !== Math.round(Number(order.total_amount_inr) * 100) || String(payment.currency) !== "INR") {
    return json({ ok: false, error: "payment_not_captured_or_amount_mismatch" }, 409);
  }
  const { data: settled, error: settleError } = await admin.rpc("complete_business_payment_order", { p_order_id: recordId, p_payment_id: paymentId });
  if (settleError || !settled?.ok) return json({ ok: false, error: "wallet_settlement_failed" }, 500);
  return json({ ...settled, ok: true });
}
Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (request.method !== "POST") return json({ ok: false, error: "method_not_allowed" }, 405);
  if (!SUPABASE_URL || !SERVICE_ROLE_KEY) return json({ ok: false, error: "payment_backend_not_configured" }, 503);
  try {
    const profileId = await resolveProfileId(request.headers.get("x-vibesync-session") ?? "");
    if (!profileId) return json({ ok: false, error: "verified_vibesync_session_required" }, 401);
    const body = await request.json() as Record<string, unknown>;
    const action = String(body.action ?? "");
    if (action === "create_order") return await createOrder(profileId, body);
    if (action === "verify_payment") return await verifyPayment(profileId, body);
    return json({ ok: false, error: "unsupported_action" }, 400);
  } catch (error) {
    console.error("wallet-payment failure", error instanceof Error ? error.message : "unknown");
    return json({ ok: false, error: "wallet_payment_failed" }, 500);
  }
});
