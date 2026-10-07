import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const anonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

const responseJson = (data: unknown, status = 200) => new Response(JSON.stringify(data), {
  status,
  headers: { "content-type": "application/json; charset=utf-8" },
});

Deno.serve(async (req) => {
  if (req.method !== "POST") return responseJson({ error: "method_not_allowed" }, 405);
  if (!supabaseUrl || !anonKey || !serviceRoleKey) {
    return responseJson({ error: "server_configuration_incomplete" }, 503);
  }

  const authorization = req.headers.get("authorization") ?? "";
  if (!authorization.toLowerCase().startsWith("bearer ")) {
    return responseJson({ error: "authentication_required" }, 401);
  }

  const userClient = createClient(supabaseUrl, anonKey, {
    global: { headers: { Authorization: authorization } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: userData, error: userError } = await userClient.auth.getUser();
  if (userError || !userData.user) return responseJson({ error: "invalid_session" }, 401);

  const userId = userData.user.id;
  const adminClient = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  try {
    await adminClient.from("device_tokens").delete().eq("user_id", userId);
    await adminClient.from("phone_sync").delete().eq("auth_user_id", userId);
    await adminClient.from("registered_accounts").delete().eq("auth_user_id", userId);
    await adminClient.from("profiles").delete().eq("auth_user_id", userId);
    await adminClient.from("chat_messages").delete().or(`sender_user_id.eq.${userId},receiver_user_id.eq.${userId}`);

    const { error: deleteError } = await adminClient.auth.admin.deleteUser(userId);
    if (deleteError) throw deleteError;

    return responseJson({ ok: true, deleted_user_id: userId });
  } catch (error) {
    return responseJson({
      error: "account_deletion_failed",
      detail: String((error as Error)?.message ?? error).slice(0, 300),
    }, 500);
  }
});