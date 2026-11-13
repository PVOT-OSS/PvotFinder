// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.finding

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prauga.finder.bluetooth.protocol.TemporaryId
import org.prauga.finder.noise.NoiseHandshake
import org.prauga.finder.noise.SoftwareX25519
import org.prauga.finder.pairing.MessageChannel

class FindingTest {
    private class Pipe(
        private val inbox: Channel<ByteArray>,
        private val outbox: Channel<ByteArray>,
        val sent: MutableList<ByteArray> = mutableListOf(),
    ) : MessageChannel {
        override suspend fun send(message: ByteArray) {
            sent += message
            outbox.send(message)
        }

        override suspend fun receive(): ByteArray = inbox.receive()
    }

    private val owner = SoftwareX25519.generate()
    private val found = SoftwareX25519.generate()
    private val other = SoftwareX25519.generate()
    private val id = TemporaryId(ByteArray(8) { 4 })
    private val oldId = TemporaryId(ByteArray(8) { 3 })
    private val peers =
        listOf(KnownPeer("other", other.publicKey), KnownPeer("owner", owner.publicKey))

    private fun pipes(): Pair<Pipe, Pipe> {
        val toServer = Channel<ByteArray>(Channel.UNLIMITED)
        val toClient = Channel<ByteArray>(Channel.UNLIMITED)
        return Pipe(toClient, toServer) to Pipe(toServer, toClient)
    }

    @Test
    fun `a paired phone verifies and rings`() = runTest {
        val (client, server) = pipes()
        val handled = mutableListOf<Pair<String, Command>>()
        val serving = async {
            runCatching {
                FindingServer(found).serve(server, peers, listOf(oldId, id)) { peer, command ->
                    handled += peer.deviceIdHex to command
                    Reply.OK
                }
            }
        }

        val session = FindingClient.open(client, owner, found.publicKey, id)
        assertEquals(Reply.OK, session.request(Command.Ping))
        assertEquals(Reply.OK, session.request(Command.Ring(30)))
        assertEquals(Reply.OK, session.request(Command.StopRing))

        assertEquals(
            listOf("owner" to Command.Ping, "owner" to Command.Ring(30), "owner" to Command.StopRing),
            handled,
        )
        serving.cancel()
    }

    @Test
    fun `an unpaired phone is refused before any command`() = runTest {
        val (client, server) = pipes()
        val stranger = SoftwareX25519.generate()
        val serving = async {
            runCatching { FindingServer(found).serve(server, peers, listOf(id)) { _, _ -> Reply.OK } }
        }

        client.send(
            NoiseHandshake(
                    "KK",
                    true,
                    FindingProtocol.prologue(id),
                    stranger,
                    found.publicKey,
                )
                .writeMessage()
        )

        assertTrue(serving.await().exceptionOrNull() is FindingException)
    }

    @Test
    fun `an identifier the phone never advertised is refused`() = runTest {
        val (client, server) = pipes()
        val serving = async {
            runCatching { FindingServer(found).serve(server, peers, listOf(oldId)) { _, _ -> Reply.OK } }
        }
        val opening = async { runCatching { FindingClient.open(client, owner, found.publicKey, id) } }

        assertTrue(serving.await().exceptionOrNull() is FindingException)
        opening.cancel()
    }

    @Test
    fun `a replayed session cannot issue commands`() = runTest {
        val (client, server) = pipes()
        val serving = async {
            runCatching { FindingServer(found).serve(server, peers, listOf(id)) { _, _ -> Reply.OK } }
        }
        FindingClient.open(client, owner, found.publicKey, id).request(Command.Ring(10))
        serving.cancel()
        val recorded = client.sent.toList()

        val (attacker, server2) = pipes()
        val rings = mutableListOf<Command>()
        val replayed = async {
            runCatching {
                FindingServer(found).serve(server2, peers, listOf(id)) { _, c ->
                    rings += c
                    Reply.OK
                }
            }
        }
        recorded.forEach { attacker.send(it) }

        assertTrue(replayed.await().exceptionOrNull() is FindingException)
        assertTrue(rings.isEmpty())
    }

    @Test
    fun `commands decode only in range`() {
        assertEquals(Command.Ring(60), Command.decode(Command.Ring(60).encode()))
        assertNull(Command.decode(byteArrayOf(2, 61)))
        assertNull(Command.decode(byteArrayOf(2, 0)))
        assertNull(Command.decode(byteArrayOf(9)))
        assertThrows(IllegalArgumentException::class.java) { Command.Ring(0) }
        assertEquals(Reply.UNKNOWN, Reply.decode(byteArrayOf(1, 2)))
    }
}
