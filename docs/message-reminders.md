# Bill and train reminders

Open a supported message in a conversation. Tap **Review bill due reminder** or **Review train departure reminder**, compare against the message, then tap **Set reminder**. Future reminders can be cancelled under **More → Reminders**. Suggestions also work with the SMS XML preview, so this flow does not require switching the default messaging app.

- Bills: an explicit future due date, with a four-digit year. The proposed reminder is at 9 am on that date.
- Trains: a railway/booking marker, an explicit journey or departure date with a four-digit year, and a departure or boarding time. The proposed reminder is one hour before departure.
- Named months, ISO dates and unambiguous numeric dates are recognised. No reminder is inferred from a missing year, an ambiguous numeric month/day, arrival times, conflicting labelled dates, paid bills or cancelled/rescheduled journeys. Compare the displayed time zone with the message.
- Confirmed reminders require notifications. Android may delay delivery under battery restrictions, especially without exact alarm access. The app restores active reminders after reboot and unlock; it never alerts for trains that have already departed or bills whose due day has ended.

The parser runs locally. Reminder records contain an opaque message key, reminder kind and timing, not the sender or message text. Notifications are private, use generic wording and do not include financial or travel details.

## Device acceptance

1. Import a synthetic preview with a bill due tomorrow and a train departure roughly 70 minutes from now. Check the date/time and time zone in the review dialog, back out of the bill and confirm only the train. Nothing should be in the reminders list for the bill.
2. Deny notification permission; check that confirming another eligible message fails clearly. Grant notifications, confirm the bill and ensure its duplicate cannot be scheduled. Cancel it and check that it disappears.
3. Reboot and unlock before the train alert. Confirm exactly one generic notification around the scheduled time, subject to Android's alarm permissions and power management. Repeat with exact alarms disabled to verify the delay warning, and with a train whose departure expires before unlock to verify no stale alert.
