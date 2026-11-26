# Schedule Send

In Compose, enter a recipient, text and SIM, then tap **Schedule send**.
Review the date, time, time zone and SIM before confirming. Go to **More →
Scheduled messages** to edit a pending message, cancel it, or check its status.
Requires SMS Organizer to be the default Android texting app with SMS access
and, on Android 12+, access to **Alarms & reminders**. This is SMS only.
See the [Android exact-alarm guidance](https://developer.android.com/develop/background-work/services/alarms).

- The date and time are stored as an instant. Changing time zones or travelling
  doesn't move the instant. The list shows it in the phone's current time zone.
- Android may delay an alarm. The app won't submit a message 15 minutes or more
  after it was due. The phone must be on and unlocked at least once after reboot
  so Android can restore pending alarms from private storage.
- Cancelling or editing is possible until the app claims the message. A change
  never resubmits an in-progress message. A stale alarm cannot submit a revised
  or deleted entry. The chosen SIM is verified again at send time; no fallback.
- Scheduled texts are stored only in app-private storage, excluded from backup.
  Alarm intents carry an opaque identifier, not the recipient or text. The app
  clears draft text from its queue after the entry is claimed or missed.
- An interruption during submission leaves an unknown status rather than
  sending again. The carrier accepting the SMS doesn't prove delivery.

The current development manifest intentionally cannot become the default
texting app. It exposes the Scheduled list, but live scheduling and carrier
delivery must not be enabled until default SMS/MMS handling is complete.

## Device release checks

Use a spare Android handset, test SIMs and synthetic messages. Verify send with
screen off, restart before the due time, restart after the late cutoff, edit or
cancel near the due time, permission revoke/regrant, removal or disablement of
the chosen SIM, switching default SMS app, time-zone change, midnight and DST
boundaries, and process death during a multipart submission. Check the recipient
gets exactly one message when expected, and none on the blocked/missed paths.
