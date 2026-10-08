# VibeSync Architecture & Data Flow

Copyright © 2026 VibeSync. All rights reserved.

## Product architecture
VibeSync is an Android social and business platform built around local-first storage, privacy-preserving identity discovery, secure private communication, serverless privileged operations, and lightweight backend state.

## System boundaries
- Android client: Kotlin, Jetpack Compose, Room, Android Keystore, Google Tink.
- Supabase: authoritative application database, authentication, controlled RPC/Edge Functions, and application-side realtime where required.
- Firebase: existing Firebase project may be used as a legacy compatibility/read source and for Firebase Cloud Messaging (FCM) delivery. New authoritative application writes remain in Supabase. Firestore/Realtime Database/Storage/Auth are not authoritative for new VibeSync state. A migration worker may read legacy Firebase data only when explicitly enabled and within the existing no-cost operational boundary.
- Object storage: Cloudflare R2 or another configured object store for large media. Database rows contain references and metadata, not media binaries.
- AI: Gemini access belongs behind a server-side/Edge Function boundary. Secrets must never ship in the APK.

## Local-first principle
Room is the immediate user experience and offline cache. Network synchronization is explicit and idempotent. The device must remain useful when the network is unavailable.

## Messaging target flow
1. Sender completes Truecaller 1-tap verification; the Android client stores only the resulting local verified session state.
2. Sender encrypts the message locally with the recipient's published VibeSync Tink key.
3. A serverless dispatch operation validates the sender and recipient relationship.
4. The opaque encrypted payload is forwarded through FCM.
5. The recipient decrypts locally and persists the plaintext only in local protected storage.
6. The recipient sends a delivery acknowledgment through an authenticated VibeSync endpoint.
7. Delivery status is reflected back to the sender without creating a permanent plaintext message archive.

## Scaling principle
Postgres stores compact structured state. Large media is kept in object storage. Contact books stay on devices. Temporary delivery state is short-lived and aggressively expired. This design is intended to minimize persistent database growth; actual capacity is measured using bytes/user, writes/user/day, egress/user, storage/user, concurrent connections, and function invocations.

## Non-goals
VibeSync does not promise that any software is impossible to hack. Security claims are limited to controls that are actually implemented, tested, and documented.