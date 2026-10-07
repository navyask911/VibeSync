# VibeSync Changelog

## 2026-10-07
- Established Supabase as the authoritative application backend.
- Retained Firebase only for FCM push notification delivery.
- Hardened Google Tink encryption failure behavior.
- Removed local Firebase Auth session cleanup dependency from the production identity path.
- Added architecture, privacy, business, advertising, minor-safety, threat-model, lawful-request, and release-control documentation.
- Created a dedicated hardening branch for validation before production merge.