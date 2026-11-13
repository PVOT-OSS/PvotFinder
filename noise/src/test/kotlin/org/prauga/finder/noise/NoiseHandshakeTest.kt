// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.noise

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseHandshakeTest {
    private val prologue = "test".toByteArray()
    private val psk = ByteArray(32) { it.toByte() }
    private val a = SoftwareX25519.generate()
    private val b = SoftwareX25519.generate()

    private fun xxPsk0(initiatorPsk: ByteArray, responderPsk: ByteArray = psk) =
        NoiseHandshake("XXpsk0", true, prologue, a, psk = initiatorPsk) to
            NoiseHandshake("XXpsk0", false, prologue, b, psk = responderPsk)

    @Test
    fun `XXpsk0 completes and both sides learn the other's static key`() {
        val (i, r) = xxPsk0(psk)

        r.readMessage(i.writeMessage())
        assertArrayEquals("hello".toByteArray(), i.readMessage(r.writeMessage("hello".toByteArray())))
        r.readMessage(i.writeMessage())

        assertTrue(i.isFinished && r.isFinished)
        assertArrayEquals(b.publicKey, i.remoteStaticKey)
        assertArrayEquals(a.publicKey, r.remoteStaticKey)
        assertArrayEquals(i.handshakeHash, r.handshakeHash)
        val (ti, tr) = i.split() to r.split()
        assertArrayEquals("ping".toByteArray(), tr.decrypt(ti.encrypt("ping".toByteArray())))
        assertArrayEquals("pong".toByteArray(), ti.decrypt(tr.encrypt("pong".toByteArray())))
    }

    @Test
    fun `XXpsk0 rejects the first message under a different PSK`() {
        val (i, r) = xxPsk0(ByteArray(32))

        assertThrows(NoiseException::class.java) { r.readMessage(i.writeMessage()) }
    }

    @Test
    fun `XXpsk0 does not reveal the responder's static key without the PSK`() {
        val stranger = NoiseHandshake("XXpsk0", true, prologue, a, psk = ByteArray(32))
        val r = NoiseHandshake("XXpsk0", false, prologue, b, psk = psk)

        assertThrows(NoiseException::class.java) { r.readMessage(stranger.writeMessage()) }
        assertThrows(IllegalStateException::class.java) { r.writeMessage() }
    }

    @Test
    fun `KK fails when the initiator expects another responder key`() {
        val other = SoftwareX25519.generate()
        val i = NoiseHandshake("KK", true, prologue, a, remoteStaticKey = other.publicKey)
        val r = NoiseHandshake("KK", false, prologue, b, remoteStaticKey = a.publicKey)

        assertThrows(NoiseException::class.java) { r.readMessage(i.writeMessage()) }
    }

    @Test
    fun `different prologues do not complete`() {
        val i = NoiseHandshake("XXpsk0", true, "one".toByteArray(), a, psk = psk)
        val r = NoiseHandshake("XXpsk0", false, "two".toByteArray(), b, psk = psk)

        assertThrows(NoiseException::class.java) { r.readMessage(i.writeMessage()) }
    }

    @Test
    fun `tampered transport messages are rejected`() {
        val (i, r) = xxPsk0(psk)
        r.readMessage(i.writeMessage())
        i.readMessage(r.writeMessage())
        r.readMessage(i.writeMessage())
        val (ti, tr) = i.split() to r.split()

        val sealed = ti.encrypt("ring".toByteArray()).also { it[0] = (it[0] + 1).toByte() }

        assertThrows(NoiseException::class.java) { tr.decrypt(sealed) }
    }

    @Test
    fun `a pattern with a PSK needs one`() {
        assertThrows(NoiseException::class.java) { NoiseHandshake("XXpsk0", true, prologue, a) }
    }
}
