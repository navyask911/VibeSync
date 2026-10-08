#!/bin/sh
#
# VibeSync Gradle launcher.
# CI installs the pinned Gradle version (9.3.1) with gradle/actions/setup-gradle.
# Keeping the project entry point as ./gradlew makes local/CI commands consistent.
#
set -eu

if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle 9.3.1 is required. In CI, use gradle/actions/setup-gradle@v5." >&2
  echo "For local development, install Gradle 9.3.1 or run through your IDE." >&2
  exit 1
fi

exec gradle "$@"
