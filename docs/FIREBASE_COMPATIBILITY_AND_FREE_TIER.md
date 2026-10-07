# Firebase Compatibility & No-Paid-Tier Policy

Copyright © 2026 VibeSync. All rights reserved.

## Approved use of the existing Firebase project
The existing Firebase project may remain connected during transition. Existing Firebase datasets may be read for migration, reconciliation, diagnostics, and user-facing compatibility where the current product requires it.

## Authoritative boundary
- Supabase Auth is the application identity authority.
- Supabase Postgres is the authoritative application data store.
- Firebase Cloud Messaging (FCM) remains the push transport.
- Existing Firebase Realtime Database / Firestore data is legacy or compatibility state unless a feature specification explicitly says otherwise.
- No new feature may silently create an additional permanent Firebase data silo.

## Migration rule
Legacy records should be copied into the minimum required Supabase schema, deduplicated with stable identifiers, and marked with source metadata such as `legacy_firebase` only when that metadata is actually needed for reconciliation. Raw credentials and service-account keys are never stored in the Android app.

## No-paid-subscription rule
VibeSync engineering must not enable Blaze or any paid Firebase/Supabase feature as part of normal development. If real usage later exceeds a free-tier limit, that is a product/operations decision requiring explicit owner approval before activation.

## Service-account handling
Firebase Admin SDK credentials are server-only. A service-account JSON/private key must never be committed, embedded in the APK, exposed in logs, or pasted into chat. The repository may contain only non-secret Firebase client configuration required by Android SDKs.

## FCM
The existing Firebase Cloud Messaging API (HTTP v1) configuration can continue to deliver VibeSync notifications. Push payloads must not carry message plaintext when the feature is private encrypted messaging; they should carry an encrypted payload or an opaque event reference.

## Legacy messaging
Existing RTDB/Firestore message queues are treated as migration/compatibility paths. New production message writes should target the hardened VibeSync transport path. Any legacy reader must fail closed on invalid or plaintext private-message payloads.

## Operational safety
Migration jobs must be idempotent, rate-limited, resumable, and bounded so they do not accidentally create runaway reads/writes or unexpected billing.