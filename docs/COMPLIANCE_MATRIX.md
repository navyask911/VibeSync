# VibeSync India Compliance & Control Matrix

**Baseline reviewed: October 8, 2026**

This document maps product and engineering controls to major India privacy and intermediary requirements. It is a control baseline, not a legal opinion or a claim of complete statutory compliance.

## 1. DPDP Act and Rules

The Digital Personal Data Protection Act, 2023 was enacted on August 11, 2023.

The Digital Personal Data Protection Rules, 2025 were notified on November 13, 2025 with phased commencement:

| Provision group | Notified commencement |
|---|---|
| Rules 1, 2, 17–21 | November 13, 2025 |
| Rule 4 | November 13, 2026 |
| Rules 3, 5–16, 22–23 | May 13, 2027 |

## 2. Control mapping

| Area | VibeSync control | Status |
|---|---|---|
| Notice | Standalone Privacy Policy and in-app disclosure plan | Implemented baseline |
| Purpose limitation | Feature-specific processing purposes in privacy documentation | Implemented baseline |
| Data minimization | Local-first contacts, selective profile fields, bounded RPCs | Implemented baseline |
| Consent | Product UI should record consent where consent is the lawful basis | Requires production verification |
| Withdrawal | Support/UI process documented | Requires production verification |
| Access/correction | Support + account/profile controls | Partial |
| Deletion | Authenticated server-side deletion path | Implemented baseline; production verification required |
| Child safeguards | 18+ romantic boundary and child-safety policy | Implemented baseline |
| Security safeguards | RLS, server secrets, Keystore, Tink, secret scanning | Implemented baseline |
| Processor governance | External provider inventory | Requires final production inventory |
| Breach response | Security and incident process | Requires operational runbook |
| Grievance mechanism | Support and safety intake documented | Requires final production channel/contact |
| Retention | Purpose-based schedule described | Requires final data inventory/schedule |
| Cross-border processing | Provider/transfer review requirement documented | Requires final production review |

## 3. IT Rules / intermediary baseline

The Information Technology (Intermediary Guidelines and Digital Media Ethics Code) Rules, 2021 require applicable intermediaries to publish user-facing rules/privacy/user-agreement information and maintain grievance and due-diligence processes appropriate to their status.

The specific obligations and thresholds depend on VibeSync's actual service classification, user base, operations and legal facts. VibeSync therefore does not declare itself a Significant Social Media Intermediary merely from product design.

Engineering/product controls include public Terms and Privacy documentation, block/report functionality, safety escalation and moderation, lawful-request handling, abuse-report logging and privileged-operation auditability.

Production launch still requires a legal classification review and a finalized grievance-contact workflow.

## 4. Operational security

Where applicable, VibeSync should maintain incident logging, time synchronization, incident-response procedures and legally required cooperation processes consistent with current CERT-In directions and any sector-specific obligations.

## 5. Payment and business features

Business wallet, points, refunds, merchant services and payment integrations may trigger additional obligations under payment-provider rules, consumer law, tax rules and sector-specific regulations.

VibeSync should retain transaction references and ledger entries rather than raw payment credentials.

## 6. Release readiness

Legal/control readiness is not complete merely because the repository contains this matrix.

Release readiness requires engineering controls, automated tests, signed artifacts, production provider/security review, final privacy/data inventory, operational grievance/safety contacts, and jurisdiction-specific legal review.

Official sources:
- https://www.meity.gov.in/digital-personal-data-protection-act-2023
- https://www.meity.gov.in/documents/act-and-policies/digital-personal-data-protection-rules-2025-gDOxUjMtQWa?pageTitle=Digital-Personal-Data-Protection-Rules-2025
- https://www.meity.gov.in/static/uploads/2026/02/550681ab908f8afb135b0ad42816a1c9.pdf

**Copyright © 2026 VibeSync. All rights reserved.**
