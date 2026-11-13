// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.pairing

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import org.prauga.finder.noise.Dh25519
import org.prauga.finder.noise.NoiseException
import org.prauga.finder.noise.NoiseHandshake
import org.prauga.finder.noise.NoiseTransport

/** A reliable, ordered message pipe between the two phones, such as GATT with [Framing]. */
interface MessageChannel {
    suspend fun send(message: ByteArray)

    suspend fun receive(): ByteArray
}

/** What the user compares on both screens before the phones exchange secrets. */
data class PairingCheck(val peer: PeerInfo, val code: String)

class PairedPeer(val info: PeerInfo, staticPublicKey: ByteArray, beaconSecret: ByteArray) {
    val staticPublicKey: ByteArray = staticPublicKey.copyOf()
    val beaconSecret: ByteArray = beaconSecret.copyOf()
}

enum class PairingFailure {
    /** The handshake failed: wrong or expired code, or a corrupted message. */
    HANDSHAKE,

    /** The phone answering is not the one whose code was scanned. */
    WRONG_PHONE,

    /** The user here cancelled or did not confirm. */
    CANCELLED_HERE,

    /** The user on the other phone cancelled or did not confirm. */
    CANCELLED_THERE,

    /** The other phone sent something unexpected. */
    PROTOCOL,
}

class PairingFailedException(val reason: PairingFailure, cause: Throwable? = null) :
    Exception(reason.name, cause)

/**
 * One pairing between the phone showing the QR code (responder) and the phone that scanned it
 * (initiator): `Noise_XXpsk0`, then both users confirm the same code, then each sends its beacon
 * secret.
 */
class PairingSession
private constructor(
    private val initiator: Boolean,
    private val invitation: Invitation,
    private val staticKey: Dh25519,
    private val self: PeerInfo,
    beaconSecret: ByteArray,
    private val channel: MessageChannel,
) {
    private val beaconSecret = beaconSecret.copyOf()

    init {
        require(beaconSecret.size == TransportMessage.SECRET_SIZE)
    }

    suspend fun run(confirm: suspend (PairingCheck) -> Boolean): PairedPeer {
        val handshake =
            NoiseHandshake(
                PairingProtocol.NOISE_PATTERN,
                initiator,
                PairingProtocol.PROLOGUE_LABEL + invitation.sessionId,
                staticKey,
                psk = invitation.psk,
            )
        val peer = handshake(handshake)
        val transport = handshake.split()
        val check = PairingCheck(peer, confirmationCode(handshake.handshakeHash))
        confirm(transport, check, confirm)
        send(transport, TransportMessage.Share(beaconSecret))
        val share =
            receive(transport) as? TransportMessage.Share
                ?: throw PairingFailedException(PairingFailure.PROTOCOL)
        return PairedPeer(peer, handshake.remoteStaticKey!!, share.beaconSecret)
    }

    private suspend fun handshake(handshake: NoiseHandshake): PeerInfo =
        try {
            if (initiator) {
                channel.send(handshake.writeMessage())
                val peer = PeerInfo.decode(handshake.readMessage(channel.receive()))
                val responderKey = handshake.remoteStaticKey
                if (!MessageDigest.isEqual(responderKey, invitation.staticPublicKey)) {
                    throw PairingFailedException(PairingFailure.WRONG_PHONE)
                }
                channel.send(handshake.writeMessage(self.encode()))
                peer
            } else {
                handshake.readMessage(channel.receive())
                channel.send(handshake.writeMessage(self.encode()))
                PeerInfo.decode(handshake.readMessage(channel.receive()))
            }
        } catch (e: NoiseException) {
            throw PairingFailedException(PairingFailure.HANDSHAKE, e)
        } catch (e: PairingException) {
            throw PairingFailedException(PairingFailure.PROTOCOL, e)
        }

    private suspend fun confirm(
        transport: NoiseTransport,
        check: PairingCheck,
        ask: suspend (PairingCheck) -> Boolean,
    ) = coroutineScope {
        val remote = async { receive(transport) }
        val local = async { ask(check) }
        val first = select { remote.onAwait { it }; local.onAwait { it } }
        val localOk: Boolean
        val remoteMessage: TransportMessage
        if (first is TransportMessage) {
            remoteMessage = first
            if (remoteMessage != TransportMessage.Confirm) {
                local.cancel()
                throw PairingFailedException(PairingFailure.CANCELLED_THERE)
            }
            localOk = local.await()
        } else {
            localOk = first as Boolean
            if (!localOk) remote.cancel()
            remoteMessage = if (localOk) TransportMessage.Confirm else TransportMessage.Abort
        }
        if (!localOk) {
            runCatching { send(transport, TransportMessage.Abort) }
            throw PairingFailedException(PairingFailure.CANCELLED_HERE)
        }
        send(transport, TransportMessage.Confirm)
        val answer = if (first is TransportMessage) remoteMessage else remote.await()
        if (answer != TransportMessage.Confirm) {
            throw PairingFailedException(PairingFailure.CANCELLED_THERE)
        }
    }

    private suspend fun send(transport: NoiseTransport, message: TransportMessage) {
        channel.send(transport.encrypt(message.encode()))
    }

    private suspend fun receive(transport: NoiseTransport): TransportMessage =
        try {
            TransportMessage.decode(transport.decrypt(channel.receive()))
        } catch (e: NoiseException) {
            throw PairingFailedException(PairingFailure.PROTOCOL, e)
        } catch (e: PairingException) {
            throw PairingFailedException(PairingFailure.PROTOCOL, e)
        }

    companion object {
        private val CONFIRM_INFO = "PvotFinder confirm v1".toByteArray(Charsets.US_ASCII)

        fun initiator(
            invitation: Invitation,
            staticKey: Dh25519,
            self: PeerInfo,
            beaconSecret: ByteArray,
            channel: MessageChannel,
        ) = PairingSession(true, invitation, staticKey, self, beaconSecret, channel)

        fun responder(
            invitation: Invitation,
            staticKey: Dh25519,
            self: PeerInfo,
            beaconSecret: ByteArray,
            channel: MessageChannel,
        ) = PairingSession(false, invitation, staticKey, self, beaconSecret, channel)

        /** Six digits from the handshake hash: HKDF-SHA256 with an empty salt, first four bytes. */
        fun confirmationCode(handshakeHash: ByteArray): String {
            val prk = hmac(ByteArray(32), handshakeHash)
            val okm = hmac(prk, CONFIRM_INFO + byteArrayOf(1))
            val value =
                ((okm[0].toLong() and 0xFF) shl 24) or
                    ((okm[1].toLong() and 0xFF) shl 16) or
                    ((okm[2].toLong() and 0xFF) shl 8) or
                    (okm[3].toLong() and 0xFF)
            return "%06d".format(value % 1_000_000)
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }
    }
}
