<!--
SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Privacy Policy — Pvot Finder

_Effective 18 November 2026_

Pvot Finder (package `org.prauga.finder`) is an app for finding your own phones, published by Saalim
Quadri for PVOT-OSS. This policy explains what the app does with your data.

**In short: there are no accounts, no ads, no analytics and no tracking, and the app never sends your
data to us. Your phones talk to each other directly over Bluetooth. Only if you turn on online
reports does a phone send its location over the internet, and then it is locked so that only your
own paired phones can read it.**

## Your phones talking over Bluetooth

- A phone with **Your phones can find this one** turned on sends a Bluetooth signal with an
  identifier that changes about every 17 minutes. Only phones you paired with it can recognise the
  identifier. Anyone nearby can see that a Pvot Finder phone is there, but not whose it is, and they
  cannot follow it from one identifier to the next.
- **Pairing** happens by showing a QR code on one phone and scanning it with the other. The phones
  then exchange keys over Bluetooth, and you check that both show the same 6-digit code.
- **Check it is really there** and **Play sound** connect over Bluetooth to a paired phone, which
  must prove with its key that it is yours. A phone only rings with a notification that can stop it.
- None of this uses the internet or any server.

## Online reports (optional, off unless you turn them on)

If you turn on **Report this phone's location online** and add at least one notice board:

- About every 30 minutes, also while the app is closed, the phone gets its own location and sends it,
  with the time and its battery level, to the notice boards you chose. The report is encrypted so that
  only phones holding this phone's secret, which is only the phone itself and the phones you paired
  with it, can read it or make one.
- Notice boards are public [Nostr](https://github.com/nostr-protocol/nostr) relays run by other people,
  not by us. You choose them; the app suggests some but adds none on its own. Each report is signed
  with a new random key, carries a lookup label that changes about every 17 minutes, and asks the
  board to delete it after 7 days.
- A board receives your internet (IP) address, the time of each report and the encrypted report. It
  cannot read the location or tell which phone or person the report belongs to. Over time a board
  could notice regular reports coming from the same IP address. Boards follow their own policies and
  may keep data longer than asked.
- When you open the app or look at one of your phones, it asks the boards for your paired phones'
  reports from the last 7 days, which reveals your IP address and the lookup labels to those boards.
  Without any boards chosen, it contacts nothing.

## Your location

The app asks for location only after explaining what it is for, and you can say no. Without it,
everything works except remembering places.

- **Last seen:** when your phone hears one of your paired phones, it remembers where it was and when.
  This stays on that phone.
- **Online reports:** described above, and only if you turn them on.

## What the app keeps on your device

- Its own keys, kept in the Android Keystore, and the secret shared with your paired phones, stored
  encrypted with a Keystore key.
- Your paired phones: the name each phone chose, its public key and its shared secret (encrypted).
- Last seen places and received reports, for 7 days.
- Your settings, including the notice boards you chose.

None of this is backed up to the cloud or copied to a new phone; the app turns Android backups off.
**Reset Pvot Finder** in Settings deletes the keys and paired phones. Uninstalling the app deletes
everything.

## Permissions

| Permission | Why |
|---|---|
| Nearby devices (Bluetooth scan, advertise, connect) | Finding, pairing with and ringing your phones |
| Camera | Scanning a pairing QR code, only on that screen |
| Location (optional) | Last seen places and online reports |
| Notifications | Showing that the phone is findable, and the notification that stops ringing |
| Foreground service (connected device, location) | Staying findable, and sending reports, while the app is closed |
| Internet | Online reports and reading them, only with notice boards you chose |

## Children

The app is not directed at children and does not knowingly collect data from them.

## Changes

If this policy changes, the new version will be published in the app's repository with a new date.

## Contact

Questions about this policy: danascape@gmail.com, or open an issue at
https://github.com/PVOT-OSS/PvotSeek/issues.
