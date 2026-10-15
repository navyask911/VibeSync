# VibeSync Admin Portal Access

## Web portal

The staff portal is hosted separately from the Android APK at **https://admin.vibesync.social**. It uses Supabase Auth email OTP/magic-link sign-in and only renders operational dashboard state after the authenticated Auth UUID is found in `public.admin_users` with `is_active = true`.

The Android in-app mock Admin Ops screen and its destructive “wipe all backend data” action have been removed. From the app, a short press on the VibeSync logo returns to the Home/first tab; a long press opens the web portal in a Chrome Custom Tab (with a normal browser fallback). This gesture is navigation only, not an authorization mechanism.

## Roles and provisioning

The database supports `super_admin`, `moderator`, `finance_manager`, and `compliance_officer`. Staff are provisioned out of band by a project owner using the trusted Supabase SQL editor or server-side administrative path. The browser cannot create staff or assign itself privileges.

```sql
INSERT INTO public.admin_users(user_id, email, display_name, role, is_active)
VALUES ('<auth-user-uuid>', '<staff-email>', '<display-name>', 'super_admin', true);
```

Replace every placeholder. Do not commit staff UUIDs or personal email addresses. Remove access by setting `is_active = false`. Avoid local fallback credentials such as `creator / 9999`: an in-app prompt is not a secure identity provider and must never authorize staff access.

## GitHub Pages configuration

- Repository Pages source: GitHub Actions.
- DNS CNAME: `admin` → `navyask911.github.io`.
- First enable Pages in repository Settings → Pages with GitHub Actions as the source; the workflow token cannot create the Pages site itself in this repository. Configure the public key through the `SUPABASE_PUBLISHABLE_KEY` / `SUPABASE_ANON_KEY` Actions variable or the existing `SUPABASE_ANON_KEY` Actions secret. The workflow reads the existing `SUPABASE_URL` variable/secret or uses the project URL fallback.
- Supabase Auth redirect allow-list should include `https://admin.vibesync.social/`.
- Never expose Supabase service-role keys, Firebase service-account JSON, database passwords, partner keys, or signing keys to Pages, browser JavaScript, or the APK.
- Configure `FIREBASE_SERVICE_ACCOUNT_JSON` in Supabase Edge Function secrets for server-side FCM re-engagement. It is not needed for login and must not be stored in this repository.

## Server-side permissions and limitations

- The telemetry, free verification, moderation, finance, compliance and export operations are implemented as SECURITY DEFINER RPCs with fixed `search_path`, explicit role checks, and audit entries where state changes or controlled exports occur.
- Free profile verification changes `profiles.is_verified` only. It does not change paid verification tiers or subscription/payment state.
- Compliance exports contain only profile/account metadata, exclude content and keys, and include a SHA-256 digest plus database audit record. The operator must independently verify legal process and organizational approval before disclosing the result.
- Data preservation is bounded to role-specific, static allow-listed operational tables. Message contents/ciphertext, phone numbers, contact hashes, auth credentials, private keys, device tokens and raw media are not exportable through this UI.
- Provider webhook health is shown as unverified until event delivery records are implemented. A successful payment order is not proof that a provider webhook endpoint is healthy.
- Inactivity cohorts use `last_active_at`, recorded via a server-validated app-resume heartbeat. Older profiles with no recorded heartbeat are not included in push broadcasts.
- FCM re-engagement uses an authenticated Edge Function, a super-admin role check, a per-user cooldown, and a limited batch size. Validate the target audience before sending.

## Verification before production

1. Configure the public key variable and Auth email redirects.
2. Apply the SQL migration and verify each RPC exists.
3. Deploy the `admin-fcm-broadcast` Edge Function and set its server-only Firebase service account secret.
4. Provision a test identity for each role; confirm users without roster membership are denied and each role's forbidden sections/actions are rejected by Postgres.
5. Confirm GitHub Pages DNS and HTTPS are active. A successful code commit does not by itself prove custom-domain validation or production migration application.
