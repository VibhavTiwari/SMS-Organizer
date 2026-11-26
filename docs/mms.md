# MMS groundwork and release gate

The development app is **not a default messaging app**. MMS code is not connected
to the UI, a manifest receiver or the carrier. Uploads or downloads were not made.

## Implemented

- Isolated Kotlin MMS Encapsulation/WSP codec: decode basic carrier notifications
  and retrieve responses; encode multipart/mixed send requests with one or more
  phone-number recipients, UTF-8 text, and JPEG/PNG/GIF. Attachments are bytes,
  not trusted executable content. Incoming text is returned as raw bytes; a
  user-facing preview still needs explicit charset/format validation. Incoming
  SMIL is skipped rather than rendered; unknown parts or headers produce an
  explicit unsupported result instead of being treated as a complete message.
- Android boundary: validate the protected WAP push action and media type, reject
  missing or conflicting SIM IDs, require the default role for carrier operations,
  and bind SmsManager to the concrete SIM. No general HTTP client. Send PDUs
  respect the smaller of the app limit and the available carrier size limit.
- Each transfer has a local UUID and an app-private staging file. Download reads
  have a hard size limit; that read limit is not a cap on bytes the platform may
  write before the callback. No remote filename is used as a local path.

## Required before enabling

1. Register protected WAP handling and a private explicit completion receiver.
   Register a non-exported FileProvider with URI grants for only the MMS
   staging directory (not the entire app cache), then verify Android's per-URI
   grants and callback lifecycle. Do not add a permissive provider to make a
   carrier integration work.
2. Persist transaction state before enqueue, use unique operation-bound callbacks,
   reconcile interruptions without automatic retry, implement acknowledgements,
   delivery/status handling and carrier variations, and keep unsupported incoming
   messages recoverable rather than falsely marking them processed. Debug staging
   is not durable inbox storage. Add size quotas and expiry/cleanup on the staging
   queue as part of integration.
3. Wire local image selection, safe preview/group participants and the persistent
   MMS inbox into the app. Receive and reply tests must exercise text, group and
   photo traffic, retry uncertainty, dual SIM, mobile data restrictions, invalid
   data and role handoff on supported Android versions and real carriers. Use a
   dedicated test handset before any switch on a primary phone.

References: [OMA MMS Encapsulation 1.3](https://www.openmobilealliance.org/release/MMS/V1_3-20050927-C/OMA-TS-MMS-ENC-V1_3-20050927-C.pdf)
and [Android SmsManager](https://developer.android.com/reference/android/telephony/SmsManager).
The code is an independent implementation of a narrow protocol subset, not a
vendored parser or a claim of full carrier conformance.
