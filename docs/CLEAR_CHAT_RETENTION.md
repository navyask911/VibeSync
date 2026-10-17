# Clear Chat retention and recovery boundaries

Clear Chat is a **device-local history operation**, not a server-side or cross-device deletion command.

## Local barrier

The SQLCipher Room table `conversation_clear_guards` stores a per-local-match clear cutoff and a durable `isClearing` barrier. During a clear, message sends and message ingestion are blocked for that match, scoped work is cancelled best-effort, Room message/projection/unread/cursor rows are deleted in one transaction, and notification/media cleanup is completed after the transaction. A recovery worker resumes an interrupted clear. The cutoff remains after cleanup so a stale inbox page, Realtime event, or FCM wake-up cannot repopulate old history.

Ingestion compares the server-authoritative `created_at` value (with the existing timestamp fallback only for display) against the local cutoff before persistence. When a cutoff exists, an absent or malformed authoritative timestamp is suppressed rather than being assigned the device's current time. A genuinely newer message can be received normally.

## Limits

- The cutoff is generated on this device's clock; the server does not enforce it. Device clock skew can conservatively suppress a legitimate new message or, if a server-provided creation timestamp is inaccurate, affect the comparison. The client should fail closed for unknown timestamps.
- The cutoff/guard lives in this app's local database/preferences. Uninstalling, clearing app data, or restoring a backup made before Clear Chat can remove or roll back that device-local barrier. It is not a tombstone replicated to other installations.
- The server's existing delivery protocol deletes a recipient ciphertext row through `acknowledge_and_purge_message` after successful decrypt-and-store acknowledgment. Undelivered server rows remain until the configured 30-day TTL cleanup. Durable receipt and participant metadata remain independently of ciphertext; encrypted Storage objects are cleaned through a separate, retryable Storage API operation.
- Therefore, Clear Chat must not claim to erase the other participant's history, delete all server copies, or guarantee restoration. Use the explicit UI wording that this phone's local history is cleared.

## Receipt recovery

READ and DELIVERED are durable server receipt statuses. Realtime/push is only the quick notification path. On chat resume and after a Realtime subscription/reconnection is accepted, the client fetches the authorized receipt table and applies statuses to this sender's matching local outgoing message IDs. Local status transitions are monotonic: a stale SENT_TO_SERVER, DELIVERED, or FAILED receipt cannot override READ.
