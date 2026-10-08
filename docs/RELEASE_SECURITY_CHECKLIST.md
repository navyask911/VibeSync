# VibeSync Release Security Checklist

Copyright © 2026 VibeSync. All rights reserved.

## Build gates
- `./gradlew test` passes.
- `./gradlew assembleDebug` passes.
- `./gradlew assembleRelease` passes.
- `./gradlew bundleRelease` passes.
- Android manifest reviewed for minimum necessary permissions.
- release minification/shrinking reviewed before Play distribution.
- secret scan passes.
- no keystore or signing material in Git.

## Backend gates
- Supabase security advisors reviewed.
- RLS enabled on every exposed table.
- no anonymous write access to application data.
- no unauthenticated SECURITY DEFINER RPCs.
- all privileged Edge Functions require verified authentication or explicit webhook authentication.
- business ledger reconciliation tested.
- automatic retention cleanup tested.

## Privacy gates
- contact discovery transmits only the approved privacy-preserving representation.
- no raw address-book upload path remains.
- FCM payloads contain ciphertext/notification metadata only as required.
- no Gemini/API secret is in the APK.
- no R2 access secret is in the APK.

## Legal/product gates
- Privacy Policy, Terms, License, and safety contacts are current.
- age gate and minor-protection rules are verified.
- ad disclosures, frequency caps, instant skip, and viewability billing are verified.
- lawful-request and grievance workflows are operational.
- payment provider terms and merchant compliance are verified.