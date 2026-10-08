# Supabase → Firebase row webhook

The deployed runtime entrypoint remains `app/functions/index.js` for compatibility
with the existing CommonJS Firebase codebase. The canonical TypeScript
implementation is `app/functions/src/supabaseRowWebhook.ts`.

## Firebase Secret Manager

Create the shared secret:

```bash
cd app
firebase functions:secrets:set SUPABASE_WEBHOOK_SECRET
```

Enter the exact same random value that will be stored in Supabase Vault.

Deploy the function:

```bash
cd app
firebase deploy --only functions:supabaseRowWebhook
```

The function is configured for `asia-south1`, so the expected endpoint is:

```
https://asia-south1-vibesync-chat-social-connect.cloudfunctions.net/supabaseRowWebhook
```

## Supabase Vault

In the Supabase SQL editor, store the endpoint and matching secret:

```sql
select vault.create_secret(
  'https://asia-south1-vibesync-chat-social-connect.cloudfunctions.net/supabaseRowWebhook',
  'firebase_supabase_webhook_url'
);

select vault.create_secret(
  '<THE-SAME-LONG-RANDOM-SECRET-AS-FIREBASE>',
  'firebase_supabase_webhook_secret'
);
```

Do not commit either secret value.

The migration attaches the trigger to `public.chat_messages` for INSERT and
UPDATE. It sends `type`, `table`, `schema`, and `record` (the NEW row) as
JSON using `pg_net` and the `x-webhook-secret` header.

The Firebase endpoint validates that header, records the event in
`supabase_webhook_events`, and optionally sends an FCM message when
`record.fcm_token` is present.
