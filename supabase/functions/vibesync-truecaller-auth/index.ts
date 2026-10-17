import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const corsHeaders = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "authorization, x-client-info, apikey, content-type",
  "access-control-allow-methods": "POST, OPTIONS",
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};

Deno.serve((req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { status: 200, headers: corsHeaders });
  return new Response(
    JSON.stringify({
      error: "endpoint_decommissioned",
      detail: "Use https://imhcbgpvjwersbnlgwzq.supabase.co/functions/v1/verify-truecaller",
    }),
    { status: 410, headers: corsHeaders },
  );
});
