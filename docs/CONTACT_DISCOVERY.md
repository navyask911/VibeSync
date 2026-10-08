# VibeSync Contact Discovery Protocol

Copyright © 2026 VibeSync. All rights reserved.

## Objective
Find which people in the device address book are VibeSync users without uploading the address book as a permanent cloud contact database.

## Current implementation truth
Plain SHA-256 of phone identifiers is a privacy-preserving pseudonymous index, not a cryptographic zero-knowledge proof. The scaling white paper's older wording must therefore be read as a design objective, not a proof of the current protocol. fileciteturn146file3L57-L76

## V1 requirements
- canonicalize phone identifiers locally;
- generate only approved privacy-preserving identifiers;
- send lookup tokens only to an authenticated matching endpoint;
- never send the complete contact list;
- never query profiles by raw phone number;
- never fall back to Firestore/Realtime Database matching;
- return only the minimum profile information needed to show a confirmed match;
- cache the UI result locally for fast/offline display.

## V2 target
A PSI/OPRF-style protocol should be evaluated so the server cannot infer the user's contact set from repeated queries. The upgrade is gated on a reviewed cryptographic design and interoperability tests.