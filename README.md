<!--
SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
SPDX-License-Identifier: AGPL-3.0-or-later
-->
# Pvot Finder

A privacy-first, offline device finder for Android over Bluetooth Low Energy, without Google, Apple or
Samsung finding networks.

## What it does today

Two phones with Pvot Finder find each other directly over Bluetooth, with no internet connection:

- **Make this phone visible** advertises a random temporary ID, new each time advertising starts.
- **Look for nearby phones** lists other phones running Pvot Finder with their signal strength (RSSI)
  and when they were last heard. A phone drops off the list after 10, 30 or 60 seconds of silence.
- Tapping a phone shows its signal over the last minute.
- **Diagnostics** shows the Bluetooth, permission, advertiser and scanner state and the raw events,
  and copies them for bug reports.

## Limits

- **Testing only.** The current advertisement is a development format: a random ID with no
  cryptography. It is not the protection the finished protocol will give, and anyone scanning can see
  that a Pvot Finder phone is nearby.
- **Foreground only.** Advertising and scanning stop when the app leaves the screen.
- Signal strength is a rough sign of closeness, not a distance.
- Needs Android 13 or newer and Bluetooth LE advertising support.
- The app asks only for the Nearby devices permission (`BLUETOOTH_SCAN` with `neverForLocation`,
  `BLUETOOTH_ADVERTISE`) and has no internet permission.

## Building

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug, installs as org.prauga.finder.dev
./gradlew test                 # unit tests
reuse lint                     # licensing
```

## Licence

AGPL-3.0-or-later. Icons from [Tabler Icons](https://tabler.io/icons) (MIT); see
`THIRD_PARTY_NOTICES.md`.
