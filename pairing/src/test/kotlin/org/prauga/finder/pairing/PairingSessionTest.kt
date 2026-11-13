// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.pairing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prauga.finder.noise.SoftwareX25519

class PairingSessionTest {
    private class Pipe(private val inbox: Channel<ByteArray>, private val outbox: Channel<ByteArray>) :
        MessageChannel {
        override suspend fun send(message: ByteArray) = outbox.send(message)

        override suspend fun receive(): ByteArray = inbox.receive()
    }

    private val showingKey = SoftwareX25519.generate()
    private val scanningKey = SoftwareX25519.generate()
    private val showing = PeerInfo(ByteArray(16) { 1 }, "Pixel 10", "0.1.0")
    private val scanning = PeerInfo(ByteArray(16) { 2 }, "Pixel 9", "0.1.0")
    private val showingSecret = ByteArray(32) { 3 }
    private val scanningSecret = ByteArray(32) { 4 }
    private val invitation = Invitation.create(showingKey.publicKey, nowSeconds = 1_000)

    private fun pipes(): Pair<MessageChannel, MessageChannel> {
        val toShowing = Channel<ByteArray>(Channel.UNLIMITED)
        val toScanning = Channel<ByteArray>(Channel.UNLIMITED)
        return Pipe(toShowing, toScanning) to Pipe(toScanning, toShowing)
    }

    private suspend fun failure(block: suspend () -> Unit): PairingFailure? =
        try {
            block()
            null
        } catch (e: PairingFailedException) {
            e.reason
        }

    @Test
    fun `both phones confirm the same code and get each other's secret and key`() = runTest {
        val (showingPipe, scanningPipe) = pipes()
        val codes = mutableListOf<PairingCheck>()
        val responder =
            PairingSession.responder(invitation, showingKey, showing, showingSecret, showingPipe)
        val initiator =
            PairingSession.initiator(invitation, scanningKey, scanning, scanningSecret, scanningPipe)

        val shown = async { responder.run { codes += it; true } }
        val scanned = initiator.run { codes += it; true }
        val atShowing = shown.await()

        assertEquals(scanning, atShowing.info)
        assertEquals(showing, scanned.info)
        assertArrayEquals(scanningSecret, atShowing.beaconSecret)
        assertArrayEquals(showingSecret, scanned.beaconSecret)
        assertArrayEquals(scanningKey.publicKey, atShowing.staticPublicKey)
        assertArrayEquals(showingKey.publicKey, scanned.staticPublicKey)
        assertEquals(2, codes.size)
        assertEquals(codes[0].code, codes[1].code)
        assertTrue(codes[0].code.matches(Regex("\\d{6}")))
    }

    @Test
    fun `a wrong PSK fails the handshake on the showing phone`() = runTest {
        val (showingPipe, scanningPipe) = pipes()
        val forged =
            Invitation(invitation.sessionId, ByteArray(32), invitation.staticPublicKey, 2_000)
        val responder =
            PairingSession.responder(invitation, showingKey, showing, showingSecret, showingPipe)
        val initiator =
            PairingSession.initiator(forged, scanningKey, scanning, scanningSecret, scanningPipe)

        val scanned = async { failure { initiator.run { true } } }

        assertEquals(PairingFailure.HANDSHAKE, failure { responder.run { true } })
        scanned.cancel()
    }

    @Test
    fun `the scanning phone refuses a responder with another static key`() = runTest {
        val (showingPipe, scanningPipe) = pipes()
        val impostor = SoftwareX25519.generate()
        val responder =
            PairingSession.responder(invitation, impostor, showing, showingSecret, showingPipe)
        val initiator =
            PairingSession.initiator(invitation, scanningKey, scanning, scanningSecret, scanningPipe)

        val shown = async { failure { responder.run { true } } }

        assertEquals(PairingFailure.WRONG_PHONE, failure { initiator.run { true } })
        shown.cancel()
    }

    @Test
    fun `cancelling on one phone fails both and shares nothing`() = runTest {
        val (showingPipe, scanningPipe) = pipes()
        val responder =
            PairingSession.responder(invitation, showingKey, showing, showingSecret, showingPipe)
        val initiator =
            PairingSession.initiator(invitation, scanningKey, scanning, scanningSecret, scanningPipe)
        val neverAnswered = CompletableDeferred<Boolean>()

        val shown = async { failure { responder.run { neverAnswered.await() } } }
        val scanned = failure { initiator.run { false } }

        assertEquals(PairingFailure.CANCELLED_HERE, scanned)
        assertEquals(PairingFailure.CANCELLED_THERE, shown.await())
    }

    @Test
    fun `the confirmation code is stable for a hash`() {
        val code = PairingSession.confirmationCode(ByteArray(32))

        assertEquals(code, PairingSession.confirmationCode(ByteArray(32)))
        assertTrue(code != PairingSession.confirmationCode(ByteArray(32) { 1 }))
    }
}
