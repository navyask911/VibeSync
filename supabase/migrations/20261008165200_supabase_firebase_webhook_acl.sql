-- Keep the trigger function internal. It is invoked by PostgreSQL triggers
-- and must not be exposed through the PostgREST RPC surface.

revoke all on function public.forward_chat_row_change_to_firebase()
from public, anon, authenticated, service_role;
