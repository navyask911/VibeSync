# VibeSync Developer Blueprint — Restricted Operational Handbook

Copyright © 2026 VibeSync. Revision date: 15 October 2026.

> **Repository visibility warning:** This handbook contains architecture and troubleshooting guidance, not credentials. Because it is committed inside this repository, it is readable by anyone who can read the repository. If the project remains public, move this file to a private repository or access-controlled runbook system before treating it as confidential. Never add JWT signing keys, Truecaller partner keys, Supabase service-role keys, keystore passwords, real user identifiers or production request/response bodies here.

## 1. Trust boundaries

- Android device: local Room/cache, WorkManager queue, Android Keystore/keysets, Tink encryption/decryption, permissions and user-controlled UI.
- Truecaller: external identity assertion provider. The partner secret must remain in Edge Function environment secrets.
- Supabase: authoritative Postgres metadata and restricted RPCs, session/identity mappings, Realtime, Storage RLS and server-side orchestration.
- FCM: delivery/wake transport. Keep payloads minimal and exclude private plaintext.
- WebRTC: SDP/ICE signaling is metadata; media is carried over the WebRTC connection. Network path may be direct or use configured ICE/TURN infrastructure.
- User devices and providers remain trust boundaries: E2EE cannot protect plaintext after a recipient device decrypts it or after a recipient copies it.

## 2. Truecaller verification to custom VibeSync session

~~~mermaid
sequenceDiagram
  participant A as Android client
  participant T as Truecaller SDK
  participant E as verify-truecaller Edge Function
  participant TC as Truecaller token/userinfo APIs
  participant DB as Supabase Postgres
  A->>T: Start OAuth with state and PKCE data
  T-->>A: Authorization code and state
  A->>E: POST code, verifier and state
  E->>E: Apply rate limits and validate request state
  E->>TC: Exchange code using server-only partner secret
  TC-->>E: Access token
  E->>TC: Fetch verified userinfo
  TC-->>E: Verified phone and profile attributes
  E->>DB: Resolve canonical profile and restricted phone index
  E->>DB: Store session hash/expiry and issue configured signed claims
  DB-->>E: Canonical profile/session result
  E-->>A: Session token and signed Supabase access JWT
  A->>DB: Authenticated RPC/PostgREST using both session and JWT
~~~

Operational invariants:
- The Android app never sends or stores the Truecaller partner key.
- The authorization code is short-lived and must be consumed with matching state/PKCE data; reject reused state.
- Rate limits must run before expensive/provider calls. Errors should expose safe codes, never provider tokens.
- Canonical profile IDs are used in E2EE AAD/recipient routing. Do not substitute phonebook contact IDs or display names.
- The profile ID mapping, custom access-token hook and RLS helper must agree on the same canonical ID.
- Session expiry and Supabase JWT expiry are separate checks; refresh/re-authenticate rather than retrying an expired credential forever.

## 3. Tink E2EE outbox queue

~~~mermaid
sequenceDiagram
  participant UI as Compose chat UI
  participant R as Room/outbox
  participant W as WorkManager worker
  participant K as Tink key resolver
  participant S as Supabase RPC/PostgREST
  participant P as Recipient device
  UI->>R: Persist outgoing message as PENDING
  UI->>W: Enqueue durable retry
  W->>R: Claim/read pending row owned by current profile
  W->>K: Resolve published recipient public key
  alt recipient key unavailable
    K-->>W: RecipientKeyNotFound / retryable
    W->>R: Persist reason and attempt timestamp
    W-->>UI: Keep pending indicator and schedule retry
  else key available
    K-->>W: Valid Tink public keyset
    W->>W: Encrypt locally with canonical IDs and AAD
    W->>S: Submit ciphertext and minimal metadata
    S-->>W: Accepted/inserted idempotently
    W->>R: Mark SENT_TO_SERVER; preserve retry metadata
    S-->>P: Realtime/REST observes encrypted row
    P->>P: Resolve sender identity; decrypt and persist in Room
    P->>S: Authenticated delivery ACK
    S-->>UI: Metadata-only delivery/read state
  end
~~~

Outbox invariants:
- Never log plaintext, ciphertext, full token, private keyset or complete phonebook contents.
- Persist locally before network access; account-scope each pending row.
- Use client message IDs for idempotency and unique row constraints.
- Use the same canonical participant IDs when computing encryption AAD. A decrypt failure is not repaired by silently trying multiple AAD variants.
- Distinguish local pending, server accepted, delivered and read; a Realtime echo to the sender alone is not proof of delivery.
- Key publishing updates both profile key metadata and the public-key lookup store. Missing key should leave the queue retryable, not downgrade to plaintext.
- ACK only after the recipient has persisted the decrypted message locally, or after the agreed protocol-specific receipt condition.

## 4. WebRTC signaling over Supabase Realtime

~~~mermaid
sequenceDiagram
  participant C as Caller device
  participant RT as Supabase Realtime signaling
  participant R as Recipient device
  C->>RT: Create call session; publish SDP offer
  RT-->>R: Deliver validated offer notification
  R->>RT: Publish SDP answer
  RT-->>C: Deliver validated answer
  C->>RT: Publish ICE candidates
  R->>RT: Publish ICE candidates
  RT-->>C: Deliver remote ICE candidates
  RT-->>R: Deliver remote ICE candidates
  C<<->>R: ICE/DTLS negotiation and WebRTC media
~~~

Troubleshooting:
- Verify both authenticated profiles subscribed to the same stable call ID and participant set.
- Check signaling event type, sender profile ID, SDP presence and candidate fields without logging full sensitive SDP in production.
- Confirm audio/camera permissions, local track creation, track enabled state, ICE candidate routing and renderer lifecycle.
- Mute should call the local audio track's enabled setter; speakerphone should update AudioManager in communication mode; camera flip should invoke the active CameraVideoCapturer handler.
- Realtime carries signaling only. Do not upload the live audio/video stream to Postgres or attach it to FCM.
- Always end/disconnect the signaling session and release renderers/capturer/audio mode on call termination.

## 5. FCM wake-up path

~~~mermaid
flowchart TD
  M["Committed server message / pending work"] --> H["Server webhook or Edge Function"]
  H --> V["Validate event and resolve recipient profile"]
  V --> D["Read recipient device tokens"]
  D --> F["Send minimal high-priority FCM data wake-up"]
  F --> A["Android FCM receiver"]
  A --> W["Enqueue durable inbox/outbox worker"]
  W --> R["Fetch authoritative rows via REST"]
  R --> L["Persist in Room; decrypt locally if recipient"]
  L --> Ack["Send delivery/read ACK after persistence"]
~~~

Keep notification/wake payloads small and free of plaintext message content, full phone numbers, public/private keysets, or authorization tokens. FCM is best-effort: the client must reconcile through REST and monotonic message cursors on app resume. Respect Android Doze, network availability, WorkManager constraints and user notification settings; never depend on a single push for correctness.

## 6. Troubleshooting playbook

### A. Tink recipient key missing or decrypt/key mismatch

1. Record the error class and a redacted canonical profile ID only; do not dump keysets.
2. Compare the canonical profile ID, UUIDv5/identity mapping, vibesync_auth_identity_map, and the exact recipient ID in the pending row.
3. Check whether the sender queried profiles.public_key/public_identity_key and the user_public_keys store; ensure the own-device publication RPC returned success rather than merely completing an HTTP request.
4. Check the keyset is a public keyset and contains the expected Tink key material. Never publish private key material.
5. For a decrypt mismatch, verify sender/receiver participant order and message ID AAD. Compare public-key fingerprints through a verified out-of-band fingerprint exchange; do not attempt silent plaintext fallback.
6. Enqueue a retry only after key publication or identity mapping has been repaired. Preserve pending ciphertext/status safely; do not duplicate-send a message with a fresh ID.

### B. WorkManager queue stalls or permanently pending messages

1. Inspect WorkManager unique work name, state, constraints, initial delay, backoff and existing-work policy.
2. Confirm the Room pending row belongs to the active profile, is in a retryable state, and has a non-empty canonical match/recipient ID.
3. Distinguish NoOp, Rejected, and retryable transport/key errors. Permanent validation errors require UI/support action, not infinite retry.
4. Test airplane mode, force-stop/reopen, app process death and network restoration; app resume should schedule reconciliation.
5. Validate that the worker has not been replaced continuously, and that unique work does not deduplicate separate conversations into one incorrectly scoped request.
6. Redact lastSyncError before UI/log upload. No message body or cryptographic secret belongs in worker logs.

### C. Supavisor pooling spikes, too-many-clients errors or high API latency

1. Confirm clients use the intended pooled connection endpoint/port for the deployed environment; a session/transaction pool differs from direct PostgreSQL. Do not assume a local-development port applies to production.
2. Review connection counts, active/idle/idle-in-transaction sessions, PostgREST latency, worker concurrency, transaction duration, and pool max size together.
3. Keep REST requests short; avoid long-lived database transactions around network/storage calls.
4. Batch only safe idempotent reads/writes, cache public metadata, and avoid N+1 lookup loops. Use bounded client concurrency and rate limits.
5. Reproduce with load tests below and above expected free-tier traffic; include p50/p95/p99 latency, errors, saturation and recovery time. An HTTP success at low load does not prove 100k-user readiness.
6. Never fix pool exhaustion by increasing uncontrolled concurrency or exposing direct database credentials to the Android client.

### D. Oppo / Vivo / Xiaomi aggressive background restrictions

1. Verify permission/notification state first, then determine whether the OS restricted background activity, auto-start or battery usage.
2. Check Android WorkManager state, FCM delivery, last app resume, and network state. Distinguish OEM background-kill behavior from invalid JWT/key errors.
3. Provide a neutral, optional user-facing guide to the device's built-in settings for unrestricted/battery-optimized background activity. Do not attempt hidden bypasses or repeatedly launch activities against user intent.
4. Keep correctness independent of push delivery: on launch/resume, reconcile pending outbox and inbox via REST, then ACK idempotently.
5. Respect Android system restrictions and document battery-impact tradeoffs. Never advise users to disable security features unrelated to VibeSync.

### E. Public avatar upload fails or the photo stays stale

1. Confirm the selected URI is still readable when the upload starts and bitmap decoding produced a non-empty image.
2. Verify the active session profile matches the object owner's filename and JWT/session expiry are valid.
3. Inspect Storage RLS policy on bucket avatars: public SELECT should not grant anonymous INSERT/UPDATE; writes must restrict to the current profile path.
4. Confirm accepted MIME type (image/webp or image/jpeg), 512×512 output, successful Storage response and public object URL. Verify profiles.avatar_url was updated through the profile RPC.
5. Confirm avatar URL parsing permits only this project's public avatars prefix, Coil crossfade model key includes a cache-busting version, and the fallback initial remains visible while the image loads.
6. Do not put private photos in a public bucket. If private avatars are required, switch to the encrypted/private path and signed access instead of relaxing the bucket.

## 7. Operational redaction checklist

Never commit/log: provider partner secrets, Supabase service-role/secret keys, JWT signing secrets, release keystore/passwords, full session tokens, private Tink keysets, real phonebook dumps, message plaintext, signed media URLs with long-lived access, production database dumps, or unredacted safety reports.

Safe-to-record examples: workflow run ID, commit SHA, exception class, endpoint route without query parameters, HTTP status, retry count, queue state, duration, and a non-reversible/short-lived correlation ID.
