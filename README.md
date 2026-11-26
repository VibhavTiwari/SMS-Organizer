# SMS Organizer

An independent, offline-first Android SMS app. The UI uses familiar category tabs, inbox search, conversation views and a compose button. It does not use Microsoft's code, branding or assets.

## Development status

This branch is an app foundation, **not a default-SMS replacement yet**. It opens a user-selected SMS XML export on the phone and lets the user search or filter it. Choose a local export in the common `<smses><sms … /></smses>` format. Import is read-only and in memory; it does not restore messages or persist the archive. Microsoft backup compatibility is not verified. The app does not request SMS or Internet permissions, upload data, send messages or change the default handler.

Do not switch your primary phone's default messaging app to a development build. Before enabling the default role we need native SMS and MMS receive/send, safe role handoff, notification and dual-SIM tests, and proper device validation. Backup/restore and reminders have separate correctness and privacy requirements. No RCS support is implied.

## Stack

- Android: Kotlin, Jetpack Compose, Material 3, Android platform APIs.
- Core: pure Kotlin in a Kotlin Multiplatform module. Category rules are deterministic and advisory; the `All` view remains unfiltered. The current Gradle target is JVM; future iOS targets can be added without bringing over the Android UI or Telephony code.
- iOS later: a separate SwiftUI interface for the subset of features permitted by Apple.

## Build

Use JDK 21, Android SDK Platform 36 and Gradle 8.13.

```sh
gradle :organizer-core:jvmTest :app:lintDebug :app:assembleDebug
```

Use synthetic test data in this repository. Never commit or upload a personal export or screenshot with message content.

### Live messaging work in progress

The native layer now knows how to page the system SMS inbox and save one complete
inbound SMS, including multipart text. It checks ownership of the Android SMS
role before system inbox access. This path is intentionally not registered in
the manifest. It does not change the default app, request sensitive permissions,
or route carrier messages to this build. Only the XML archive flow is usable.
The shared core also distinguishes a partial outgoing SMS from a failed or
unconfirmed one. It does not automatically retry any send, and it does not
claim carrier acceptance is a delivery receipt. The device send path is not
switched on.
