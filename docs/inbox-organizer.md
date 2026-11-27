# Inbox organizer: smoke test

Run `python3 samples/generate-inbox-review.py > fictional-review.xml` to create an
invented SMS archive with dates relative to today. Copy it to a test Android
phone and choose **More → Open export**. Keep this file to repeat the same tests;
regenerating it creates new timestamps and therefore different message identities.

1. Check the Transactions, Bills, Travel, OTPs, Promotions and Personal tabs.
   Open a message, change its category and verify it moves. Reset the correction
   and verify the automatic category returns. Open the same export again to
   verify that an explicit correction persists.
2. Open the electricity bill and the train text. Check the event and proposed
   reminder times before confirming. Confirm one, find it in **More → Reminders**,
   reopen the app, then cancel it. Neither ambiguous dates nor dates without an
   explicit year should offer a suggestion. Android must allow notifications
   and exact alarms for precise timing. Date-only bills use a suggested reminder
   at 9 am; train reminders are suggested one hour before the parsed departure.
3. Verify the synthetic scam is in **More → Spam**, not in All or search. Mark
   it as not spam and verify that All finds it. Personal messages and ordinary
   marketing links should not be treated as spam just for containing a link.
4. In **More → Smart cleanup**, review an expired OTP and promotion. Hide only
   the selected item, then restore it under **More → Hidden messages**. Opening
   the same export again should preserve the app's choices. The original export
   is unchanged.

These flows operate on the device. They do not send a text or change the default
SMS app. This archive build does not exercise live delivery, lock-screen OTP
arrival, SIM handling or carrier MMS.
