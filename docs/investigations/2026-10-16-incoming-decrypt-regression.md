# Part A: VibeSync incoming-decryption regression evidence

**Scope / refs.** This report compares `7eb72373b41f83ff74590a96548ca8c9320520fc` (short `7eb7237`) with `680a8cb0db382ecb7089633168593c14b019f955` (short `680a8cb`). GitHub's compare endpoint confirms `680a8cb` is exactly one commit ahead of `7eb7237`. Inspection is static; no private keysets, plaintext, or physical-device logs were used.

## A0. Diff summary: what changed

The relevant commit changes 13 files (the main crypto changes are `TinkCryptoManager.kt`, `SupabaseClientManager.kt`, `MessageDeltaSyncManager.kt`, the new `MessageDecryptionPolicy.kt`, and `PendingMessageSyncWorker.kt`).

- **New pre-decrypt recipient-key screening was added in `SupabaseClientManager.kt`.** In the delta fetch path around lines 2408–2420, the added `historicalRecipientKeyMissing` flag calls `TinkCryptoManager.hasRetainedRecipientKey(...)`; the decrypt call is conditioned on that flag being false. This check was absent at `7eb7237`.
- **A second early-return gate was added in `TinkCryptoManager.decryptPayload()`.** Around lines 704–712 it compares the envelope's recipient ID with `KeysetInfo.keyId.toString()`, invokes the mismatch callback, and returns an empty string before `HybridDecrypt.decrypt()`. The base revision did not reject on that comparison; it only logged metadata drift and continued to Tink.
- **The V2 envelope format did not change in this diff.** It still carries `version=2`, `recipient_key_id`, `recipient_key_fingerprint`, and Base64 ciphertext (`TinkCryptoManager.kt:287–315`). Normal-message encrypt and decrypt still use the existing deterministic context builder.
- **Fingerprint behavior did change** in `TinkCryptoManager.kt`: the previous full canonical-JSON fingerprint was replaced by SHA-256 over canonical primary public-key material; it excludes Tink envelope metadata. Public-only diagnostics and append-only key rotation / keyset continuity checks were added. This is separate from AAD and does not itself explain authenticated-decryption failure.
- **Peer publication/resolve behavior changed** in `SupabaseClientManager.kt`: active key IDs are checked against the live registry, publication confirmation is stored in Room, and resolution uses the authenticated active-key resolver. **Outbox behavior changed** in `PendingMessageSyncWorker.kt` to compare live/cached peer keys and fail closed rather than encrypt to a stale cached key. Neither changes the normal message AAD bytes.
- **Delta failure classification changed**: `MessageDeltaSyncManager.kt` now prefers `record.decryptionFailureReason` before the generic tombstone; the newly introduced early key-ID gate creates the key-change reason.

## A1. Where the placeholder comes from

The failure in this regression is **not necessarily a caught `GeneralSecurityException`**. The new metadata gate can skip the primitive entirely:

```kotlin
// TinkCryptoManager.kt (680a8cb), around lines 704–712
val matchingRetainedKey = myHandle.keysetInfo.keyInfoList.any { info ->
    info.keyId.toString() == parsedCiphertext.recipientKeyId && info.status.name == "ENABLED"
}
if (!matchingRetainedKey) {
    onMismatchedKey?.invoke()
    return ""
}
```

The new upstream gate compounds this:

```kotlin
// SupabaseClientManager.kt (680a8cb), around lines 2408–2420
val historicalRecipientKeyMissing = context != null && !ownMessage &&
    cipherText.startsWith(TinkCryptoManager.TINK_PREFIX) &&
    !TinkCryptoManager.hasRetainedRecipientKey(context, cipherText)
...
!historicalRecipientKeyMissing && cipherText.startsWith(...) && aad != null
```

If that condition is true, the expression takes the `else ""` branch and never calls `verifyAndDecryptPayloadWithImmediateKeyRefresh`. The retention helper repeats the signed comparison around `TinkCryptoManager.kt:590–596`. The ID in `KeysetInfo` is a protobuf `uint32` represented by Tink's Java/Kotlin API as a signed `Int`; a key ID of 3952343082 becomes the signed `Int` -342624214. Its ordinary `.toString()` is therefore not equal to the public JSON/envelope decimal string `"3952343082"`.

The UI text follows from the empty result rather than identifying a GSE: `MessageDeltaSyncManager.kt:225–226` falls back to `record.decryptionFailureReason` or `MessageLocalRetentionPolicy.DECRYPTION_TOMBSTONE_TEXT`; `MessageDecryptionPolicy.kt:25` uses the same tombstone when the decrypt lambda returns no renderable text. In the actual primitive path, `TinkCryptoManager.kt:741–750` invokes `decryptor.decrypt(cipherBytes, contextInfo)` and catches failures there. Thus the new key-ID pre-check can produce the user-visible failure **before** that catch/primitive path executes.

## A2. Associated-data trace

Normal one-to-one message paths:

1. **Encrypt:** `PendingMessageSyncWorker.kt:280–284` calls `TinkCryptoManager.encryptForRecipientOrThrow` with `senderPhone = senderProfileId` and `receiverPhone = recipientProfileId`. Despite the parameter names, these are profile IDs. `TinkCryptoManager.kt:521–522` computes `createDeterministicContext(senderPhone, receiverPhone)`, then passes those bytes to `HybridEncrypt.encrypt(plaintext, contextInfo)`.
2. **Delta decrypt:** `SupabaseClientManager.kt:2400–2408` passes the server row's `sender_id`, `receiver_id`, and `match_id` to `resolveAadParticipantIds`. For a valid composed match ID, that resolver parses the two IDs and orders them lexicographically (`TinkCryptoManager.kt:474–498` in this revision). It then invokes `verifyAndDecryptPayloadWithImmediateKeyRefresh` with those IDs as `senderPhone` / `receiverPhone`; the primitive path ultimately calls `getAssociatedData` and `createDeterministicContext` (`TinkCryptoManager.kt:499–500, 743–744`).
3. **Conversation-list decrypt:** `SupabaseClientManager.kt:2514–2527` uses the same `resolveAadParticipantIds(sender_id, receiver_id, match_id, localProfileId)` result, and then calls `TinkCryptoManager.decryptPayload` with that returned pair.
4. **Primitive test helper:** `TinkCryptoManager.kt:550–559` parses the existing envelope and decrypts using `getAssociatedData(senderPhone, receiverPhone)`; it uses the same builder.
5. **Group messages are separate:** `TinkCryptoManager.kt:757–810` encrypts and decrypts using `groupAssociatedData(groupId, messageId, senderProfileId, recipientProfileId)` in fixed group/message/sender/recipient order. This path is not used by the reported one-to-one match IDs.

The normal context builder (`TinkCryptoManager.kt:449–463`) lowercases both nonblank participant IDs and sorts them before returning UTF-8 bytes of `lowerId_higherId`. It deliberately does not currently use message ID or direction. For the example IDs `605918cdddc5a40c` and `7ebef8956864b083`, the context on either phone is exactly UTF-8 for:

`605918cdddc5a40c_7ebef8956864b083`

A receiver whose local `matchId` is composed from its own perspective still resolves the parsed pair and sorts it again. **The sender and receiver context bytes are equal.** That is a protocol-binding limitation (no direction/message binding), but it is not the demonstrated reason these fresh one-to-one messages fail. Per the guardrail, do not change this to a new AAD or envelope scheme as part of this regression fix.

## A3. Key-ID representation and flows

- Public JSON primary ID: `TinkCryptoManager.primaryKeyId(root)` / `getPublicKeyMetadata()` read `primaryKeyId` and per-key `keyId` as decimal strings. The V2 envelope stores that string in `recipient_key_id`; its parser returns a string (`TinkCryptoManager.kt:287–315, 356–384`).
- Actual Tink metadata: `KeysetInfo.keyInfoList[*].keyId` and the IDs returned from rotation are `Int` values (`TinkCryptoManager.kt:158–176`). `rotateMyPrimaryKeyKeepingHistory()` uses the integer internally with `setPrimary(newId)`, then returns `newId.toString()` around lines 162–176; this conversion can print a negative decimal for IDs whose high bit is set.
- **Defective comparisons:** `TinkCryptoManager.kt:595` (helper) and `TinkCryptoManager.kt:706` (decrypt gate) compare `info.keyId.toString()` against the unsigned decimal envelope string. For ID 3952343082, these strings are `"-342624214"` and `"3952343082"`. Both comparisons fail despite the ID bits being the same.
- Supabase publication and active-key resolution carry the JSON / RPC `key_id` as strings; current checks compare the returned string with `PublicKeyMetadata.keyId` (`SupabaseClientManager.kt:1124–1130, 1163–1205, 1381–1395`). No signed integer cast is used in these direct REST/RPC string comparisons.
- Room metadata added in the previous revisions stores key IDs as TEXT: `tink_key_publications.keyId`, `peer_key_changes.previousKeyId`, and `peer_key_changes.newKeyId`. They are not Room numeric columns, so they can preserve the decimal unsigned representation. The ciphertext JSON is also a string.
- The reported key is therefore enough to trigger the new gates. IDs below (2^{31}) will commonly appear the same under signed and unsigned decimal formatting, masking the defect.

## A4. Keyset / publication path

The published public key is derived from the same managed handle used by decrypt: `getMyPublicKeysetJson(context)` calls `getOrCreateMyKeysetHandle(context)`, then `serializePublicKeysetJson(handle)` serializes `handle.publicKeysetHandle`; decrypt obtains the private primitive from that same `getOrCreateMyKeysetHandle(context)` result (`TinkCryptoManager.kt:90–145, 185–193, 680–700`). The manager uses the protected `AndroidKeysetManager` entry `vibesync_private_keyset` in `vibesync_tink_keyset_prefs`, protected by `android-keystore://vibesync_master_key`. Rotation opens that same manager, adds a key, promotes it, and asserts the old IDs remain (`TinkCryptoManager.kt:140–177`); startup and publication do not explicitly rotate. If the stored keyset is missing but the provisioned marker still exists, the function fails closed; keyset-unwrapping/build errors are caught and return null. The continuity marker is in the same preferences file, however, so total loss of that entire preferences file also loses the marker and cannot be distinguished from a fresh install by that marker alone.

Publication serializes the live local handle and submits it through `rpc/publish_my_tink_public_key`; the resolver uses the active published key registry. The code inspected does not show a second unrelated key-generation path for normal publication/decrypt. There is no evidence here that the published public key derives from a different private keyset than the local decrypt handle.

## A5. Ciphertext, placeholder, and server cursor

- The delta fetch initially preserves the remote ciphertext in `ChatMessageEntity.text` when decryption fails (`SupabaseClientManager.kt:2435–2455`).
- **That ciphertext is then overwritten in the local Room row with placeholder text.** `MessageDeltaSyncManager.kt:225–226` chooses `KEY_CHANGED_TOMBSTONE_TEXT` / `DECRYPTION_TOMBSTONE_TEXT`; the following `remote.copy(text = localText, ...)` is persisted to both chat-message DAOs. Thus the placeholder is stored rather than just generated at display time, and that local row no longer contains the source ciphertext.
- The server copy is **not consumed/deleted by this failure path**. Delivery ACK is conditional on `canRenderPlaintext`, and the sync watermark advances only if both `batch.complete` and `complete` are true (`MessageDeltaSyncManager.kt:276–286, 352–362`). Failed incoming decrypt leaves `complete=false`, so the watermark remains unchanged and the server row can be fetched again. The original ciphertext is thus still available from Supabase for retry, even though it is not preserved in the failed local row.
- This persistence/display coupling needs follow-up to fully satisfy the requested retry-state model, but the immediate fresh-message regression is the newly added signed/unsigned-ID pre-check.

## Ranked verdict

1. **H2 — proven primary cause.** `680a8cb` adds a pre-decrypt gate comparing an unsigned public/envelope key ID string with signed `KeysetInfo.keyId.toString()`, both in `SupabaseClientManager.kt` and `TinkCryptoManager.kt`. A high-bit key ID fails the check and skips the primitive.
2. **H1 — disproven for this failure.** The existing normal-message context construction and receiver resolution both canonicalize the same pair of profile IDs to identical bytes. It lacks direction/message binding, but the bytes match for sender and receiver, so do not introduce a wire-format change in this fix.
3. **H3 — not supported as the primary cause by this diff.** Envelope format and AAD encryption/decryption calls are unchanged from `7eb7237`; the public key and decrypt key derive from the same AndroidKeysetManager handle. The new key-ID guard is sufficient to explain the observed behavior. The full-preferences-loss continuity-marker limitation and persisted placeholder behavior are documented above but do not supersede the direct H2 evidence.

**Fix direction dictated by evidence:** remove ID-metadata gates from all decrypt paths, always let Tink authenticate/decrypt the ciphertext, and normalize any retained-key diagnostic by interpreting Tink's key ID as unsigned. No B1 AAD/envelope change is justified by this evidence.
