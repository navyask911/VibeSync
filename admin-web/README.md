# VibeSync Admin Web

Static staff portal served by GitHub Pages at https://admin.vibesync.social.

## Public browser configuration

Add a GitHub repository **Actions variable** named `SUPABASE_PUBLISHABLE_KEY` (preferred) or `SUPABASE_ANON_KEY` containing this project's public anon/publishable key. Optionally set `SUPABASE_URL`; otherwise the workflow uses the VibeSync project URL. Publishable/anon keys are intended for browser use and remain constrained by RLS and server-side role checks. Never configure a service-role/secret key as a Pages variable.

The workflow injects the key only into the Pages deployment artifact. The checked-in `config.js` contains no key. Without the variable, Pages can deploy but sign-in remains disabled.

## Provision a staff member

1. Create the user's account in Supabase Auth.
2. Using a trusted SQL editor/server-side admin path, insert the Auth UUID into `public.admin_users` with one role: `super_admin`, `moderator`, `finance_manager`, or `compliance_officer`.
3. Set `is_active = true`. The browser cannot insert, update or grant itself a role.

Example only — replace every placeholder and never commit a real UUID or staff email:

```sql
INSERT INTO public.admin_users(user_id, email, display_name, role, is_active)
VALUES ('<auth-user-uuid>', '<staff-email>', '<display-name>', 'super_admin', true);
```

An authenticated user without an active roster row is signed out before dashboard data is loaded.

## Sections and trust boundaries

- Telemetry uses `vibesync_get_admin_telemetry()` with periodic refresh and best-effort Realtime invalidation.
- Verification updates the free `profiles.is_verified` field only; it does not alter paid tiers, subscriptions or payment state. Ban/spam changes are role checked and audit logged.
- Payment data is returned by a dedicated finance RPC. The UI honestly marks webhook delivery as unobserved because the current schema has no persisted provider-delivery event ledger.
- Legal-process search accepts Section 94 BNSS / MLAT basis and a case reference, returns non-content account metadata only, and records a timestamped SHA-256 seal. This UI does not determine legal validity; the operator must verify process and approvals separately.
- Re-engagement runs in the `admin-fcm-broadcast` Edge Function, requires an active super admin, has a per-user cooldown and a 500-profile batch ceiling, and keeps FCM tokens and the Firebase service account on the server. Only profiles with recorded `last_active_at` heartbeats are eligible.
- Data-preservation exports are paged through a static table/column allow-list by role. Message contents/ciphertext, authentication credentials, private keys, FCM tokens, phone numbers, contact hashes and raw media are excluded.

## Deploy prerequisites

- Add a DNS CNAME for `admin` pointing to `navyask911.github.io`; configure the custom domain in repository Settings → Pages and wait for GitHub's DNS/HTTPS validation.
- Set the repository's Pages source to GitHub Actions.
- Set the public Supabase Actions variable noted above.
- Apply the Supabase migration and deploy the `admin-fcm-broadcast` function. Configure `FIREBASE_SERVICE_ACCOUNT_JSON` only in Supabase Edge Function secrets, never in this repository or Pages variables.
- Add the portal origin to Supabase Auth's allowed redirect URLs and ensure the email OTP/magic-link template is configured.
- Provision and test each role, including denial of inactive staff and normal end-user accounts, before production use.
