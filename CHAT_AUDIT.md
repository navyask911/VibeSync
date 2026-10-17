# Chat Experience Engine — Step 0 Audit

**Baseline:** `1cf11fbe8284a48d32c9e38a68fb67e9691a5aff` on `fix/release-startup-icons-ci`  
**Scope:** delivery receipts, background message sync, local state, UI presentation, server retention.

## Findings

1. **Durable local message pipeline exists.** `ChatMessageEntity` persists plaintext for locally-created outgoing messages and has a `deliveryState` column. `PendingMessageSyncScheduler`, `PendingMessageInboxSyncWorker`, and `VibeSyncOutboxWorker` provide durable inbox/outbox work.
2. **The FCM receiver is too permissive for chat pushes.** `AppFirebaseMessagingService` currently has a legacy path that reads sender/name/text/media fields, persists that body directly, and posts a notification. It also supplies demo age/city/score fallbacks. Chat wakeups must be metadata-only and resolve ciphertext through the authenticated inbox sync.
3. **Delivery UI is not aligned with the required state contract.** The bubble derives short relative time text, and outgoing states do not distinguish server receipt (single red tick) from recipient delivery (double grey) and read (double green). A profile intro card is still inserted into the message list ahead of the date-grouped timeline.
4. **Room is at schema v62.** The prior migration preserved encrypted payloads after decryption failures. Additive v63 fields are the safe path for delivery/read timestamps; keep the SQLCipher migration chain intact.
5. **Receipt storage must outlive ciphertext.** Deleting a delivered row from `chat_messages` means its delivery/read state cannot be recovered from that row. A small receipt table and a separate, access-controlled participant mapping are needed. Only the four required receipt fields belong on the Realtime-published table.
6. **Storage removal must use the Storage API.** SQL deletion from `storage.objects` can orphan stored bytes. The acknowledgement response therefore identifies any media reference and the app removes an associated object through the authenticated Storage API; the database migration does not delete Storage metadata directly.
7. **The chat screen already has lifecycle callbacks for realtime.** The chat view starts/stops its current realtime hook on resume/pause. Reuse that lifecycle and keep one receipt subscription scoped to the active conversation; do not add a screen-global or application-lifetime socket.
8. **Validation boundary.** GitHub Actions is the build authority for this branch. Static inspection cannot prove physical-device push delivery, Firebase server credentials, project Realtime publication settings, or the actual Storage bucket/path encoding; verify those separately after CI.

## Implementation plan

- Add a restricted server receipt ledger, recipient-only hard-delete acknowledgement, decryption-failure NACK, insert/update receipt triggers, and a 30-day undelivered-message cleanup schedule.
- Advance Room to v63 with delivery/read timestamps; display exact local timestamps and the specified four-state tick palette; remove the scrolled intro card and use neutral light/dark bubble surfaces.
- Route `type=new_message` data-only pushes directly to expedited inbox sync. Do not consume message bodies, plaintext, or media URLs from push data; retain direct reply through the existing outbox worker.
- Add focused JVM tests for delivery-state rendering, clock formatting, and the hard-delete request shape.
- Keep uploads, document/contact pickers, location refresh, payment placeholder, AI-image proxy and city wallpaper catalog as explicit follow-up surfaces if not safely integrated in the same patch; never simulate a completed server-side integration.
