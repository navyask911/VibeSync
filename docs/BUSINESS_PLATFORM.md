# VibeSync Business Platform

Copyright © 2026 VibeSync. All rights reserved.

## Business products
- annual business listing
- optional verification tiers
- business wallet
- follower broadcasts
- CPM advertising
- direct customer chat
- business API/webhooks
- analytics and voucher/redemption reporting.

## Wallet
Product specification: 1 VibeSync Point = ₹0.05 and ₹1 = 20 Points. Broadcasts are priced at 1 Point per recipient follower. See the business architecture specification for the package model. fileciteturn146file1L32-L52

## Accounting rule
Room is never the financial authority. Every credit, debit, hold, capture, refund, reversal, and reconciliation action is a server-side ledger entry with an idempotency key.

## Payments
Payment gateways handle card/UPI credentials. VibeSync stores only payment-provider references and accounting metadata.

## Broadcast refunds
The published business model places broadcast recipient cost on hold and refunds recipients whose delivery did not succeed. This implementation must use actual delivery acknowledgments rather than an assumed delivery percentage. fileciteturn146file1L48-L60

## Ads
VibeSync ads are native and user-dismissible, with 1 ad after 5 profile swipes and 1 sponsored story after 5 stories, and no forced watch delay. fileciteturn146file0L8-L27