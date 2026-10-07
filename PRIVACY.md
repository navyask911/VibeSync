# VibeSync Privacy Policy

**Effective date: October 7, 2026**

VibeSync is designed as a privacy-first social and business platform. The product uses data minimization, local-first processing, privacy-preserving contact discovery, authenticated access controls, and server-authoritative business operations.

## 1. Information VibeSync may process

Depending on the features enabled by a user, VibeSync may process account/authentication attributes, profile information, profile media, connection/match state, encrypted message envelopes, delivery/read status, device push-token information, abuse/safety reports, business-account information, wallet/accounting records, advertisement campaign information, and operational/security telemetry.

VibeSync does not require a user to provide every available category of information to use every feature.

## 2. Contact discovery

The original product objective is to help users find people they already know without maintaining a permanent copy of the user's complete address book in the cloud.

Contacts are normalized and transformed locally before discovery requests. The production server returns only the minimum matching profile information required by the feature.

A plain SHA-256 hash of a phone number is treated only as a privacy-preserving pseudonymous representation; VibeSync does not describe plain hashing as cryptographic zero-knowledge contact discovery. A stronger PSI/OPRF-style protocol may be introduced after cryptographic review.

## 3. Messages and encryption

Supported private messaging uses client-side encryption with Google Tink. The application backend is designed to process encrypted message payloads rather than message plaintext.

Encryption applies only to the protocols and release paths actually implemented and verified. A third-party infrastructure provider such as a push service may still observe operational delivery metadata.

VibeSync does not maintain a universal decryption backdoor for genuinely end-to-end encrypted private-message content.

## 4. Push notifications

Firebase Cloud Messaging (FCM) may be used to deliver Android notifications. FCM is a transport mechanism and is not the authoritative VibeSync application database.

Private-message push payloads are designed to carry encrypted payloads or opaque notification data rather than plaintext conversation content.

## 5. Media and storage

Large profile and business media, photos, videos, and other objects may be stored in configured object storage such as Cloudflare R2. Access should be authenticated or time-limited according to the feature.

## 6. Business and advertising data

Business features may process business profile data, campaign configuration, wallet balances, transaction references, delivery outcomes, impression records, and analytics required to provide the service.

VibeSync's published advertising policy uses transparent sponsored labeling, immediate user dismissal, frequency controls, and a minimum viewability threshold. VibeSync does not intend to sell device identifiers to advertising brokers.

## 7. AI

AI features may send the minimum required user-provided content to the configured AI provider through a server-side function. Server credentials are not embedded in the Android application.

The specific retention and processing policies of the external AI provider also apply where external processing occurs.

## 8. Security and fraud prevention

VibeSync may collect limited security telemetry necessary to prevent abuse, rate-limit attacks, diagnose failures, preserve audit trails, and protect business transactions.

Security telemetry should avoid storing private message plaintext unless a lawful and explicitly documented feature requires otherwise.

## 9. Account deletion

Users may request account deletion. The production architecture provides a server-authoritative deletion path intended to remove VibeSync account records, device registrations, contact-discovery records, and message transport records owned by that account, subject to lawful retention obligations and records that must be preserved for security, fraud prevention, accounting, or legal compliance.

## 10. Children and age safety

The core romantic/dating discovery product is designed as 18+ by default. VibeSync must not expose adults to minor dating profiles or allow prohibited adult/minor romantic interaction.

Any separate 13–17 contact-only product mode remains a controlled design concept and requires a dedicated child-safety and legal review before production activation.

## 11. Lawful requests and emergency safety

VibeSync may receive lawful requests from authorized authorities and may preserve or disclose information that the platform actually possesses when required by applicable law.

The company does not promise access to encrypted message plaintext that it does not possess.

Requests and disclosures should be handled through an auditable authorization process.

## 12. International use

The service may be used in multiple jurisdictions. Additional privacy rights, localization, transfer restrictions, retention requirements, or regulatory duties may apply depending on the user's location and the service's legal status.

## 13. Compliance baseline

For India, the compliance program should be reviewed against the Digital Personal Data Protection Act, 2023, the Digital Personal Data Protection Rules, 2025 and applicable commencement timeline, the Information Technology Act, 2000, applicable Information Technology Intermediary Rules, CERT-In directions, payment/consumer requirements, and applicable child-safety obligations.

For other jurisdictions, the service should be assessed against the laws that apply to the actual service, users, processing activities, and business model.

This policy is an engineering/product baseline and is not a substitute for legal advice.

## 14. Changes

VibeSync may update this policy as features, infrastructure, law, or business operations change. The effective date will be updated with material revisions.
