# VibeSync Privacy & Contact Discovery

Copyright © 2026 VibeSync. All rights reserved.

## Contact discovery
The product goal is that a user's complete address book is never uploaded as a permanent cloud dataset. Contacts are normalized and transformed locally, and the server returns only the minimum match information required by the UI.

## Cryptographic terminology
Plain SHA-256 of a phone number is not, by itself, zero-knowledge contact discovery because phone-number identifier spaces are small. VibeSync therefore uses the phrase `privacy-preserving contact discovery` for the current deployed baseline. A future PSI/OPRF implementation may use stronger zero-knowledge terminology only after security review.

## Current baseline
- Raw device contacts remain on the device.
- Contact matching requests must contain transformed lookup identifiers, never a raw address-book upload.
- The contact index is protected by Row Level Security and server-side matching controls.
- The server returns only minimal profile data needed to establish a match.
- Phone numbers are not returned by the contact-match RPC.
- Legacy raw-phone matching paths are deprecated and must not be used in production builds.

## Identity
Truecaller verification is the authoritative sign-in event for the Android client. Phone numbers remain sensitive contact/discovery data; the client keeps the resulting verification state locally.

## Local data
Room may contain the user's local contacts, cached matches, and messages required for offline UX. Android backup of sensitive application data is disabled unless a specific product feature re-encrypts and exports it.

## Advertising privacy
VibeSync advertising is designed around on-device contextual selection and minimal targeting tags. The advertising policy prohibits resale of device identifiers and deceptive tracking. See `docs/AD_POLICY.md`.

## Important limitation
Privacy guarantees depend on the exact production protocol and third-party infrastructure behavior. VibeSync cannot promise that FCM, telecom providers, operating systems, or other external infrastructure reveal no metadata.