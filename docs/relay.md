<!--
SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
SPDX-License-Identifier: AGPL-3.0-or-later
-->
# Finding beyond Bluetooth range (M4)

**Status: draft for review.** Extends [protocol.md](protocol.md) §5–§6 and
[threat-model.md](threat-model.md). Nothing here is implemented. No PVOT-run backend: reports travel
over public Nostr relays that anyone can run, and users can choose their own.

## 0. Order

| step | what | network |
|---|---|---|
| M4a | **Last seen by my phones**: a paired phone that hears another stores where and when | none |
| M4b | **Unwanted-tracking protection** (protocol §5): separated mode, detection, identify and ring | none |
| M4c | **Reports**: per-period public keys, extended advertising, encrypted sightings queued on relays | none yet |
| M4d | **Transport**: publish and fetch reports through Nostr relays | internet |
| M4e | **Owner view**: where a phone was reported, how sure, open in a map app | — |

M4b must ship before M4d is switched on for anyone: relaying is where a hidden phone becomes a
tracker.

## 1. M4a — Last seen by my phones

- When a phone hears a paired phone (a "maybe nearby" match, protocol §3) it records **its own**
  location, accuracy, time and signal strength for that peer: the latest record per peer, plus one per
  hour for the last 7 days.
- Stored on the phone only (Room, excluded from backups). Shown on the paired phone's page: "Last seen
  14:02, about 30 m from here", with **Open in map app** (a `geo:` intent; the app ships no maps).
- Needs a location permission on the looking phone. While Pvot Finder only looks in the foreground,
  "while using the app" is enough. `BLUETOOTH_SCAN` loses `neverForLocation`, because scan results now
  lead to a location. Android may then show scanning as location use; the permission text says why.
- No new keys, nothing leaves the phone.

## 2. M4b — Unwanted-tracking protection

As protocol §5, made concrete:

- **Separated:** no paired phone heard for 30 minutes. The phone sets flag bit 0 and switches to a
  separated identifier: `HMAC-SHA256(B, "PvotFinder sep v1" || be32(day))`, truncated to 8 bytes,
  with `day = floor(unix_seconds / 86400)`. Paired phones still recognise it, because they compute
  both kinds.
- **Detection** on every phone that scans: a separated identifier that this phone hears at three
  places at least 500 m apart, over at least 1 hour, raises a notification "An unknown Pvot Finder
  phone is moving with you". It offers ring and identify. This needs background scanning (M5) to be
  useful; until then it runs while the app is open.
- **Identify and ring for strangers:** a separated phone accepts, without any key, a GATT request
  that rings it for 10 s and shows "This phone is being located by its owner with Pvot Finder".
  Limited to once per 10 minutes.
- Phones that never pair are never separated and never relayed.

## 3. M4c — Reports

### 3.1 Keys

- Per period `i`: `seed_i = HMAC-SHA256(B, "PvotFinder key v1" || be32(i))`,
  `sk_i = HKDF-SHA256(salt = "", ikm = seed_i, info = "PvotFinder x25519", 32)` used as an X25519
  private key (clamped as X25519 does), `pk_i = X25519(sk_i, 9)`.
- Paired phones derive `sk_i` from the shared `B` and can decrypt. A relay only ever sees `pk_i`.
- Separated phones use `day` instead of `i`, with label `"PvotFinder sep key v1"`.

### 3.2 Advertisement

- `pk_i` is 32 bytes and does not fit the 10-byte legacy payload. A second advertising set, using
  **extended advertising** (both test Pixels support it, up to 1650 bytes), carries
  `version = 1 | flags | pk_i` under the same service UUID. Phones without extended advertising keep
  only the legacy set and cannot be relayed (shown in the app).
- Both sets restart together at each period, like protocol §2.2.
- Relays scan with `setLegacy(false)` and all supported PHYs.

### 3.3 Sighting report

[HPKE](https://www.rfc-editor.org/rfc/rfc9180) base mode, suite DHKEM(X25519, HKDF-SHA256) /
HKDF-SHA256 / ChaCha20-Poly1305 (RFC 9180 test vectors A.2), `info = "PvotFinder report v1"`,
recipient `pk_i`.

```
plaintext = version = 1 (1) | latitude × 1e7 (int32) | longitude × 1e7 (int32)
          | accuracy, metres (uint16) | observed, unix seconds (uint32) | RSSI (int8)
report    = enc (32) | ciphertext (16 + 16 tag)  = 64 bytes
lookup    = SHA-256("PvotFinder lookup v1" || pk_i)
```

- A relay that hears `pk_i` makes at most one report per `pk_i` per 10 minutes and queues it while
  offline, for up to 24 hours.
- Relaying is **off by default**. The user turns on "Help others find their phones", which explains:
  it uses the phone's location and the internet, and reveals nothing about the user to the owner except
  that a phone was near that spot.
- Relaying in the background needs background location and background scanning, so M4 relays only
  while the app is open, unless M5 finds acceptable ways.

## 4. M4d — Transport over Nostr

[Nostr](https://github.com/nostr-protocol/nips) relays are public WebSocket servers that store signed
JSON events. PVOT runs none; the app lists a few independent defaults and the user can change them.

- **Publish:** one event per report.
  - kind: a regular stored kind in 1000–9999, **to choose**, and ideally to register as a NIP;
  - signed by a fresh secp256k1 key for each report, so reports cannot be linked through it;
  - `content`: base64 of the report;
  - tags: a single-letter indexed tag (letter **to choose**, avoiding ones that common NIPs use) with
    the hex lookup hash, and `["expiration", now + 7 days]` (NIP-40);
  - optionally a proof of work (NIP-13), if relays ask for it against spam.
- **Fetch:** the owner's phone, when the app opens or when asked, subscribes on each relay to the
  lookup hashes of every paired phone for every period of the last 7 days. That is about 600 hashes
  per phone, sent in batches. Each report it can decrypt is stored locally.
- New permission: `INTERNET`. Network use is limited to these relays.
- Libraries:
  - a WebSocket client (OkHttp, Apache-2.0);
  - BIP-340 Schnorr signatures on secp256k1, either from a library or implemented on BouncyCastle's
    curve arithmetic and checked against the BIP-340 test vectors, the same way as Noise. **Open:**
    prebuilt native libraries may be a problem for F-Droid.

**Why not the BitTorrent DHT:** BEP 44 stores values only under keys derived from the value itself or
from the writer's own signing key. So a relay cannot store a report under a key the owner can compute.
Phone-to-phone carrying over Bluetooth with no internet stays a possible later transport; it needs many
users to be useful.

## 5. M4e — Owner view

- The paired phone's page lists reports newest first: time, accuracy, **Open in map app**.
- A location reported by a single relay is marked "1 report". Two or more independent reports within
  200 m and 30 minutes are marked "confirmed" (protects against fake reports, threat model T7).
- "Last seen by my phones" (M4a) and relay reports are shown separately.

## 6. What this does not protect

- Relay operators see the IP addresses of relaying phones and of owners fetching. Over time an operator
  can link an owner's IP to the set of lookup hashes they ask for, and see when reports arrive for them.
  It cannot read locations or tell which phone a hash belongs to. Mitigations: several relays, batching,
  optional proxy/Tor (**open**).
- Anyone who hears `pk_i` can post a fake location for it (the "1 report" / "confirmed" labels above).
- No PVOT-side rate limiting exists. Spam control depends on the relays and on proof of work.
- Relays may drop events before they expire, so delivery is best effort.

## 7. Open questions for review

1. Nostr kind number and tag letter; propose a NIP?
2. Default relay list: which, how many?
3. secp256k1/BIP-340: a library (which one passes F-Droid) or our own on BouncyCastle?
4. Relaying in the foreground only for M4, background later (M5)?
5. Is M4a ("last seen", needs a location permission) wanted before the rest?
