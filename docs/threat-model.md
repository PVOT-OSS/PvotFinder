<!--
SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
SPDX-License-Identifier: AGPL-3.0-or-later
-->
# Pvot Finder threat model

**Status: draft for review**, for protocol version 1 ([protocol.md](protocol.md)). Covers pairing,
direct finding and the outline of relaying. Each threat lists what the design does and what remains.

## What we protect

1. **Where people are.** Neither the owner nor anyone near a Pvot Finder phone should become trackable
   by others through it.
2. **Ownership.** Only phones the owner paired can find, verify or ring a phone.
3. **Secrets.** Static keys `S`, beacon secrets `B`, pairing `psk`.
4. **The phone's behaviour.** Nothing reachable over Bluetooth does more than ring, visibly.

## Who we defend against

| | attacker | can |
|---|---|---|
| T1 | passive listener | receive advertisements anywhere, for a long time, with many receivers |
| T2 | active radio attacker | also send, replay, spoof, connect, relay |
| T3 | shoulder surfer | see or photograph a pairing QR code |
| T4 | holder of an owner's phone | lost or stolen phone, unlocked |
| T5 | other apps on the same phone | the usual Android app sandbox |
| T6 | stalker | uses Pvot Finder as designed to follow someone |
| T7 | relay or server operator (M4) | sees reports, runs the server |
| T8 | backups | cloud or device-to-device backup |

## Threats

### T1 passive listener
- *Following a phone over time.* Identifiers change every 1024 s and are HMAC outputs under a secret
  `B`, unlinkable without it. The advertising set restarts at each change, so the Bluetooth address
  changes with it (to verify on the devices).
  - **Remains:** within one period a phone is followable. A listener with continuous coverage can link
    periods by timing or signal strength (true of every BLE beacon design). "This is a Pvot Finder
    phone" is always visible from the service UUID.
- *Learning who owns it.* Advertisements carry no name, no static key, no device ID.
- *Version 0 (today).* The random ID is per advertising session and links to nothing. It only says
  "Pvot Finder here". The README marks it as test-only.

### T2 active radio attacker
- *Fake "nearby".* Replaying a recorded `eid_i` within its period makes an owner's phone show "maybe
  nearby". Only a completed `KK` handshake (§4.2) shows "verified", and that needs the static key.
  - **Remains:** the unverified hint can be faked. The UI labels it as such.
- *Pairing MITM.* Without the QR's `psk`, message 1 of `XXpsk0` fails. B pins A's static key from the
  QR.
- *Ring abuse.* Ring needs a `KK` session with a paired static key. Ephemeral keys make recorded
  sessions useless.
- *Denial of service* (jamming, connection flooding): not prevented. Connection attempts are rate
  limited so the phone's battery is not drained.
- *Malformed input.* Fixed-size parsing, length checks before allocation, reject unknown versions
  (already tested for version 0).

### T3 shoulder surfer
- A photographed QR, used within 5 minutes before B: the 6-digit confirmation shown on both screens
  differs, and the user doesn't confirm, so no beacon secret is shared.
  - **Remains:** a user who confirms without comparing. The UI asks them to compare.
- The QR expires after 5 minutes and is single-use. `FLAG_SECURE` keeps it out of screenshots and
  recents.

### T4 holder of an owner's phone
- They get the `B` of every peer (it must be usable without user presence) and can follow those phones
  in direct range, and later through relays.
  - **Mitigation:** "Reset identity" on each remaining phone, then pair again. An owner-group re-key is
    open (protocol §7).
  - **Remains:** until the owner resets, peers are followable. This is said plainly in the UI next to
    "Remove".
- `S` cannot be extracted (TEE, not exportable), but it can be *used* while they hold the phone.

### T5 other apps
- Keystore keys are bound to our app UID. `B` at rest is AES-GCM under a Keystore key. The database is
  in app-private storage.
- Exported components: none besides the launcher activity. The GATT service only answers Noise.

### T6 stalker
- *A hidden phone paired to the stalker's phone.* Direct finding only works in Bluetooth range, so the
  stalker has to be near anyway. The serious case is relaying (M4).
  - **Mitigation:** separated mode, detection, identify-and-ring (protocol §5), **required before the
    relay network opens**.
  - **Remains:** a phone hidden by someone the victim lives with may never count as separated. This
    is a known open problem of every finding network.
- *Ringing someone's phone to find it.* Only for paired phones.

### T7 relay or server operator (M4 sketch)
- Reports are HPKE-encrypted to `pk_i`. The server indexes by `SHA-256(pk_i)` and never learns
  locations or owners. Relays learn their own location and that a Pvot Finder phone was near.
  - **Remains:** fake reports, traffic analysis of owner queries (who asks for which hashes),
    retention. To be designed in M4.

### T8 backups
- `allowBackup="false"`, all extraction excluded. Keystore keys never leave the phone in any case.

## Out of scope

- A phone that is rooted, or whose TEE is broken.
- A phone that is off, out of battery, or force-stopped: it does not advertise. The README says so.
- Precise location from signal strength: RSSI is shown as a rough sign, never as a distance.
