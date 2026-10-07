# VibeSync

**VibeSync is a proprietary Android social + business platform built around privacy-preserving contact discovery, local-first operation, secure private communication, and a low-persistent-state serverless backend.**

## Product purpose

VibeSync combines:

- private contact discovery without maintaining a user's complete address book as a cloud dataset;
- profile discovery, connection/matching, and private conversations;
- local-first/offline chat and media workflows;
- business profiles, broadcasts, wallet, analytics, and ethical native advertising;
- serverless backend control for authentication, authorization, push dispatch, business accounting, safety, and auditability.

## Production architecture

```text
Android / Kotlin + Compose
        |
       Room
        |
  local-first state
        |
        +------------------+
        |                  |
   Supabase Auth       Google Tink
        |                  |
        +--------+---------+
                 |
          Supabase/Postgres
                 |
        +--------+---------+
        |                  |
   Edge Functions       Realtime
        |
   +----+----+------------------+
   |         |                  |
  FCM      Gemini              R2
 push      server-side       large media
```

## Backend ownership

**Supabase** is authoritative for application identity, structured application state, controlled RPCs, Edge Functions, business accounting, and security policies.

**Firebase** is retained for **Firebase Cloud Messaging (FCM)** push delivery. The existing Firebase project/data may be used as a controlled legacy compatibility or migration source, but new authoritative VibeSync application data belongs in Supabase.

**Google Tink** is the application cryptography boundary for supported private-message encryption. Encryption failure is fail-closed.

**Room** is the local-first data/cache layer and is not a financial or identity authority.

**R2/object storage** holds large media. PostgreSQL should hold metadata/references rather than media binaries.

## Privacy

VibeSync follows data minimization:

- the complete device address book is not uploaded as a permanent cloud dataset;
- contact discovery uses locally derived privacy-preserving identifiers;
- plain SHA-256 phone hashing is **not** called zero-knowledge cryptography;
- a stronger PSI/OPRF-style protocol is the target for a future reviewed contact-discovery implementation;
- phone numbers are not the primary application identity;
- Supabase Auth UUIDs are the authoritative identities.

See `docs/CONTACT_DISCOVERY.md` and `docs/PRIVACY_AND_CONTACT_DISCOVERY.md`.

## Security

VibeSync does not make an impossible-to-hack claim. It uses defense in depth:

- authenticated Supabase RLS;
- server-authoritative privileged RPCs;
- Android Keystore protected session material;
- Google Tink encryption;
- secret scanning in CI;
- fail-closed encrypted-message handling;
- server-side FCM dispatch;
- short-lived/expired delivery state;
- server-authoritative business ledger controls.

See `SECURITY.md`, `docs/SECURITY_THREAT_MODEL.md`, and `docs/RELEASE_SECURITY_CHECKLIST.md`.

## Lawful requests and safety

VibeSync supports a documented process for lawful requests, preservation, abuse reporting, safety escalation, and auditable disclosure decisions. It does **not** provide a universal message-decryption backdoor.

See `docs/LAW_ENFORCEMENT_AND_DATA_REQUESTS.md` and `docs/MINOR_SAFETY.md`.

## Business platform

The business suite is designed for revenue generation before infrastructure needs to scale:

- ethical native CPM advertising;
- local-business discovery;
- follower broadcasts;
- business wallet/points;
- transaction and delivery accounting;
- direct business chat/API/webhooks.

The business specification defines **1 VibeSync Point = ₹0.05**. Actual payment processing remains with approved payment providers; VibeSync should retain transaction references rather than card credentials.

See `docs/BUSINESS_PLATFORM.md` and `docs/AD_POLICY.md`.

## Firebase and free-tier rule

The existing Firebase project is currently operated under the no-cost Firebase plan. VibeSync engineering must not activate paid Firebase/Blaze billing as part of this development track.

FCM remains the preferred push transport because push delivery itself is not the application data store.

See `docs/FIREBASE_COMPATIBILITY_AND_FREE_TIER.md`.

## Build

Requirements:
- Android SDK 35
- Java 21
- Gradle 9.3.1 in CI

Local build commands:

```bash
gradle test
gradle assembleDebug
gradle assembleRelease
gradle bundleRelease
```

Debug builds use the standard generated Android debug signing key.

Release builds use environment-provided signing values:
- `KEYSTORE_PATH`
- `STORE_PASSWORD`
- `KEY_PASSWORD`
- `KEY_ALIAS`

**Never commit the release keystore.**

## Required server secrets

Server-only secrets must live in secure provider secret storage. Never embed them in Android source:

- `SUPABASE_SERVICE_ROLE_KEY`
- `FIREBASE_SERVICE_ACCOUNT_JSON` for the FCM Edge Function
- Gemini server credential
- Cloudflare/R2 signing credentials

## Repository structure

```text
app/                         Android application
supabase/migrations/         database migrations
supabase/functions/          serverless functions
docs/                        architecture, privacy, security, legal baseline
scripts/                     controlled setup/verification scripts
tests/                       automated/non-Android tests where present
.github/                     CI and repository governance
```

## Proprietary rights

Copyright © 2026 VibeSync. All rights reserved.

The VibeSync source code, architecture, database design, business logic, UI/UX, product specifications, trademarks/branding, and documentation are proprietary unless a separate written license states otherwise.

See `LICENSE`, `NOTICE`, and `PRIVACY.md`.

## Development status

This repository is undergoing a security and architecture hardening pass. A release is considered ready only after automated tests, APK/AAB packaging, backend security review, secret review, and production configuration review all pass.