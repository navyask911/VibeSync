# Truecaller server-side token exchange

Android posts the OAuth authorization code, PKCE verifier, and state to `verify-truecaller`. The function exchanges the one-time code with Truecaller and fetches the profile from Truecaller's fixed userinfo endpoint. Client phone/profile IDs and arbitrary endpoint URLs are never trusted. Legacy SDK access tokens use the fixed phone-number-detail endpoint with the server-only `TRUECALLER_PARTNER_KEY`.

## Required server secrets

- `SUPABASE_SERVICE_ROLE_KEY` (or the project-provided `SUPABASE_SECRET_KEY`) for server-only account/session provisioning.
- `APP_JWT_SECRET` (fallback `JWT_SECRET`) for HS256 signing.
- `TRUECALLER_PARTNER_KEY` for the legacy access-token verification path.
- `TRUECALLER_CLIENT_ID` is optional because the registered project ID is the default.

## Deployment

1. Confirm `APP_JWT_SECRET` is the exact HS256 signing secret trusted by this Supabase project's API gateway. A random Edge Function secret alone is insufficient for PostgREST/Realtime to accept this JWT. Projects using only asymmetric signing keys must configure a compatible signing/verifying key first.
2. Apply migrations: `supabase db push`.
3. Deploy: `supabase functions deploy verify-truecaller`.

The 30-day JWT includes role=authenticated, sub=profile ID, verified phone, exp, and a unique jti. Its jti is tied to `public.vibesync_sessions` for session revocation. Android stores the JWT in an Android Keystore-backed AES-GCM encrypted session and uses it as the PostgREST/Realtime bearer token. The opaque session token remains for legacy RPCs requiring `x-vibesync-session`.

The documented OAuth authorization-code + PKCE route uses client ID, authorization code, and verifier. The partner key is used for the legacy partner-token verification route.
