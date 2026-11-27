# Inbox categories

The app categorises text locally. Personal conversations, transactions, OTPs, promotions, bills, and travel each have a tab. Other holds messages with no confident category. Category rules never delete, block, or move SMS in Android's shared provider.

- Outgoing texts start in Personal. Ordinary incoming numbers are treated as potentially personal; strong-looking financial, travel, or offer content without a service-style sender remains in Other. Service-style senders include registered Indian sender IDs, short codes, and all-capital alphanumeric senders. Sender shape is not verification.
- Service messages need multiple signals to count as a bank transaction, bill, or trip. Code detection needs one clearly labelled code; a nearby amount or card number is not enough. Personal-number OTPs also require a sign-in or code-sharing warning. This inbox rule does not widen the lock-screen preview filter.
- Open a conversation and choose **Change category** on an individual message. Choosing **Classify automatically** restores the automatic result. The correction applies only to that message, not to future texts from its sender.
- Corrections are stored locally as a salted message fingerprint and category. Opening the same XML export again reapplies its corrections. Reordered or modified exports may not match the prior corrections. Import remains a preview and does not change device history.

## Device acceptance checks

1. Open an XML export with synthetic personal, banking, bill, travel, promotion, OTP, and ambiguous messages. Check their starting tabs and that categories alone don't remove them from All.
2. Change a bill to Other. It should disappear from Bills and remain visible in Other. Reopen the app and same export. The correction should survive.
3. Reset it to automatic. Verify it returns to Bills. Open an unrelated export with the same row positions and timestamp but different bodies; corrections should not leak.
4. On the gated spare-phone build, repeat with live messages, then switch back to the stock texting app and verify that the underlying SMS history is unchanged.
