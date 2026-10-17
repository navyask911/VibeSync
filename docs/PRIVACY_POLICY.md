# VibeSync Privacy Policy

Effective date: 15 October 2026
Product: VibeSync for Android and related VibeSync services

> **Publication checklist:** This policy is a production-oriented draft based on the repository's documented architecture. Before publishing it in an app store, the operator must fill in the legal entity name, registered address, privacy/grievance contact, retention/deletion commitments and jurisdiction-specific details, then validate every disclosure against the deployed release and vendor contracts. It is not a legal opinion or a guarantee of regulatory/store approval.

## 1. Who operates VibeSync

VibeSync is operated by **[insert legal entity / proprietor name]**, with its registered address at **[insert address]**. Privacy requests and complaints: **[insert monitored privacy email]**. The designated grievance contact, where required, is **[insert name/title, contact details and response hours]**. Do not release this policy with unresolved placeholders.

## 2. What this policy covers

This policy covers the VibeSync Android application, account/profile services, private messaging, stories, voice/video calls, contact discovery, business listings, points wallet, broadcasts, analytics, and related backend services. Additional policies or notices may apply to a specific promotion, payment provider or feature.

## 3. Data we process and why

### Account and profile data
We process the canonical VibeSync profile identifier, verified phone identity supplied through Truecaller, display name, age or age-related settings, profile text, occupation and other fields the user elects to provide, preferences, verification status, profile image, privacy settings and account state. We use this information to authenticate, display a profile, provide matching and communication, protect accounts, and respond to user requests.

### Contact discovery
If the user grants contact permission and starts matching, the app reads the contact fields needed for that action and normalizes phone numbers locally. It sends the necessary phone-number candidates over authenticated HTTPS to a restricted VibeSync/Supabase matching RPC, which compares them with a server-side indexed directory of verified accounts and returns minimal match information. The app does **not** upload the device's complete address book as a permanent contacts database, and local contact names/photo references remain on the device unless a separate feature shares them.

“Zero-retention contact discovery” refers to not retaining each submitted contact batch as a server-side uploaded address book. It does **not** mean that the backend never processes a phone number or that verified account phone numbers are never retained: a restricted lookup index is maintained for active-account matching. Phone-number matching is protected in transit by HTTPS but is not covered by message end-to-end encryption.

### Private messages and receipts
Supported private chat messages, replies, reactions, edits/deletes, and voice-note payloads use Google Tink client-side encryption where enabled. The server transports/persists encrypted payloads and operational fields needed for delivery, including identifiers, timestamps, content type, message size, retry state, recipient/participant relationships, and delivery/read acknowledgements. Depending on the feature and implementation version, some metadata may be visible to VibeSync and infrastructure providers even when message contents are encrypted.

### Profile pictures
A user-selected primary profile picture is resized/cropped to 512×512, re-encoded, and uploaded to the public Supabase Storage bucket named avatars. Its public URL is stored with profile metadata. **Profile pictures in this bucket are not end-to-end encrypted and may be viewed by anyone with access to the public URL.** Users should not upload an image they intend to keep secret. Additional gallery items may use a different flow; the in-app visibility indicator and deployed policy must be checked before assuming they share the primary avatar's visibility.

### Stories, media and calls
Story captions, selected media, background audio references, audience/privacy choices, and timestamps are processed to provide the story feature. The status-media object bucket and signed-access/authorization rules govern access. Do not interpret a storage URL or encrypted chat indicator as proof that every story asset is E2EE. Audio/video calls use WebRTC media tracks and exchange signaling metadata such as session IDs, SDP and ICE candidates through the configured signaling transport. Network operators may observe connection metadata.

### Business suite and wallet
We process business listing fields, contact details supplied by the business, selected targeting preferences, follower/audience relationships, points balance and ledger entries, broadcast content/status, dispatch results, analytics events and payment-provider references. Wallet balance and broadcast debits are authoritative server-side operations. The app should not collect payment card numbers, CVV, bank passwords or UPI PINs.

### Location, device and safety data
If enabled and permissioned, location may be used for proximity discovery, nearby business targeting or calls/business context. We may process device tokens for push notifications, app/platform information for reliability, safety reports, block lists, moderation decisions, and technical logs used to protect the service. Push data should be minimized and must not include plaintext private-message content.

## 4. Truecaller authentication

VibeSync uses the Truecaller verification flow to confirm a phone identity. The Android app sends the authorization material required by the server-side verification endpoint; the Edge Function exchanges the authorization code with Truecaller using a partner secret held in server environment secrets, verifies the returned identity, and provisions a canonical VibeSync session/profile mapping. Partner secrets are not embedded in the APK. Truecaller may process information under its own privacy terms as part of the authentication flow.

## 5. Encryption and security

Private chat content uses Google Tink client-side encryption for supported message flows. Device-side key material is intended to remain on the originating device/secure key storage, and public keys are published for authorized encryption. The backend must not receive plaintext message bodies where E2EE is required. E2EE does not conceal all metadata, protect an unlocked/compromised device, prevent recipient screenshots, or replace account security, abuse response, key verification and secure development practices.

We use HTTPS, verified sessions, row-level security, restricted views/RPCs, least-privilege storage policies, local database protections and operational monitoring where implemented. No online service can promise absolute security.

## 6. Service providers and disclosures

Depending on enabled features, data may be processed by:
- Supabase/Postgres/Storage/Edge Functions for account metadata, restricted phone matching, application state, media access and server-side business logic;
- Truecaller for verification;
- Firebase Cloud Messaging (FCM) for push delivery and background wake signals;
- payment service providers selected for a transaction;
- configured object storage or infrastructure providers;
- legal advisers, regulators, courts or law-enforcement authorities where disclosure is legally required and properly reviewed.

We do not sell personal data as a product. We limit provider access to operational purposes, contractual requirements and legitimate safety/legal needs. Store privacy labels and Android Data Safety declarations must accurately list every SDK/data category used in the shipped build.

## 7. Retention

We retain account/profile information while the account is active or while needed to provide the service. Message ciphertext and delivery metadata are retained according to conversation operation, disappearing-message settings and applicable safety/legal requirements. Stories are designed for a limited display period, but the actual deletion of metadata, storage objects, caches and backups depends on the deployed expiry/cleanup jobs; no claim of immediate erasure should be made unless those jobs are verified. Contact-match requests are processed for lookup and are not retained as a separate uploaded address-book database; the restricted verified-phone index remains while needed for account matching.

After an authenticated account-deletion request, working account data should be deleted or de-identified within **[confirm operational SLA]**, subject to security logs, disputes, legally required records and backup rotation. Before publication, replace this bracketed SLA with a period that is technically implemented and legally approved.

## 8. Choices and rights

Users can manage supported profile and privacy settings, decline optional permissions, stop contact matching, restrict discovery where controls are available, block/report users, and request access/correction/deletion through **[insert verified in-app route and email]**. Withdrawing device permissions stops future access but may not erase data already lawfully processed. Under applicable Indian privacy law, requests will be handled according to the rights and procedures in force for the relevant processing and effective-date provisions.

## 9. Children and adult features

VibeSync's social/dating discovery is adult-first and intended for people aged 18 or older. Do not create dating profiles for minors. Suspected underage accounts or child-safety reports should be handled through the published safety process and any legally mandated reporting obligations.

## 10. International processing

Service providers may process data in regions where VibeSync or its providers operate. We will apply applicable contractual, security and transfer safeguards. The final provider/region disclosure must match the production Supabase project and all other enabled vendors.

## 11. Policy changes and complaints

We may revise this policy when features, providers or laws change. Material changes should be communicated through an appropriate in-app or website notice and dated here. Contact: **[privacy email]**. Grievance contact: **[name/title, postal address, email and response timelines]**.

## 12. Regulatory and store reference checklist

The final policy and in-store data disclosures should be reviewed against:
- India's Digital Personal Data Protection Act, 2023 and the Digital Personal Data Protection Rules, 2025, including their applicable commencement timeline and any subsequent notifications: https://www.meity.gov.in/documents/act-and-policies
- Google Play User Data policy: https://support.google.com/googleplay/android-developer/answer/10144311
- Apple App Store privacy and data-use expectations, if an iOS version is distributed: https://developer.apple.com/app-store/user-privacy-and-data-use/

These references are compliance inputs, not a certification of compliance. Re-check current law and platform requirements before launch.
