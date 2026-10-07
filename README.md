# VibeSync

VibeSync is a proprietary Android social and business platform built around privacy-preserving contact discovery, local-first data, serverless backend services, secure messaging, and business communication tools.

## Architecture

- Kotlin + Jetpack Compose Android application.
- Room for local-first persistence and offline UX.
- Supabase/PostgreSQL as the authoritative application backend.
- Object storage for large media where configured.
- Serverless functions for privileged business logic.
- Firebase Cloud Messaging (FCM) only for Android push notifications.
- Google Tink for application cryptography.
- Truecaller remains a test/development authentication integration until a production-approved authentication contract is finalized.

## Privacy-first contact discovery

The device address book is not intended to become a permanent cloud database. Contact discovery should use privacy-preserving identifiers and minimal match results. Plain SHA-256 phone hashing must not be described as zero-knowledge cryptography.

## Credentials

Never commit Android signing keys, Firebase service-account private keys, Supabase service-role/secret keys, Gemini API keys, or Cloudflare/R2 secret credentials.

## Build

Release artifacts are unsigned unless CI signing secrets are provided. Never commit a keystore.

## Legal

See LICENSE, PRIVACY.md, TERMS.md, and SECURITY.md.
