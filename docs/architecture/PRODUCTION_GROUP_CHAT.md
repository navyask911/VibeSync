# VibeSync production group chat

## Components

- chat_groups stores group title, description, avatar reference, owner, invite code, and two permissions.
- chat_group_members stores current role and per-member pin/mute preferences. A non-null left_at is a historical membership, not active access.
- chat_group_messages is timeline metadata (message ID, sender profile/name and timestamp). It does not store the plaintext message.
- chat_group_message_envelopes has one TINK_ENC: payload for each active recipient, including the sender's current profile. The RPC rejects missing, duplicate or inactive recipients and requires the envelope count to equal the active membership count.

## Encryption and send path

1. Android resolves the active group roster and each profile's published Tink public key. Sending fails closed if a recipient key cannot be resolved.
2. The sender encrypts the text once per member using Tink hybrid encryption. Associated data is the versioned tuple vibesync-group-v1|groupId|messageId|senderProfileId|recipientProfileId. A ciphertext copied to another group/message/identity tuple will fail authentication.
3. vibesync_send_chat_group_message validates the verified session, group role, admin-only messaging setting and recipient set, then writes metadata and recipient envelopes in one transaction.
4. A database trigger POSTs a content-free envelope event to vibesync-fcm-dispatch. The edge function rereads authoritative rows, checks the sender/recipient and active membership, then sends data-only GROUP_CHAT_SYNC_WAKEUP FCM messages to the recipient profile's registered device tokens. It skips the sender. Push payloads contain identifiers only—not message text or the ciphertext.
5. Android enqueues unique WorkManager sync work. It fetches only the current recipient's envelopes, decrypts locally with the device key and writes the plaintext to the SQLCipher Room database. Foreground group screens refresh the encrypted timeline every five seconds.

## Access boundaries

- Group tables have RLS enabled. Reads require a verified session and active membership; envelope reads are further limited to recipient_profile_id = current profile.
- Authenticated direct table writes are not granted; management and send operations go through the explicit RPCs. Group RPCs are marked SECURITY DEFINER with fixed search paths and have PUBLIC/anon execution revoked.
- Only active members can join by a current invite code. Owners/admins can rotate or revoke invite links. Invite codes are returned by the group-list RPC only for owners/admins; direct table SELECT grants omit the invite_code column. Owners control deletion; owners/admins manage roles and group settings. The server enforces roles independently of UI visibility.
- Android registers an HTTPS App Link for https://vibesync.app/g/{inviteCode} and joins via vibesync_join_chat_group_by_invite.

## Notifications and UX

- Each incoming group notification uses NotificationCompat.MessagingStyle, setGroupConversation(true), the group title and a Person identity/icon based on the sender name. Notification IDs are stable per group.
- Group list order includes the member's pin preference; mute windows support 8 hours, one week and an effectively indefinite 2099-01-01 deadline.
- The shipped group-chat UI includes multi-select known VibeSync members, a circular image preview, a 512×512 WebP crop/upload, sender-name headers with sender-stable tints, roster/role management, permission toggles, invite sharing/revocation, pin/mute, exit and owner deletion. Group voice/video call controls are intentionally absent.

## Deployment notes

- 20261015041235_production_group_chat_architecture.sql creates the schema/RLS/RPCs, avatar storage path policies and the database-to-FCM webhook trigger.
- 20261015042338_group_rpc_security_definer.sql explicitly sets the group RPC security attributes; 20261015042358_group_message_all_member_envelopes.sql adds the all-active-members envelope guard.
- The existing avatars Storage bucket is configured as public by the legacy profile-avatar flow. Group avatar writes are restricted by the new owner/admin path policies, but an object URL placed in avatar_url is still publicly readable to anyone who obtains it. Do not store sensitive imagery there; making group avatars private needs a separate private bucket or client-side encryption/signed-URL migration that does not break existing profile avatar reads.
- Android App Link auto-verification requires the domain owner to host a valid /.well-known/assetlinks.json associating vibesync.app, the Android package and the release signing certificate. The manifest filter alone does not prove domain verification.
- Messages to a member whose Tink public key has not yet been published are intentionally rejected rather than falling back to plaintext or server encryption.
