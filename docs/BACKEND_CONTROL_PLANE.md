# VibeSync Backend Control Plane

Copyright © 2026 VibeSync. All rights reserved.

## Control-plane objectives
The backend control plane exists to provide authoritative authorization, auditability, abuse prevention, business accounting, operational diagnostics, retention controls, and lawful request handling while preserving the product's local-first and privacy-first design.

## Authoritative identities
Privileged authorization decisions remain server-controlled. Truecaller verification establishes the client sign-in state; user-editable phone strings, display names, or client flags are never trusted as authorization identities.

## Control domains
### Identity
- Truecaller is the end-user verification authority.
- Truecaller OAuth is the sole end-user sign-in contract; the current client points at the supplied VIBESYNC DEV credential.

### Device registry
- FCM remains the push transport boundary; end-user sign-in does not require Supabase Auth UUID sessions.
- FCM tokens are operational identifiers and are not sold, exported to advertisers, or exposed through profile APIs.

### Messaging
- Encrypted message payloads remain opaque to the application backend.
- Temporary delivery records may exist only as long as required for delivery acknowledgments, retry, refund, or abuse operations.
- No backend feature may silently downgrade an encrypted message to plaintext.

### Media
- Client compression reduces transport/storage cost.
- Object storage access is authorized through short-lived server-issued URLs or equivalent capabilities.
- R2 access keys and signing secrets never exist in Android source or APK runtime configuration.

### Business accounting
Business wallets use server-authoritative ledger transactions, idempotency keys, holds/escrow, delivery outcomes, refunds, and audit events. Local Room balances are caches, not authoritative money state.

### Advertising
Impressions are billed only under the published viewability rule. Campaign budget, CPM tier, targeting constraints, impression count, and spend are server-authoritative.

### Operations
Health dashboards report measured state. Synthetic fallback numbers must never be presented as live backend measurements.

## Zero-investment design
The architecture minimizes always-on infrastructure and persistent data. Paid infrastructure is expected only when real usage exceeds the applicable free quotas or when a production requirement demands additional capacity.