# VibeSync

**VibeSync is a privacy-first Android social, communication, discovery, and business platform built for local-first operation and controlled serverless infrastructure.**

## What VibeSync is

VibeSync brings together adult social and relationship discovery, private communication, phonebook-based contact discovery, business tools, wallet/points, sponsored placements, safety reporting, and account recovery.

The application is Kotlin + Jetpack Compose with Room for local state, Supabase for authoritative application services, Google Tink for supported message encryption, Firebase Cloud Messaging (FCM) for push transport, and object storage for large media.

## Architecture and ownership

~~~text
Android / Kotlin / Compose
        |
       Room
        |
 local-first state
        |
   +----+-------------------+
   |                        |
Supabase Auth          Google Tink
   |                        |
   +-----------+------------+
               |
        Supabase / Postgres
               |
        Edge Functions + RPC
          |       |       |
         FCM    Realtime  R2
~~~

**Authoritative application backend:** Supabase Auth + Postgres + reviewed RPCs/Edge Functions.

**Firebase boundary:** FCM remains a push transport. Existing Firebase Realtime Database / Firestore records may remain available only for explicitly reviewed migration, reconciliation, diagnostics, or legacy-compatibility paths. No new feature should silently introduce a permanent Firebase application-data silo.

**Local data:** Room is the device-local cache/storage boundary. It is not the identity, accounting, or legal authority.

**Cryptography:** Supported private-message flows use Google Tink and fail closed when encryption/decryption requirements are not met. VibeSync does not claim to be impossible to hack.

**Media:** Large objects belong in object storage such as Cloudflare R2; Postgres should retain metadata/references instead of binary media.

## Privacy model

VibeSync follows data minimization:

- raw device address books remain on the device;
- contact discovery uses locally derived pseudonymous representations;
- plain SHA-256 phone hashing is not described as formal zero-knowledge cryptography;
- phone numbers are not the primary account identity;
- Supabase Auth UUIDs are the authoritative account identifiers;
- private-message transport rejects plaintext where encrypted transport is required.

See docs/CONTACT_DISCOVERY.md and docs/PRIVACY_AND_CONTACT_DISCOVERY.md.

## Safety and trust

The product includes adult-first dating/romantic discovery controls, block/report/moderation flows, account deletion and local-data clearing, lawful-request handling, no universal decryption backdoor, and explicit advertising disclosures.

See docs/MINOR_SAFETY.md, docs/LAW_ENFORCEMENT_AND_DATA_REQUESTS.md, and docs/AD_POLICY.md.

## Business platform

Business features are designed around transparent accounting and first-party controls: business profiles and venue discovery, follower broadcasts, VibeSync Points, campaign/impression accounting, direct business messaging/API concepts, and native sponsored placement.

The current product specification uses 1 VibeSync Point = INR 0.05. Real payments should be handled by approved payment providers; VibeSync should not store card credentials.

See docs/BUSINESS_PLATFORM.md.

## Firebase and free-tier boundary

The existing Firebase project may be used during transition, but it must not become an uncontrolled second application backend.

Approved uses include:

1. FCM push notification delivery;
2. explicitly reviewed legacy reads/migration/reconciliation;
3. bounded diagnostics where the feature still depends on legacy records.

Service-account credentials are server-only. They must never be committed, embedded in the APK, logged, or pasted into chat.

VibeSync development must not enable paid Firebase/Blaze billing without explicit owner approval.

See docs/FIREBASE_COMPATIBILITY_AND_FREE_TIER.md.

## Testing and release gates

Every release candidate must pass:

- Standard `./gradlew test` unit + JVM Compose UI tests;
- secret scanning;
- debug APK packaging;
- signed release APK packaging;
- signed release AAB packaging;
- artifact existence verification.

CI uploads:

~~~text
VibeSync-Android/
├── debug APK
├── signed release APK
├── signed release AAB
├── JVM test reports
└── instrumented Android test reports
~~~

See docs/TESTING.md and docs/RELEASE_SECURITY_CHECKLIST.md.

## Build requirements

- Android SDK 35
- Java 21
- Gradle 9.3.1
- Release commands use `./gradlew`

Local commands:

~~~bash
gradle test
gradle assembleDebug
gradle assembleRelease
gradle bundleRelease
~~~

Release signing is supplied through environment variables or CI secrets. The release keystore is never committed.

## Secrets

Server-only credentials must live in secure provider secret storage. Never embed them in Android source.

Examples include Supabase service-role credentials, Firebase service-account JSON, server-side AI credentials, R2 signing credentials, and release keystore/password material.

Android builds may contain only the minimum public client configuration required for the selected SDKs.

## Repository map

~~~text
app/                         Android application
supabase/migrations/         Database migrations
supabase/functions/          Edge/serverless functions
docs/                        Architecture, safety, privacy and release controls
scripts/                     Setup and verification scripts
.github/workflows/           CI/release gates
~~~

## Legal and policy documents

PRIVACY.md — privacy/data handling baseline  
TERMS.md — user and business terms baseline  
LICENSE — VibeSync proprietary license  
NOTICE — proprietary + third-party notice  
SUPPORT.md — support, privacy and abuse request process  
SECURITY.md — security reporting and security boundary  
docs/COMPLIANCE_MATRIX.md — India-focused compliance/control mapping

These documents are product and engineering baselines, not legal advice. Public launch should receive jurisdiction-specific legal review.

## Current release status

The repository is under an active hardening and release-validation program. A release is considered ready only when the CI release gate is green, signed APK/AAB artifacts are present, the UI smoke suite passes, and backend/security configuration has been reviewed.

Copyright © 2026 VibeSync. All rights reserved.
