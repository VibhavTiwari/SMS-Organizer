# Verification code notifications

Incoming carrier SMS only. Android's protected default-SMS delivery is validated,
assembled, and stored before any notification is posted. Imported messages never
trigger alerts. The development build deliberately does not register the carrier
receiver or request SMS permissions. When enabling the default-SMS role, declare
`RECEIVE_SMS` and register `.telephony.PlatformSmsDeliveryReceiver` as exported
with `android:permission="android.permission.BROADCAST_SMS"` and only the
`android.provider.Telephony.SMS_DELIVER` action.

The notification never includes a code until the user explicitly opts in under
Notifications. This is separate from Android 13+ notification permission.
Eligible messages come from Indian service-style sender IDs and contain one
unambiguous ASCII numeric code explicitly labelled as a verification code or
OTP. This is deliberately stricter than the OTP inbox tab. Ordinary numbers,
alphanumeric codes and formats we can't confidently parse stay in the inbox.
A service-style ID is not an authenticity check.

After opt-in, the code is in the title of a public local-only notification;
no message body, sender, additional data, copy action, or full-screen intent
is included. Android controls lock-screen content and peek/AOD appearance,
and can substitute a generic notification even if opted in. A new eligible
message replaces the previous notification. It is dismissed after 90 seconds,
when tapped, or when the opt-in is turned off. The app doesn't store a
separate code history; this timeout does not erase entries that Android or
authorized notification listeners may have retained. Never grant unknown
apps notification access.

Before device release, verify on a locked Samsung device using synthetic
carrier SMS: unambiguous code without opt-in, after opt-in with lock-screen
content allowed, channel denied, lock-screen content hidden, DND, after
turning opt-in off, and after switching default SMS app. Confirm no code
appears for imported or ambiguous messages, the latest alert replaces the
previous one, and the alert times out.
