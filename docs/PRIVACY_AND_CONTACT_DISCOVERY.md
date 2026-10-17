# VibeSync Privacy & Contact Discovery

Copyright © 2026 VibeSync. All rights reserved.

## Contact discovery transparency

Contact discovery is a phone-matching feature, not a zero-knowledge protocol. After the user grants contacts permission, VibeSync normalizes phone numbers to E.164 and transfers the numbers needed for matching over encrypted HTTPS (SSL/TLS) to an authenticated Supabase RPC. Supabase checks those numbers against an indexed, server-restricted directory of verified VibeSync account phone numbers.

The phone index is not directly readable by anonymous or authenticated client roles. The RPC requires a valid VibeSync session and returns only canonical profile IDs and minimal metadata for matching contacts. HTTPS protects phone numbers in transit, but Supabase necessarily processes the supplied numbers. Phone numbers used for matching are not protected by chat end-to-end encryption.

The contact-matching request does not upload device contact names as a permanent server-side address-book database. Local contact names and display preferences remain on-device, while match results are cached in the encrypted Room database for offline use.

## Chat encryption

Private chat messages and media payloads use Google Tink client-side encryption. Chat E2EE is separate from the contact-discovery transport: the server can process contact-matching phone numbers while private message payloads remain encrypted end-to-end. Security also depends on correct key publication, session validation, device security, and backend access controls.

## Identity

Truecaller verification provisions the canonical VibeSync profile ID and the private E.164 lookup index. Supabase's match RPC returns that canonical profile ID so conversations do not depend on local contact IDs, names, or non-canonical placeholders.

## Local data and other infrastructure

Room may contain local contacts, cached match results, and messages required for offline UX. Android backup of sensitive application data should remain disabled unless a feature explicitly encrypts and protects exported data.

VibeSync advertising is designed around on-device contextual selection and minimal targeting tags. Third-party systems, including mobile networks, operating systems, push providers, and cloud infrastructure, may still process operational metadata. VibeSync does not claim that HTTPS removes server-side visibility into phone numbers sent for contact matching.
