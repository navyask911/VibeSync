# VibeSync Security Threat Model

Copyright © 2026 VibeSync. All rights reserved.

## Assets
- authentication sessions and account identity
- private messages and encryption keys
- contact discovery identifiers
- profile data
- media objects
- business wallet and advertising balances
- FCM device tokens
- safety reports and lawful-request records

## Adversaries
- malicious application clients
- compromised devices
- credential theft
- database/API probing
- replay and message injection
- unauthorized business-wallet operations
- leaked server secrets
- abusive insiders with administrative access.

## Security rules
1. The client is untrusted for authorization.
2. Server-side authorization uses authenticated identity.
3. Sensitive server capabilities are Edge Functions or restricted RPCs.
4. Cryptographic failures fail closed.
5. No universal message-encryption key exists.
6. No raw address-book dataset is maintained for convenience.
7. Business money state is server-authoritative and idempotent.
8. FCM is transport, not identity or application storage.
9. Third-party credentials are secret-managed and never packaged into Android.
10. Security telemetry must not expose message plaintext.

## Residual risk
End-to-end encryption protects message content only when the production protocol is implemented correctly, keys are protected on devices, and sender/recipient verification is meaningful. Metadata, push-provider behavior, device compromise, screenshots, notifications, and social engineering remain separate risks.