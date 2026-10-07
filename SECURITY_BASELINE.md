# VibeSync Security Baseline

1. Never commit service-account private keys.
2. Never commit Android signing keys.
3. Never ship Gemini or other server secrets inside the APK.
4. Never use a fake/default OTP in production.
5. Never authorize database access from user-editable profile metadata.
6. Never upload complete address books as a permanent lookup dataset.
7. Never call plain SHA-256 phone hashing zero-knowledge contact discovery.
8. Never use a global shared message-encryption secret.
9. Never fail open on cryptographic verification.
10. Server-authoritative business operations need idempotency keys and audit trails.
11. Release signing belongs in CI secrets, not Git.
