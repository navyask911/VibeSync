# VibeSync Privacy Policy

**Effective date: October 8, 2026**

This Privacy Policy explains what VibeSync may process, why it is processed, where processing happens, and what controls are available to users.

VibeSync is designed around data minimization and local-first processing. The exact data processed depends on the features a person uses.

## 1. Who this policy is for

This policy applies to the VibeSync Android application and the VibeSync services that support it.

VibeSync may operate across jurisdictions. Where local law imposes additional rights or obligations, those requirements may apply in addition to this policy.

## 2. Data categories

| Category | Examples | Main purpose |
|---|---|---|
| Account/authentication | Truecaller-verified mobile number, limited profile fields returned by Truecaller, local verification state | Sign-in and security |
| Profile | Name, age, gender, city, bio, interests, relationship preferences | Discovery and profile display |
| Profile media | Avatar/profile photos and other user-selected media | Profile and communication features |
| Connections | Likes, matches, friendships, blocks, reports | Social graph features and safety |
| Messages | Encrypted message envelopes, message IDs, timestamps, delivery/read state | Private messaging and delivery |
| Contact discovery | Normalized E.164 phone numbers submitted in an authenticated HTTPS request | Matching registered VibeSync friends |
| Push/device | FCM registration token, platform information | Push notification delivery |
| Safety/security | Abuse reports, moderation events, rate-limit/security telemetry | Fraud prevention, safety, platform integrity |
| Business | Business profile, campaign, broadcast, wallet and transaction-reference data | Business services and accounting |
| Advertising | Campaign settings, impression/delivery outcomes, sponsored-placement metadata | Sponsored delivery and billing |
| AI feature inputs | Minimum content required by an enabled AI feature | Providing that requested AI function |
| Support/legal | Support requests, deletion requests, lawful-request records | Service, compliance and dispute handling |

VibeSync should not collect a category merely because the application can technically access it.

## 3. Contacts and contact discovery

The phonebook is intended to remain on the device.

For contact discovery, after permission is granted, VibeSync normalizes selected contact phone numbers to E.164 and sends the numbers in the JSON body of an authenticated Supabase RPC over encrypted HTTPS (SSL/TLS), strictly to match registered friends. Supabase processes the supplied phone numbers against an indexed, server-restricted lookup directory and returns canonical profile IDs plus the minimum profile metadata needed to display matches. The client no longer computes blind tokens or phone-number hashes for contact discovery.

HTTPS protects phone numbers in transit; it does not make those numbers invisible to Supabase and does not extend chat end-to-end encryption to contact matching. The app does not upload contact display names as a permanent cloud address book. Local contact names and returned match results are cached on-device for the contacts UI.

## 4. Messages and encryption

Supported private-message flows use client-side encryption with Google Tink.

The application backend may receive ciphertext, message identifiers, recipient/sender identifiers needed for authorization and routing, timestamps, delivery/read metadata, and other transport data required by the feature.

Encryption claims apply only to protocols and release paths actually implemented and tested. Device compromise, malicious recipients, insecure backups, notification metadata, and third-party transport metadata can affect the overall security boundary.

VibeSync does not maintain a universal decryption backdoor.

## 5. Firebase Cloud Messaging

Firebase Cloud Messaging (FCM) may be used for Android push delivery.

FCM is treated as a transport service, not as the authoritative VibeSync application database. Private-message notifications should avoid sending conversation plaintext when encrypted transport is required.

Existing Firebase Realtime Database or Firestore records may still exist as controlled legacy compatibility/migration data where a reviewed feature requires them. New application features should not create uncontrolled permanent Firebase silos.

## 6. Storage and media

Large objects may be stored in object storage such as Cloudflare R2. Access should use authenticated, scoped, or time-limited mechanisms where appropriate.

VibeSync should retain metadata and references in databases instead of storing large media binaries in ordinary application tables.

## 7. Business and advertising processing

Business features may process business identity, campaign settings, broadcasts, wallet/accounting entries, transaction references, delivery results, and analytics.

Sponsored content should be clearly labelled. VibeSync's advertising policy is intended to prevent deceptive controls, forced waiting, hidden close controls, and unfair impression billing.

The platform's product policy is that it does not sell users' private message content or raw address-book data to advertising brokers.

## 8. AI processing

When an enabled AI feature is used, VibeSync may send the minimum required input to a server-side AI provider.

AI credentials must remain server-side. Provider-specific retention, security and processing terms also apply to the information sent to that provider.

The application should not send full conversations or unrelated personal information when a smaller request is sufficient.

## 9. Security telemetry

VibeSync may process limited logs and security telemetry needed for Truecaller verification and abuse prevention, rate limiting and fraud detection, incident investigation, service diagnostics, safety/moderation, and business-accounting integrity.

Private message plaintext should not be placed into ordinary telemetry.

## 10. Retention

VibeSync follows purpose-based retention rather than indefinite retention.

Retention periods should be documented per data class and reduced when the data is no longer required.

Examples:

- local chat/cache data: retained according to local user/device settings and cleared on logout or deletion where applicable;
- session credentials: retained only while needed for an authenticated session;
- contact-discovery records: limited to the minimum information and duration needed for matching/security;
- safety/security records: retained as long as reasonably necessary for abuse prevention, incident handling, or legal duties;
- business/accounting records: retained as required for reconciliation, tax, payment, fraud and legal obligations;
- deletion requests: retained long enough to prove and administer the deletion process where necessary.

Actual retention schedules must be reviewed against the production data inventory before launch.

## 11. User controls and requests

Users should have clear mechanisms, appropriate to the feature and applicable law, to:

- access or review personal data;
- correct inaccurate profile/account information;
- withdraw consent where consent is the processing basis;
- request deletion;
- report abuse or illegal content;
- block another user;
- manage discovery/privacy settings;
- request help with account recovery.

India's notified DPDP Rules require clear, standalone notices and accessible mechanisms for consent withdrawal, rights and complaints according to their applicable commencement timeline. VibeSync's UI and support process are being designed around those requirements.

See SUPPORT.md and docs/COMPLIANCE_MATRIX.md.

## 12. Account deletion

VibeSync provides a server-authoritative account-deletion path.

Deletion is intended to remove or de-identify account and transport records that VibeSync no longer has a reason to retain, subject to legal retention obligations, security/fraud records that must be retained for a limited period, accounting/tax/payment records that must be preserved, and records that must be preserved for a lawful request or dispute.

A deleted account is not a promise that every copy maintained by independent third parties is immediately erased.

## 13. Children and age safety

The core romantic/dating discovery experience is adult-focused and uses an 18+ safety boundary.

VibeSync must not expose adults to minor dating profiles or permit prohibited adult/minor romantic interaction.

Any separate minor-oriented social/contact experience requires a distinct safety, consent, product and legal review before activation. It must not be enabled merely because code exists for it.

For India, the DPDP Act treats a child as a person below 18 and imposes specific child-data safeguards. The application therefore treats age handling as a safety boundary, not just a profile field.

## 14. International transfers and providers

VibeSync may use infrastructure and service providers located in different countries.

Cross-border processing, subprocessors and transfer controls must be assessed against the law applicable to the service and the relevant user. Provider choices should be documented in the production data inventory.

## 15. Lawful requests and safety emergencies

VibeSync may preserve, restrict, or disclose information it actually possesses when required by applicable law or when necessary for a documented safety process.

VibeSync does not promise to produce encrypted content that the service genuinely does not possess in plaintext.

Emergency handling, preservation and disclosure decisions should be authenticated, documented, limited in scope, and auditable.

## 16. India compliance baseline

VibeSync's India compliance program is designed to support the Digital Personal Data Protection Act, 2023, the Digital Personal Data Protection Rules, 2025, applicable Information Technology Rules, 2021, applicable CERT-In requirements, payment/consumer rules and child-safety obligations.

The DPDP Act became law on August 11, 2023. The notified DPDP Rules were published on November 13, 2025 with a phased commencement schedule. VibeSync therefore distinguishes between controls implemented now and legal obligations whose specific provisions have later commencement dates.

Official references:

- https://www.meity.gov.in/digital-personal-data-protection-act-2023
- https://www.meity.gov.in/documents/act-and-policies/digital-personal-data-protection-rules-2025-gDOxUjMtQWa?pageTitle=Digital-Personal-Data-Protection-Rules-2025
- https://www.meity.gov.in/static/uploads/2026/02/550681ab908f8afb135b0ad42816a1c9.pdf

See docs/COMPLIANCE_MATRIX.md.

This policy is a product/engineering baseline and is not legal advice.

## 17. Changes

Material changes to this policy will receive a new effective date. The application and support channels should provide access to the current policy version.

**Copyright © 2026 VibeSync. All rights reserved.**
