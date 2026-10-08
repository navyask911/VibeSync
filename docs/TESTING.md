# VibeSync Testing Strategy

Copyright © 2026 VibeSync. All rights reserved.

## Test layers

### JVM unit tests

Run with:

~~~bash
gradle test
~~~

These tests cover pure Kotlin/data logic and Room-backed repository behavior without requiring a connected Android device.

### Compose UI tests under Robolectric

The JVM test suite includes UI coverage for the Truecaller-only login screen, its retry/error state, and registration form gating before date-of-birth completion.

### On-device Compose UI tests

CI runs:

~~~bash
gradle connectedDebugAndroidTest
~~~

inside an Android 35 emulator.

The instrumented suite verifies that critical Compose screens render under the real Android runtime and that registration controls remain correctly gated.

### Screenshot regression

GreetingScreenshotTest remains as a lightweight visual snapshot baseline for the root UI.

Screenshots are useful for visual review, but screenshot output is not treated as a substitute for behavioral UI assertions.

## Release verification

A release candidate must pass, in order:

1. secret scan;
2. `./gradlew test`;
3. release signing preparation;
4. `./gradlew assembleRelease`;
5. `./gradlew bundleRelease`;
6. artifact existence checks;
7. artifact upload.

CI intentionally fails before release packaging if required signing secrets are not available.

## Manual device matrix

Before public launch, verify at minimum a current Android handset, a tall-screen OnePlus-class device, notification permission handling, camera/microphone permissions, back navigation, Truecaller verification success/failure/retry, contact discovery permission and empty state, chat send/delivery/read state, business wallet/broadcast controls, account deletion, and light/dark theme.

This document describes the automated baseline; it does not claim that manual device validation has already been completed.
