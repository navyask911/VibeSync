# VibeSync

**Made in India, for the World.** VibeSync is an Android-first social discovery, secure messaging, stories, calling and local-business platform. This repository contains the Android app and backend migrations/Edge Functions. It is proprietary source-available software; see LICENSE.

## Product areas

- Verified account onboarding through Truecaller.
- Kotlin and Jetpack Compose UI, Room local storage and WorkManager-backed retry.
- Supported private chat payloads encrypted client-side with Google Tink.
- Contact discovery through restricted server-side E.164 matching over HTTPS; this is not chat E2EE.
- Stories, voice notes, WebRTC call signaling and FCM wake-up transport.
- Business listing, wallet points, follower broadcasts, analytics and promotion tooling.
- Safety settings, blocking/reporting and account controls.

Feature availability depends on backend policy, migrations, configured provider credentials and the exact release commit. See PRODUCT_REQUIREMENTS.md, PRIVACY_POLICY.md and TERMS_OF_SERVICE.md for additional context.

## Architecture overview

~~~mermaid
flowchart TD
  Android["Android app - Kotlin + Compose"] --> Room["Room / local protected state"]
  Android --> TC["Truecaller verification"]
  Android --> Tink["Google Tink client-side encryption"]
  TC --> Verify["Supabase Edge Function: verify-truecaller"]
  Verify --> Session["Verified VibeSync session / JWT mapping"]
  Tink --> RPC["Supabase RPC + Postgres"]
  Session --> RPC
  RPC --> Storage["Supabase Storage"]
  RPC --> Realtime["Supabase Realtime / signaling"]
  RPC --> FCM["FCM wake-up / push transport"]
  Owner["Business owner UI"] --> Wallet["Transactional wallet + broadcast RPC"]
  Wallet --> Dispatch["Broadcast Edge Function"]
  Dispatch --> FCM
~~~

Supabase is the authoritative application backend. Room is the local/offline cache, not the identity or accounting authority. FCM is a push/wake channel; it must not carry plaintext private-message bodies. WebRTC media is sent through the WebRTC connection; Supabase Realtime is used for signaling rather than transporting the audio/video stream.

## Main repository layout

~~~text
app/src/main/java/com/example/
  data/                 Room entities, DAOs and repositories
  security/              Local security and crypto helpers
  service/               WorkManager outbox/sync workers
  ui/                    Compose screens, view models and components
  util/                  Supabase, Tink, auth, media and call integration
supabase/
  functions/             Edge Functions for verified/privileged operations
  migrations/            SQL schema, RLS, policies and RPCs
docs/
  PRODUCT_REQUIREMENTS.md
  PRIVACY_POLICY.md
  TERMS_OF_SERVICE.md
  LICENSE
  SECRET_BLUEPRINT.md
  ARCHITECTURE_AND_DATA_FLOW.md
  RELEASE_SECURITY_CHECKLIST.md
~~~

## Local development

Requirements: Android SDK configured, JDK 21, and the Gradle wrapper in this repository. Open the root directory in Android Studio or use the wrapper from a shell.

~~~bash
./gradlew --version
./gradlew test
./gradlew assembleDebug
~~~

Release builds additionally require release-signing inputs supplied through the approved local environment or CI secret store. Do not commit keystores, passwords, provider keys, Truecaller partner secrets, Supabase service-role/secret keys, user data or production exports.

Use the tracked .env.example as a template; supply actual configuration through an untracked .env or CI environment according to the repository workflow. Do not use production data for local tests. Run migrations only against the intended project after inspecting the migration plan and confirming the connected project identity.

## Backend development

- SQL changes belong in supabase/migrations and should be idempotent where feasible.
- Sensitive writes should run through verified-session RPCs or Edge Functions and use explicit RLS/storage policies.
- Service-role keys and provider secrets are backend-only. The Android app may contain a publishable project key but must never contain the service-role/secret key.
- Keep broadcasts, wallet debits and other accounting operations idempotent. The server recomputes target audience and wallet charge.
- The public avatars bucket intentionally exposes primary profile avatar images. Do not use it for secret/private media.
- Private chat payloads should be encrypted before transport; do not add plaintext to logs, FCM payloads or analytics.

## CI/CD

GitHub Actions workflows validate the Android build, scan tracked files for leaked secrets, verify Truecaller wiring, assemble/verify release artifacts and apply Supabase migrations. Check the Actions run attached to the exact commit before declaring a release ready.

A green migration workflow does not prove Android compiles. A successful compile does not prove a signed APK was produced or installed on a device. Verify each expected step and artifact separately.

## Security and privacy references

- [Architecture and data flow](ARCHITECTURE_AND_DATA_FLOW.md)
- [Contact discovery transparency](PRIVACY_AND_CONTACT_DISCOVERY.md)
- [Threat model](SECURITY_THREAT_MODEL.md)
- [Release security checklist](RELEASE_SECURITY_CHECKLIST.md)
- [Testing strategy](TESTING.md)
- [Privacy policy](PRIVACY_POLICY.md)
- [Terms of service](TERMS_OF_SERVICE.md)

## Confidentiality warning for maintainers

A Markdown file committed to a public repository is public, even when its name contains “SECRET” or “confidential”. The developer handbook intentionally contains no credentials, private keys, production tokens, database dumps or non-public personal data. Move operationally sensitive runbooks to a private repository or access-controlled knowledge base.
