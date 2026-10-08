# VibeSync Compliance Baseline

Copyright © 2026 VibeSync. All rights reserved.

## Engineering controls
- Data minimization: collect only what a feature requires.
- Purpose limitation: contact discovery, messaging, ads, business accounting, and safety data are kept logically separate.
- Retention limitation: transient data expires automatically where product/legal requirements permit.
- Access control: authenticated identity plus least-privilege RLS/server functions.
- Auditability: privileged operations are attributable and reviewable.
- Security testing: release builds require automated tests, dependency review, secret scanning, and build verification.
- Data subject controls: account deletion, privacy settings, blocking/reporting, and access/change workflows must be implemented before public launch where legally required.

## India
Engineering should maintain a current applicability matrix for the DPDP Act/Rules, IT Act and Intermediary Rules, CERT-In directions, consumer/contract obligations, payments requirements, and child-safety requirements that apply to the final service model.

## GDPR and other jurisdictions
Where applicable, design against data minimization, storage limitation, integrity/confidentiality, privacy-by-design, lawful basis/consent requirements, user rights, breach response, and international-transfer requirements.

## Payments
VibeSync never stores full card numbers, CVV, banking passwords, or UPI PINs. Payment gateways handle payment credentials. VibeSync stores provider transaction references, amounts, status, and reconciliation records only.

## AI / generated content
AI-generated content must be clearly disclosed where required by the applicable law/platform rules. Moderation, abuse reporting, and traceability controls must not require storing unnecessary private conversation content.

## Review cadence
Legal and compliance controls should be reviewed before material feature changes, before public commercial launch, after a major legal change, and after a security incident.