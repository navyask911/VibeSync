# VibeSync governance deployment notes

## Private runtime configuration

Configure Edge Function secrets in Supabase (never commit values to Git):

- `SUPERADMIN_PHONE`: E.164 number permitted to receive the VibeSync superadmin claim. Defaults in `verify-truecaller` to `+919743040335` if unset.
- `RAZORPAY_WEBHOOK_SECRET`: Razorpay Webhook Secret used for HMAC-SHA256 signature verification. This is distinct from the Razorpay API key secret.
- Existing payment configuration remains in Edge Function secrets: `RAZORPAY_KEY_ID` and `RAZORPAY_KEY_SECRET`.
- Supabase provides `SUPABASE_SECRET_KEYS` (or legacy `SUPABASE_SERVICE_ROLE_KEY`) to server-only Edge Functions. Never expose those keys to an Android/web client.

Set or rotate values from a trusted terminal or CI secret step with `supabase secrets set KEY=value`; do not put literal values in this repository.

## Custom access token hook

The SQL migration creates `public.custom_access_token_hook(jsonb)` and grants execution only to `supabase_auth_admin`. In the Supabase Dashboard, enable **Authentication → Hooks → Custom Access Token** and select:

`pg-functions://postgres/custom_access_token_hook`

VibeSync's current Truecaller exchange issues its own signed JWT, so `verify-truecaller` embeds `is_superadmin` and `app_metadata.role` directly after server-side verification. The top-level JWT `role` remains `authenticated`, the valid PostgREST database role.

## Razorpay webhook

Set the Razorpay webhook URL to `https://<project-ref>.supabase.co/functions/v1/razorpay-webhook`, select `payment.captured` and `order.paid`, and configure the same webhook secret in Supabase Function secrets. The handler verifies HMAC over the raw request bytes before calling the service-role-only `process_payment_activation` RPC.

## Vault export

Only a signed, unexpired VibeSync JWT with `is_superadmin=true`, `app_metadata.role=superadmin`, a live `vibesync_sessions` row, and a matching `super_admins` membership is accepted.

- `GET /functions/v1/export-vault` streams a unified CSV export for profiles, businesses, wallet balances, and wallet ledger.
- Optional `?dataset=profiles`, `?dataset=businesses`, or `?dataset=wallets` narrows the export.
- Export start/completion/failure is recorded in `admin_audit_logs`.
