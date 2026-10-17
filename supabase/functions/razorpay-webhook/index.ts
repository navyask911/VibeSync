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
const WEBHOOK_SECRET = Deno.env.get("RAZORPAY_WEBHOOK_SECRET") ?? "";
const admin = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, { auth: { persistSession: false, autoRefreshToken: false } });
const headers = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "content-type, x-razorpay-signature",
  "access-control-allow-methods": "POST, OPTIONS",
  "cache-control": "no-store",
};
function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { ...headers, "content-type": "application/json; charset=utf-8" } });
}
function toHex(bytes: Uint8Array): string { return Array.from(bytes).map(b => b.toString(16).padStart(2,"0")).join(""); }
function constantTimeEqualsHex(left: string, right: string): boolean {
  const a=left.toLowerCase(); const b=right.toLowerCase();
  if (a.length !== b.length) return false;
  let mismatch=0; for (let i=0;i<a.length;i++) mismatch |= a.charCodeAt(i)^b.charCodeAt(i);
  return mismatch===0;
}
async function signatureFor(secret: string, rawBytes: Uint8Array): Promise<string> {
  const key=await crypto.subtle.importKey("raw",new TextEncoder().encode(secret),{name:"HMAC",hash:"SHA-256"},false,["sign"]);
  return toHex(new Uint8Array(await crypto.subtle.sign("HMAC",key,rawBytes)));
}
Deno.serve(async (request: Request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers });
  if (request.method !== "POST") return json({ ok:false,error:"method_not_allowed" },405);
  if (!SUPABASE_URL || !SERVICE_ROLE_KEY || !WEBHOOK_SECRET) return json({ ok:false,error:"razorpay_webhook_not_configured" },503);

  const rawBytes = new Uint8Array(await request.arrayBuffer());
  if (rawBytes.byteLength > 1_000_000) return json({ ok:false,error:"webhook_body_too_large" },413);
  const suppliedSignature = request.headers.get("x-razorpay-signature") ?? "";
  if (!suppliedSignature) return json({ ok:false,error:"signature_required" },401);
  const expectedSignature = await signatureFor(WEBHOOK_SECRET,rawBytes);
  if (!constantTimeEqualsHex(expectedSignature,suppliedSignature)) return json({ ok:false,error:"razorpay_signature_invalid" },403);

  let body: Record<string,unknown>;
  try { body=JSON.parse(new TextDecoder().decode(rawBytes)) as Record<string,unknown>; }
  catch { return json({ ok:false,error:"invalid_json" },400); }
  const eventName=String(body.event ?? "");
  if (!["payment.captured","order.paid"].includes(eventName)) return json({ ok:true,ignored:true,event:eventName });
  const payload=body.payload as Record<string,unknown>|undefined;
  const paymentPart=payload?.payment as Record<string,unknown>|undefined;
  const payment=paymentPart?.entity as Record<string,unknown>|undefined;
  if (!payment) return json({ ok:false,error:"payment_entity_missing" },400);
  const providerOrderId=String(payment.order_id ?? "");
  const providerPaymentId=String(payment.id ?? "");
  const amountPaise=Number(payment.amount ?? 0);
  const currency=String(payment.currency ?? "");
  const status=String(payment.status ?? "");
  if (!providerOrderId || !providerPaymentId || !Number.isSafeInteger(amountPaise) || amountPaise<=0)
    return json({ ok:false,error:"payment_details_invalid" },400);
  if (status!=="captured" || currency!=="INR") return json({ ok:false,error:"payment_not_captured_or_currency_invalid" },409);

  const { data,error }=await admin.rpc("process_payment_activation",{
    p_provider_order_id:providerOrderId,p_provider_payment_id:providerPaymentId,
    p_amount_paise:amountPaise,p_currency:currency,p_payment_status:status,
  });
  if (error || !data?.ok) {
    console.error("razorpay webhook settlement failed",error?.message ?? "activation_rejected");
    return json({ ok:false,error:"payment_activation_failed" },500);
  }
  return json({ ok:true,result:data });
});
