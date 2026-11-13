<!--
SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
SPDX-License-Identifier: AGPL-3.0-or-later
-->
# Pvot Finder protocol, version 1

**Status: draft for review.** Nothing here is implemented yet except the version 0 advertisement in
§2.1. Read together with [threat-model.md](threat-model.md).

## 0. Goals and rules

- Phones that their owner paired can find each other over Bluetooth LE, with no network and no
  server.
- Anyone else who hears a phone cannot tell it is the same phone from one ~17 minute period to the
  next, and learns nothing about its owner.
- Only standard constructions: [Noise](https://noiseprotocol.org/noise.html) handshakes, HMAC-SHA256,
  HKDF (RFC 5869), ChaCha20-Poly1305, X25519, and later HPKE (RFC 9180). Nothing home-made beyond
  choosing labels and byte layouts.
- Private keys stay in the Android Keystore wherever the platform allows it.

Byte order is big-endian. `||` is concatenation. `Trunc_n(x)` is the first `n` bytes of `x`. Labels are
ASCII without a terminator.

## 1. Keys on each phone

| key | what | where | used for |
|---|---|---|---|
| `S` static key | X25519 key pair | AndroidKeyStore, TEE, `PURPOSE_AGREE_KEY`, not exportable | Noise handshakes (pairing, finding) |
| `B` beacon secret | 32 random bytes | app storage, encrypted under `W` | rotating identifiers (§3), later relay keys (§6) |
| `W` storage key | AES-256-GCM | AndroidKeyStore, TEE, not exportable | encrypting `B` and the peers' `B` at rest |
| device ID | 16 random bytes | app storage | naming a phone to its peers; never broadcast |

- Checked on the Pixel 9 and Pixel 10 (Android 17): Keystore X25519 agreement and Ed25519 signing
  work in the TEE; StrongBox refuses X25519, so StrongBox is not used.
- `B` has to leave the phone (it is shared with paired phones), so it cannot be a Keystore key. It is
  decrypted only in memory, when needed.
- No user authentication is bound to `S` or `W`: the phone must answer while locked and, later, in
  the background.
- None of these are backed up: `allowBackup="false"` and data extraction rules exclude everything.
  Keystore keys never leave the phone, so a restored copy would be useless anyway.
- Ed25519 is not used in version 1. It stays available should relay reports (§6) need signatures.

## 2. Advertisements

All advertisements are Bluetooth LE service data under the Pvot Finder service UUID
`b656c287-0065-424d-932f-3d3a412b8331` (128-bit, development; a 16-bit UUID from the Bluetooth SIG
would save 14 bytes, **open**). Legacy advertising, 31 bytes: flags (3) + service data header (2 + 16)
leave **10 bytes** of payload. No device name, no TX power.

### 2.1 Version 0 (current, development only)

`version = 0 (1) | flags = 0 (1) | random ID (8)`, the ID new each time advertising starts. Not linked
to any key. Removed once version 1 ships.

### 2.2 Version 1 (finding, M3)

```
version = 1 (1) | flags (1) | eid_i (8)
```

- `flags` bit 0 **separated** (§5), bits 1–7 reserved, sent as 0, ignored on receipt.
- `eid_i` is the identifier for period `i` (§3).
- Connectable, so an owner's phone can connect for §4.
- At every period change the advertising set is **stopped and started again**, so that Android gives
  it a new random address at the same moment the identifier changes. Otherwise the unchanged address
  links the old and new identifiers. *To check on the devices in M3: the address really changes.*

### 2.3 Pairing advertisement (M2)

Only while the pairing screen shows a QR code (§4.1):

```
version = 1 (1) | type = 0x50 'P' (1) | Trunc_8(SHA-256("PvotFinder pair v1" || session_id)) (8)
```

Under a separate pairing service UUID (to be generated), connectable.

## 3. Rotating identifiers

- Period length **1024 s** (~17 min). `i = floor(unix_seconds / 1024)`, as a 32-bit integer.
- `eid_i = Trunc_8(HMAC-SHA256(B, "PvotFinder eid v1" || be32(i)))`
- A phone advertising uses its own `B`. A phone looking for its peers computes `eid_j` for every paired
  peer and `j in i-2 .. i+2`, which tolerates about ±34 minutes of clock difference, and matches
  received identifiers against that table.
- 64-bit identifiers: accidental collisions between peers are negligible. A false match only shows
  "maybe nearby" until §4 confirms it.
- Separate labels per use, so later derivations from `B` (§6) cannot be confused with these.

## 4. Handshakes

Both handshakes run over a GATT connection with one Pvot Finder service and one characteristic,
written by the client and notified by the server. Messages are framed as `be16(length) || message`
and split to the negotiated MTU (request 247). Noise messages are at most 65535 bytes; ours are a few
hundred.

The static key `S` lives in the Keystore. Noise's `DH(s, re)` is a Keystore `KeyAgreement`; the
ephemeral keys are software X25519 keys that never outlive the session.

### 4.1 Pairing: `Noise_XXpsk0_25519_ChaChaPoly_SHA256`

1. Phone **A** (showing) creates `session_id` (16 random bytes) and `psk` (32 random bytes) and shows
   a QR code:

   ```
   pvotfinder:1:<base64url, no padding>(version = 1 (1) | session_id (16) | psk (32)
                                          | A's static public key (32) | expires, unix seconds (8))
   ```

   132 characters. Valid for **5 minutes**, for **one** completed pairing; three failed handshakes
   also end it. The screen sets `FLAG_SECURE`, so the code stays out of screenshots and recent apps.
2. A advertises §2.3 and runs the GATT server. Phone **B** (scanning) reads the QR with its camera,
   finds the advertisement whose hash matches `session_id`, connects and starts the handshake as
   initiator with prologue `"PvotFinder pairing v1" || session_id`.
3. `psk0` mixes the QR's secret in before anything is sent. Someone who has not seen the QR cannot
   complete message 1, and learns no static key from trying.
4. B checks that A's static key in the handshake equals the one in the QR, and aborts otherwise.
5. Handshake payloads carry `PeerInfo` (device ID, user-visible name, app version). No secrets.
6. **Confirmation.** Both screens show the other phone's name and a 6-digit code,
   `be32(Trunc_4(HKDF-SHA256(salt = "", ikm = h, info = "PvotFinder confirm v1"))) mod 1000000`, where
   `h` is the final handshake hash. The user checks the codes match and taps Confirm on both phones.
   Each sends `Confirm` as a Noise transport message. This catches someone who raced B with a
   photographed QR.
7. Only after both confirmations does each phone send `Share { beacon_secret B }`. Each stores the
   other as a peer: device ID, name, static public key, `B` (encrypted under `W`), time paired.
8. The session ends. A forgets `session_id` and `psk`.

Pairing is **pairwise**. Pairing A–B and A–C does not make B and C peers (**open**: owner groups,
§7).

### 4.2 Finding a paired phone: `Noise_KK_25519_ChaChaPoly_SHA256` (M3)

When phone O matches a peer's `eid_i`, it can connect and run `KK` as initiator. Both statics are
known from pairing. Prologue: `"PvotFinder find v1" || eid_i`.

- `KK`'s first message does not say who is asking, and saying so in clear would let listeners link
  sessions. The found phone therefore tries each paired phone's static key with each identifier it
  advertised in the last periods (`i-1 .. i+1`) until message 1 decrypts. With a handful of paired
  phones this costs a few dozen X25519 operations.
- A completed handshake proves the phone holds that peer's static key: the UI goes from "maybe
  nearby" (advertisement only) to "verified nearby".
- Transport messages, one byte of type then arguments, each answered with one byte (`OK`,
  `REFUSED`, `UNKNOWN`): `Ping` (proves the session, nothing visible); `Ring { seconds, 1..60 }` →
  the found phone plays a loud sound with a dismissable notification saying which of the owner's
  phones asked; `StopRing`. `Unpair` (§7) is not implemented yet.
- The finding GATT service uses the advertisement's service UUID, with characteristic
  `ef23f22f-df24-447f-95b0-ed4c15a5a9e2`. Advertisements are connectable from version 1.
- Each session has new ephemeral keys, so recorded sessions cannot be replayed.
- No microphone, camera, location or file access is reachable through this channel.

## 5. Unwanted tracking

A phone with Pvot Finder hidden in someone's bag is a tracker. The design follows the IETF
[DULT](https://datatracker.ietf.org/wg/dult/about/) approach:

- **Separated mode.** A phone that has not heard any of its peers for 30 minutes sets flag bit 0 and
  rotates its identifier every **24 hours** instead of every period. Without that, nobody else can
  notice the same device following them.
- **Detection.** Pvot Finder phones that scan notice a separated device that stays near them across
  several places over hours, and warn their user. This needs location on the detecting phone, so it
  arrives with the relay work (M4), and **must ship before the relay network opens**.
- **Identify and ring.** A non-owner near a separated device can connect and ask it to ring and to
  show that it is a Pvot Finder phone, without any key. This is rate-limited.
- The M2 code only reserves the flag bit.

## 6. Relaying through other phones (M4 sketch, not for implementation)

Superseded in detail by [relay.md](relay.md) (draft), which fixes the key derivation, the report
format and the transport (Nostr relays, no PVOT backend).

- The lost phone also advertises, as **extended** advertising (both test Pixels support it, up to 1650
  bytes), a per-period X25519 public key `pk_i`. The private key `sk_i` is derived by HKDF from
  `HMAC-SHA256(B, "PvotFinder key v1" || be32(i))`, so peers can derive `sk_i` too.
- A relay that hears `pk_i` encrypts its own location to it with **HPKE** (RFC 9180, DHKEM(X25519,
  HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305) and uploads `(SHA-256(pk_i), ciphertext, time)`.
- Owners query by `SHA-256(pk_j)` and decrypt with `sk_j`. The server sees neither locations nor
  identities.
- Open for M4: bogus reports, since anyone who hears `pk_i` can encrypt a fake location to it; phones
  that only support legacy advertising; retention; relay rate limits; the server's API.

## 7. Removing, losing and resetting

- **Remove a peer**: deletes its record and its `B` here. Best effort: send `Unpair` over §4.2 at the
  next contact, so the other phone removes this one too.
- **Lost or stolen phone**: whoever holds it unlocked has the `B` of every peer and can follow them.
  **Reset identity** on each remaining phone creates a new `S` and `B`. Its peers then need to pair
  again, because there is no secure way to hand them the new `B` without contact. **Open**: owner
  groups that can re-key without re-pairing.
- **Reset this phone** wipes `S`, `W`, `B` and all peers.

## 8. Implementation notes for M2

- Noise is implemented from the specification (`CipherState`, `SymmetricState`, `HandshakeState`,
  patterns `XXpsk0` and `KK` only) as a plain Kotlin module, tested against published Noise test
  vectors. **Open**: an existing library instead. The candidates are old and unmaintained.
- QR codes: ZXing `core` (Apache-2.0) to draw and to decode; frames from CameraX. No Google Play
  services.
- Peers in Room. Secrets as AES-GCM ciphertext under `W`, with the record's device ID as associated
  data.
- New permissions: `BLUETOOTH_CONNECT` (GATT), `CAMERA` (QR, only on the phone that scans).
- Logs and diagnostics never contain `S`, `B`, `psk`, the QR contents or Noise keys. Identifiers
  (`eid`) only in the in-app diagnostics, as today.
