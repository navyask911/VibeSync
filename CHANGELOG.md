# VibeSync Changelog

## 2026-10-08 — Release hardening and verification
- Added JVM Compose UI coverage for login, OTP verification and registration gating.
- Retained optional on-device Compose UI smoke tests for manual/device validation; removed them from the primary release gate.
- Updated CI to decode the configured KEYSTORE_BASE64 release key into an ephemeral runner file.
- Made signed release APK/AAB existence an explicit CI gate.
- Uploads now include debug APK, release APK, release AAB, JVM reports and instrumented test reports.
- Reconciled Firebase documentation with the actual controlled legacy-compatibility boundary.
- Expanded privacy, terms, security, support, licensing and release documentation.
- Added an India-focused compliance/control mapping with the notified DPDP Rules 2025 commencement timeline.

## 2026-10-07
- Established Supabase as the authoritative application backend.
- Retained Firebase for FCM push delivery and controlled legacy compatibility.
- Hardened Google Tink encryption failure behavior.
- Removed local Firebase Auth session cleanup dependency from the production identity path.
- Added architecture, privacy, business, advertising, minor-safety, threat-model, lawful-request, and release-control documentation.
- Created a dedicated hardening branch for validation before production merge.
