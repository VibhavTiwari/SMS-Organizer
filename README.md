# SMS Organizer

An independent, offline-first Android SMS app. The UI uses familiar category tabs, inbox search, conversation views and a compose button. It does not use Microsoft's code, branding or assets.

## Development status

This branch is an app foundation, **not a default-SMS replacement yet**. It opens a user-selected SMS XML export on the phone and lets the user search or filter it. Choose a local export in the common `<smses><sms … /></smses>` format. Import is read-only and in memory; it does not restore messages or persist the archive. Microsoft backup compatibility is not verified. The app does not request SMS or Internet permissions, upload data, send messages or change the default handler.

Do not switch your primary phone's default messaging app to a development build. Before enabling the default role we need native SMS and MMS receive/send, safe role handoff, notification and dual-SIM tests, and proper device validation. Backup/restore and live carrier validation are separate from archive review. No RCS support is implied.

## Stack

- Android: Kotlin, Jetpack Compose, Material 3, Android platform APIs.
- Core: pure Kotlin in a Kotlin Multiplatform module. Category rules are deterministic and can be corrected per message. The main inbox excludes messages in Spam or Hidden messages. The current Gradle target is JVM; future iOS targets can be added without bringing over the Android UI or Telephony code.
- iOS later: a separate SwiftUI interface for the subset of features permitted by Apple.

## Build

Use JDK 21, Android SDK Platform 36 and Gradle 8.13.

```sh
gradle :organizer-core:jvmTest :app:lintDebug :app:assembleDebug
```

Use synthetic test data in this repository. Never commit or upload a personal export or screenshot with message content.

### Inbox organizer

- **Categories:** separate personal texts, payments, bills, travel, OTPs and
  promotions. Open a conversation and choose **Change category** to override a
  result or restore the automatic category.
- **Reminders:** supported bill and train texts offer a reminder. Nothing is
  scheduled until you confirm it. Review or cancel reminders from **More**.
- **Spam and smart cleanup:** review high-confidence spam separately; review
  old OTPs and promotions before hiding them. Nothing is automatically deleted
  from the phone or the import.

See [Inbox organizer test guide](docs/inbox-organizer.md) to try these flows
with an invented inbox instead of your own texts.

### Live messaging work in progress

The native SMS layer now connects a paged system inbox and a composer to
Android's messaging APIs. A send requires the SMS role, permission, an active
SIM and a confirmation showing the recipient and SIM. It records status for
each segment without copying the message body to the app's send journal, never
retries automatically, and does not treat a carrier send result as proof of
delivery.

The app still cannot request the default role and does not declare SMS, MMS
or phone-state permissions. Carrier-delivery receiver code is not registered.
The XML archive remains the only inbox available in this build, and both
features below require the complete default-SMS path before live use.

- [Schedule Send](docs/schedule-send.md) adds an on-device SMS queue with a date,
  time and explicit SIM, including edit/cancel and send status.
- [Verification code notifications](docs/otp-notifications.md) add an optional
  lock-screen preview for clearly identified incoming SMS codes.

Do not use this build as a messaging replacement.

Before enabling an opt-in lab build, test synthetic single and multipart SMS,
restricted/missing SIM, carrier rejection, early process death, partial or
unknown send, photo and group MMS in both directions, and switching back to the
previous Android messaging default. Keep any real texts on the device.

See [MMS groundwork and release gate](docs/mms.md) for current photo and group-message coverage and known limits.
