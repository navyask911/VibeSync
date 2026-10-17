# VibeSync Contact Discovery

Copyright © 2026 VibeSync. All rights reserved.

## How contact matching works

VibeSync reads device contacts only after the user grants Android contacts permission. For matching, the Android client normalizes phone numbers to E.164 and sends the selected number list in the JSON body of an authenticated HTTPS (TLS) request to the Supabase RPC `match_contacts_by_phone_numbers`.

Supabase performs an indexed match against `public.phone_lookup_index` and returns only the canonical VibeSync profile ID and the minimal profile metadata needed to render a matched friend. The server-side phone index is protected by row-level security and is not directly readable by anonymous or authenticated client roles; the RPC validates the current VibeSync session and matches only the supplied numbers.

## What is and is not private

- Phone numbers included in a contact-match request are transferred to Supabase over TLS strictly for friend matching. HTTPS protects the data in transit; it does not make the server unaware of the phone numbers.
- The server maintains a restricted index for verified account phone numbers. The app does not upload the contact display-name/address-book database as a permanent cloud contact list.
- The client stores returned contact matches in Room for offline UI performance. The legacy local `phoneHash` field stores the normalized E.164 string for schema compatibility; it is not a cryptographic hash.
- Private chat messages and media payloads are separately encrypted end-to-end with Google Tink. Contact discovery phone metadata is not covered by chat E2EE.

## Identity and conversation routing

The profile ID returned by the Supabase RPC is the canonical recipient identity. Conversation creation and message delivery should use that ID instead of a locally generated contact ID, display name, hash, or phone-number placeholder. A lookup that cannot resolve a registered canonical profile must fail closed rather than silently guessing a recipient.

## Operational requirements

- Keep the RPC authenticated and rate-limit batches; this implementation accepts at most 1,000 numbers per request and the Android client sends smaller batches.
- Never place phone numbers in URL query strings or application logs; send them in the HTTPS POST body.
- Keep the phone index inaccessible to client-side table queries. Only the controlled RPC and server-side service-role provisioning flow should read or write it.
- Continue to verify Google Tink E2EE and canonical-recipient behavior independently from phone matching.

