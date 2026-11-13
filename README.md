<!--
SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
SPDX-License-Identifier: AGPL-3.0-or-later
-->
# Pvot Finder

A privacy-first, offline device finder for Android over Bluetooth Low Energy, without Google, Apple or
Samsung finding networks.

## What it does today

Your own phones find each other directly over Bluetooth, with no internet connection and no server.

- **Pair** two phones by showing a QR code on one and scanning it with the other, then checking that
  both show the same 6-digit code. Pairing uses the Noise protocol; see [docs/protocol.md](docs/protocol.md).
- **Let my phones find this one** makes the phone advertise an identifier that changes every ~17
  minutes and that only its paired phones can recognise. It keeps working in the background, with a
  notification saying so.
- **Look for nearby phones** shows your paired phones as "maybe nearby" when their identifier is
  heard, with signal strength. **Check it is really there** connects and makes the phone prove it holds
  its key ("verified nearby").
- **Play sound** makes a paired phone ring at alarm volume for 30 seconds, always with a notification
  that stops it. A phone that cannot show that notification refuses to ring.
- **Diagnostics** shows the Bluetooth, permission, advertiser and scanner state and the raw events.

## Limits

- **Direct range only.** A phone is found only within Bluetooth range of one of your other phones.
  Relaying through other people's phones is designed but not built.
- Anyone scanning can see that *a* Pvot Finder phone is nearby, and can follow one identifier for up
  to a period. See [docs/threat-model.md](docs/threat-model.md).
- **Looking happens in the foreground**: scanning stops when the app leaves the screen. Being findable
  continues in the background, but stops if the app is force-stopped or the phone is off, and does not
  restart after a reboot yet.
- Signal strength is a rough sign of closeness, not a distance.
- Whoever holds one of your unlocked phones can find your others; after losing a phone, use
  **Reset Pvot Finder** on the remaining ones and pair again.
- Needs Android 13 or newer and Bluetooth LE advertising support.
- Permissions: Nearby devices (`BLUETOOTH_SCAN` with `neverForLocation`, `BLUETOOTH_ADVERTISE`,
  `BLUETOOTH_CONNECT`), Camera (scanning a pairing code only), Notifications (ringing), and a
  connected-device foreground service. No internet permission.

## Building

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug, installs as org.prauga.finder.dev
./gradlew test                 # unit tests
reuse lint                     # licensing
```

## Licence

AGPL-3.0-or-later. Icons from [Tabler Icons](https://tabler.io/icons) (MIT); see
`THIRD_PARTY_NOTICES.md`.
