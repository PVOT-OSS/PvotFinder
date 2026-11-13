// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.pairing

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

object PairingProtocol {
    /** GATT service and advertisement UUID for pairing; separate from the finding UUID. */
    val SERVICE_UUID: UUID = UUID.fromString("00939d3c-6922-47bf-aade-7c21f076841f")

    /** The one characteristic: the client writes, the server notifies. */
    val MESSAGE_CHARACTERISTIC_UUID: UUID = UUID.fromString("00939d3c-6922-47bf-aade-7c21f0760001")

    const val VERSION = 1
    const val NOISE_PATTERN = "XXpsk0"
    const val VALIDITY_SECONDS = 5 * 60L
    const val SESSION_ID_SIZE = 16
    const val PSK_SIZE = 32
    const val KEY_SIZE = 32
    const val ADVERT_TYPE: Byte = 0x50

    internal val PROLOGUE_LABEL = "PvotFinder pairing v1".toByteArray(Charsets.US_ASCII)
    private val ADVERT_LABEL = "PvotFinder pair v1".toByteArray(Charsets.US_ASCII)

    /** The advertisement payload that lets the scanning phone find the showing one. */
    fun advertisement(sessionId: ByteArray): ByteArray =
        byteArrayOf(VERSION.toByte(), ADVERT_TYPE) + sessionTag(sessionId)

    fun sessionTag(sessionId: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .run {
                update(ADVERT_LABEL)
                digest(sessionId)
            }
            .copyOf(8)
}

/** What the QR code carries: everything the scanning phone needs to pair with the showing one. */
class Invitation(
    sessionId: ByteArray,
    psk: ByteArray,
    staticPublicKey: ByteArray,
    val expiresAtSeconds: Long,
) {
    val sessionId: ByteArray = sessionId.copyOf()
    val psk: ByteArray = psk.copyOf()
    val staticPublicKey: ByteArray = staticPublicKey.copyOf()

    init {
        require(sessionId.size == PairingProtocol.SESSION_ID_SIZE)
        require(psk.size == PairingProtocol.PSK_SIZE)
        require(staticPublicKey.size == PairingProtocol.KEY_SIZE)
    }

    fun isExpired(nowSeconds: Long): Boolean = nowSeconds >= expiresAtSeconds

    fun encode(): String {
        val bytes =
            ByteBuffer.allocate(SIZE)
                .put(PairingProtocol.VERSION.toByte())
                .put(sessionId)
                .put(psk)
                .put(staticPublicKey)
                .putLong(expiresAtSeconds)
                .array()
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val PREFIX = "pvotfinder:1:"
        private const val SIZE =
            1 + PairingProtocol.SESSION_ID_SIZE + PairingProtocol.PSK_SIZE +
                PairingProtocol.KEY_SIZE + 8

        fun create(
            staticPublicKey: ByteArray,
            nowSeconds: Long,
            random: SecureRandom = SecureRandom(),
        ): Invitation =
            Invitation(
                sessionId = ByteArray(PairingProtocol.SESSION_ID_SIZE).also(random::nextBytes),
                psk = ByteArray(PairingProtocol.PSK_SIZE).also(random::nextBytes),
                staticPublicKey = staticPublicKey,
                expiresAtSeconds = nowSeconds + PairingProtocol.VALIDITY_SECONDS,
            )

        /** Returns null for anything that is not a well-formed, unexpired invitation. */
        fun decode(text: String, nowSeconds: Long): Invitation? {
            if (!text.startsWith(PREFIX)) return null
            val bytes =
                try {
                    Base64.getUrlDecoder().decode(text.removePrefix(PREFIX))
                } catch (e: IllegalArgumentException) {
                    return null
                }
            if (bytes.size != SIZE || bytes[0].toInt() != PairingProtocol.VERSION) return null
            val buffer = ByteBuffer.wrap(bytes, 1, SIZE - 1)
            val invitation =
                Invitation(
                    sessionId = ByteArray(PairingProtocol.SESSION_ID_SIZE).also(buffer::get),
                    psk = ByteArray(PairingProtocol.PSK_SIZE).also(buffer::get),
                    staticPublicKey = ByteArray(PairingProtocol.KEY_SIZE).also(buffer::get),
                    expiresAtSeconds = buffer.getLong(),
                )
            val tooFarAhead =
                invitation.expiresAtSeconds > nowSeconds + PairingProtocol.VALIDITY_SECONDS + 60
            return invitation.takeUnless { it.isExpired(nowSeconds) || tooFarAhead }
        }
    }
}
