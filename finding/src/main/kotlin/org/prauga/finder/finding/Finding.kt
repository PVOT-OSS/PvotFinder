// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.finding

import java.util.UUID
import org.prauga.finder.bluetooth.protocol.FinderProtocol
import org.prauga.finder.bluetooth.protocol.TemporaryId
import org.prauga.finder.noise.Dh25519
import org.prauga.finder.noise.NoiseException
import org.prauga.finder.noise.NoiseHandshake
import org.prauga.finder.noise.NoiseTransport
import org.prauga.finder.pairing.MessageChannel

object FindingProtocol {
    /** The finding GATT service lives under the advertisement's own service UUID. */
    val SERVICE_UUID: UUID = FinderProtocol.SERVICE_UUID

    val MESSAGE_CHARACTERISTIC_UUID: UUID = UUID.fromString("ef23f22f-df24-447f-95b0-ed4c15a5a9e2")

    const val NOISE_PATTERN = "KK"
    const val MAX_RING_SECONDS = 60

    private val PROLOGUE_LABEL = "PvotFinder find v1".toByteArray(Charsets.US_ASCII)

    internal fun prologue(advertisedId: TemporaryId): ByteArray =
        PROLOGUE_LABEL + advertisedId.toByteArray()
}

/** What a paired phone can ask of the phone it found. */
sealed interface Command {
    /** Proves the session works; the found phone does nothing visible. */
    data object Ping : Command

    data class Ring(val seconds: Int) : Command {
        init {
            require(seconds in 1..FindingProtocol.MAX_RING_SECONDS)
        }
    }

    data object StopRing : Command

    fun encode(): ByteArray =
        when (this) {
            Ping -> byteArrayOf(PING)
            is Ring -> byteArrayOf(RING, seconds.toByte())
            StopRing -> byteArrayOf(STOP)
        }

    companion object {
        private const val PING: Byte = 1
        private const val RING: Byte = 2
        private const val STOP: Byte = 3

        fun decode(bytes: ByteArray): Command? =
            when {
                bytes.size == 1 && bytes[0] == PING -> Ping
                bytes.size == 1 && bytes[0] == STOP -> StopRing
                bytes.size == 2 &&
                    bytes[0] == RING &&
                    bytes[1].toInt() in 1..FindingProtocol.MAX_RING_SECONDS ->
                    Ring(bytes[1].toInt())
                else -> null
            }
    }
}

enum class Reply(internal val code: Byte) {
    OK(0x10),
    REFUSED(0x11),
    UNKNOWN(0x12);

    companion object {
        fun decode(bytes: ByteArray): Reply =
            entries.firstOrNull { bytes.size == 1 && bytes[0] == it.code } ?: UNKNOWN
    }
}

class FindingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The looking phone's side of a session with one paired phone. */
class FindingClient
private constructor(private val channel: MessageChannel, private val transport: NoiseTransport) {
    suspend fun request(command: Command): Reply {
        channel.send(transport.encrypt(command.encode()))
        return try {
            Reply.decode(transport.decrypt(channel.receive()))
        } catch (e: NoiseException) {
            throw FindingException("Bad reply", e)
        }
    }

    companion object {
        /** Runs `KK` with the phone that advertised [advertisedId]; throws when it is not that peer. */
        suspend fun open(
            channel: MessageChannel,
            staticKey: Dh25519,
            peerStaticKey: ByteArray,
            advertisedId: TemporaryId,
        ): FindingClient {
            val handshake =
                NoiseHandshake(
                    FindingProtocol.NOISE_PATTERN,
                    initiator = true,
                    prologue = FindingProtocol.prologue(advertisedId),
                    staticKey = staticKey,
                    remoteStaticKey = peerStaticKey,
                )
            try {
                channel.send(handshake.writeMessage())
                handshake.readMessage(channel.receive())
            } catch (e: NoiseException) {
                throw FindingException("Handshake failed", e)
            }
            return FindingClient(channel, handshake.split())
        }
    }
}

/** A paired phone's static key, by its device ID. */
data class KnownPeer(val deviceIdHex: String, val staticPublicKey: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is KnownPeer &&
            deviceIdHex == other.deviceIdHex &&
            staticPublicKey.contentEquals(other.staticPublicKey)

    override fun hashCode(): Int = 31 * deviceIdHex.hashCode() + staticPublicKey.contentHashCode()
}

/**
 * The found phone's side. The first `KK` message does not say who is asking, so each paired phone
 * is tried with each identifier this phone may have advertised recently.
 */
class FindingServer(private val staticKey: Dh25519) {
    /**
     * Answers every command through [handle] until [channel] fails; throws [FindingException] when
     * the other side is not a paired phone.
     */
    suspend fun serve(
        channel: MessageChannel,
        peers: List<KnownPeer>,
        recentIds: List<TemporaryId>,
        handle: suspend (peer: KnownPeer, command: Command) -> Reply,
    ): Nothing {
        val first = channel.receive()
        var found: Pair<KnownPeer, NoiseHandshake>? = null
        search@ for (peer in peers) {
            for (id in recentIds) {
                val handshake =
                    NoiseHandshake(
                        FindingProtocol.NOISE_PATTERN,
                        initiator = false,
                        prologue = FindingProtocol.prologue(id),
                        staticKey = staticKey,
                        remoteStaticKey = peer.staticPublicKey,
                    )
                try {
                    handshake.readMessage(first)
                    found = peer to handshake
                    break@search
                } catch (e: NoiseException) {
                    continue
                }
            }
        }
        val (peer, handshake) = found ?: throw FindingException("Not a paired phone")
        channel.send(handshake.writeMessage())
        val transport = handshake.split()
        while (true) {
            val message =
                try {
                    transport.decrypt(channel.receive())
                } catch (e: NoiseException) {
                    throw FindingException("Bad command", e)
                }
            val reply = Command.decode(message)?.let { handle(peer, it) } ?: Reply.UNKNOWN
            channel.send(transport.encrypt(byteArrayOf(reply.code)))
        }
    }
}
