# VibeSync — Product Requirements Specification

Copyright © 2026 VibeSync. All rights reserved.
Revision date: 15 October 2026

## 1. Product intent

VibeSync is an Android-first social, messaging, discovery and local-business platform. The product direction is privacy-first, local-first, low-operational-cost and accessible to everyday users. Its positioning is “Made in India, for the World.” It combines adult social discovery and connections, private encrypted conversations, stories, voice/video calling, contact discovery, and a small-business suite.

This specification is a product/engineering source of truth, not a claim that every described path is enabled in every release. The branch, backend configuration, migrations, Android permissions, RLS policies and release verification determine actual availability.

## 2. Personas and roles

| Role | Capabilities | Guardrails |
|---|---|---|
| Visitor / unauthenticated | View login and public product information | Cannot access private account data or post as a member |
| Verified member | Profile, contact discovery, Connect, chat, stories, calls, privacy settings, reporting and account controls | Must authenticate through the configured Truecaller flow; profile visibility and permissions apply |
| Conversation participant | Send encrypted messages, use reactions/replies/media, report/block and manage conversation settings | Chat content is intended to be encrypted on-device; metadata and delivery signals remain operationally visible |
| Business owner | Manage owned listings, wallet points, campaign/broadcast drafts, analytics and catalog | Ownership and wallet charges are rechecked by server-side RPCs; never trust client cost estimates |
| Moderator / authorized staff | Use explicitly permissioned safety and moderation tools | Least privilege, attributable actions, audited access; no general decryption backdoor |
| Superadmin / operator | Governance and controlled operational actions | Account allow-list/role verification must be server-enforced; credentials stay in environment secrets/Vault |
| Background system worker | Retry outbox sync, reconcile delivery, process expiry/wakeups | Idempotent operations, bounded retries, account scoping and no plaintext logging |

The app is adult-first; release policy must restrict dating/social features to adults and apply documented safety procedures for reports, blocks, threats and suspected underage use.

## 3. Product requirement matrix

| Area | User-facing requirement | Technical acceptance criteria |
|---|---|---|
| Authentication | Simple, verified sign-in using Truecaller | Authorization code is verified server-side; partner key is secret-only; session identity is canonical and expires |
| Profile | Name, bio, preferences, public profile picture and privacy controls | Own profile writes go through authorized RPCs; avatar picker compresses to 512×512 WebP/JPEG; public avatar URL is stored in profile metadata |
| Contacts | Find existing VibeSync members from locally selected contacts | Contact batch is not persisted as a server address book; phone matching uses HTTPS and a restricted RPC/index; match response returns only needed profile data |
| Connect / discovery | Browse profiles, apply distance/age/intent/verification filters and send requests | Filters are validated in app; server checks request/session/limits; location collection is permission-based |
| Messaging | One-to-one messages, reply, edit/delete policy, reactions, disappearing timer, encrypted voice notes and message info | Room-first outbox; Google Tink encryption before remote transport; durable WorkManager retries; message IDs deduplicate retries; delivery/read signals are authoritative server state |
| Message states | Single tick means server accepted/sent, double tick delivered, blue double tick read when receipts are enabled | Sender renders state from current delivery fields and read timestamp, not from optimistic UI alone |
| Stories | Photo/video/text stories with caption, reply/reaction/like and privacy controls | Media containers stay within safe drawing insets; upload controls remain above gesture/navigation regions; story privacy and expiry are checked on backend |
| Calls | Audio/video calls with mute, speakerphone, camera toggle/flip and end-call controls | SDP/ICE signaling is authenticated and separated from media; local audio/video controls change the WebRTC tracks/capturer |
| Privacy | Read-receipt and typing privacy, screen security, incognito keyboard where supported, optional app lock and note-to-self | Controls reflect actual platform limitations; never imply a setting can prevent external screenshots or another camera recording |
| Business listing | Profile/catalog, media, location, offers and owner controls | Listing owner is derived or verified server-side; public directory exposes only approved public fields |
| Business wallet | Recharge and ledger visibility | Wallet uses integer points; accounting is server-side and transactional; no direct client writes to balance tables |
| Business broadcast | Target all followers, nearby followers or interest tags | RPC recomputes eligible audience and checks wallet balance before debit; dispatch function checks ownership and sends FCM; failures remain visible and retryable |
| Safety | Block, report, delete account/data and respond to abuse | Abuse signals cannot silently bypass reporting or moderation; preserve only what is necessary for legitimate safety/legal retention |
| Backup/recovery | Optional encrypted backup and recovery prompts | Backup payload is encrypted client-side; no private key material in server logs; export/restore must validate the recovery secret |
| Operations | Superadmin governance, diagnostics, secrets and migrations | Production keys never enter repository/APK; RLS/RPC tests and artifact verification block release |

## 4. Architecture principles

1. **Canonical identity:** bind every sensitive operation to the current verified VibeSync profile and session, not to a display name, phonebook row ID or client-supplied role.
2. **Local-first messaging:** persist outgoing work locally before attempting network delivery. Retry only through durable workers and make writes idempotent.
3. **Cryptographic separation:** private chat payloads use Google Tink on the client. Contact discovery is HTTPS phone matching, not E2EE. Public profile avatars are publicly readable images and are not E2EE.
4. **Server-authoritative accounting:** estimate costs in the UI, but calculate audience, eligibility and debit transactionally in Postgres.
5. **Least privilege:** public views contain safe fields; RLS and security-definer RPCs enforce access; service-role and provider secrets remain server-side.
6. **Observable failures:** explain to users when a key is missing, a message is queued, a broadcast is pending, or a permission blocks a feature. Do not convert a timeout into a false success.
7. **Affordable by design:** minimize persistent binary data and unnecessary Realtime connections, use pooling, cache safe public metadata, and benchmark cost/latency against real load.

## 5. Business Points and commercial logic

The current product pricing convention is **1 VibeSync Point = ₹0.05** when estimating a follower broadcast. For a server-calculated audience of N recipients at one point each:

- Points charged = N points, subject to any explicitly versioned business pricing rule in the active database migration.
- Indicative rupee cost = N × ₹0.05.
- A UI estimate is not authoritative. The stage RPC locks/reads the wallet, recomputes eligible recipients, refuses an insufficient balance, applies the debit and writes a ledger event atomically.
- After a successful stage, the broadcast is dispatched through the configured backend function. Dispatch failure is a pending operational state; it must not create a second debit on retry.
- Points are app credits for the features described in the terms, not cash or a bank deposit. Pricing copy, payment terms and tax treatment must match the final deployed implementation and applicable law.

## 6. Privacy and media model

- Phonebook contact names and the full address book remain local unless a user explicitly shares information through a feature. Contact discovery sends the numbers required for matching through authenticated HTTPS. The restricted verified-phone lookup index persists while needed for active-account matching; request batches are not a durable uploaded address book.
- Private chat message content and supported chat attachments are encrypted by the sender before backend persistence; the recipient decrypts on its device.
- Story media uses the status-media Storage path and story access policy. Do not call it end-to-end encrypted unless a verified client-side encryption pipeline is enabled for that path.
- Profile pictures in the public avatars bucket are visible to anyone able to access the public URL. Do not upload sensitive/private imagery.
- Names, profiles, message timestamps, recipient IDs, push tokens, receipts, safety reports and billing state may still be personal/operational data and need appropriate access controls and retention.

## 7. Release acceptance gates

Before a public release: compile and test the exact commit; validate release signing and APK/AAB; inspect Android permissions and privacy disclosures; run RLS/RPC and storage-policy tests; validate Truecaller verification and session expiry; validate Tink key exchange on two physical devices; test outbox recovery under network loss/process death; test safe-area layouts on gesture and three-button navigation; verify wallet debit and dispatch retry idempotency; test profile/account deletion; and have Privacy Policy, Terms, Data Safety and store disclosures reviewed against actual runtime behavior.

## 8. Decision log / open items

- Legal operator name, registered address, grievance/privacy contact and jurisdiction-specific complaint process must be finalized before public distribution.
- Retention and deletion SLAs need to match deployed cleanup jobs and backup rotation.
- Confirm whether all extra profile gallery images should remain encrypted objects while the primary avatar is public; mixed visibility must be communicated accurately.
- Ensure public-profile URL versions are cache-busted and storage objects can be replaced/retired without stale cached imagery.
- Make CI success and artifact verification a hard release gate; a commit alone does not certify release readiness.
