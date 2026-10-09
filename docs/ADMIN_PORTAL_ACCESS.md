# VibeSync Admin Portal Access

## Hidden entry

On the Truecaller / secure phone-verification screen, tap the VibeSync logo/header seven times consecutively. This opens the Admin Portal Login screen. The shortcut only navigates to the login UI; it is not an authorization boundary.

## Current local login gate

The Android app reads `adminUsername` and `adminPassword` from local Room preferences. When those fields are blank, the code currently falls back to:

- Username: `creator`
- Passcode: `9999`

These are client-side prompt values, not secure server credentials. They must not be treated as sufficient authorization for administrator functions.

## Server role mechanism found in source

The migration `supabase/migrations/20261007_vibesync_security_core.sql` creates `public.platform_staff` with:

- `auth_user_id UUID`, referencing `auth.users(id)`
- `role`, constrained to `OWNER`, `ADMIN`, `MODERATOR`, `SUPPORT`, or `LEGAL_REVIEW`
- `active BOOLEAN`

The `public.is_platform_staff()` RPC returns true only if an active row matches `auth.uid()`. Normal client roles cannot directly read or write the staff table.

## No seeded production admin account

The repository contains no migration that provisions an admin staff row, Supabase Auth UID, or server admin password. The current Truecaller flow uses an Auth-free opaque VibeSync session header (`x-vibesync-session`) resolved by `public.vibesync_current_profile_id()`; that is a different identity mechanism from `auth.uid()`. Therefore, the current staff RPC can reject a normal Truecaller session even when the local prompt accepts its fallback values.

**The current codebase does not identify a confirmed working production admin account.** Do not assume `creator / 9999` alone grants access, and do not embed a service-role key or fabricated owner UID in the Android app.

## Required provisioning before production admin use

A project owner must explicitly select the administrator identity and provision it through a trusted server-side Supabase administrative path. Then align the staff authorization RPC with the app's identity model (either use a real Supabase Auth JWT and `auth.uid()`, or implement a narrowly scoped role mapping for validated VibeSync profile sessions). Keep role-table writes server-only, audit role changes, and verify that inactive/non-staff sessions are denied. This UI/client commit does not modify or apply any SQL migration or assign a role.
