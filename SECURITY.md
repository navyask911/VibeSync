# VibeSync Security Policy

## Security objectives

VibeSync follows defense in depth:

- Truecaller-verified identity and local session controls;
- Supabase Row Level Security and server-authoritative RPCs;
- server-only privileged secrets;
- Android Keystore protection for sensitive local verification state;
- Google Tink encryption for supported private-message flows;
- fail-closed encrypted-message handling;
- controlled Firebase compatibility;
- secret scanning and release gates;
- local-first data minimization;
- audited business/accounting paths.

## Firebase boundary

Firebase Cloud Messaging (FCM) remains approved as push transport.

Existing Firestore/Realtime Database integrations may remain only where a reviewed migration, compatibility, or diagnostic path explicitly requires them. New features must not silently expand Firebase into a second authoritative application backend.

## Reporting a vulnerability

Report security problems through the official VibeSync security/support channel published with the production service.

Do not include passwords, OTPs, private keys, session tokens, raw contact books or production credentials in a report.

A useful report includes the affected component/version, exact reproduction steps, security impact, reproducibility and minimal supporting evidence.

## Security claims

No software can honestly be guaranteed impossible to hack.

VibeSync therefore avoids absolute-security claims and instead uses measurable release gates, threat modelling, tests, least privilege and documented incident handling.

## Release expectation

A release candidate should not be treated as production-ready until the release security checklist, signed artifact verification and test gates are green.

**Copyright © 2026 VibeSync. All rights reserved.**
