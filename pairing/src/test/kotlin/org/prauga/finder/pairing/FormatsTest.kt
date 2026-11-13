// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatsTest {
    private val key = ByteArray(32) { 9 }

    @Test
    fun `invitation survives the QR text and has the documented size`() {
        val invitation = Invitation.create(key, nowSeconds = 1_000)
        val text = invitation.encode()

        val decoded = Invitation.decode(text, nowSeconds = 1_000)!!

        assertTrue(text.startsWith("pvotfinder:1:"))
        assertEquals(13 + 119, text.length)
        assertArrayEquals(invitation.sessionId, decoded.sessionId)
        assertArrayEquals(invitation.psk, decoded.psk)
        assertArrayEquals(key, decoded.staticPublicKey)
        assertEquals(1_300, decoded.expiresAtSeconds)
    }

    @Test
    fun `expired, future and malformed invitations are refused`() {
        val text = Invitation.create(key, nowSeconds = 1_000).encode()

        assertNull(Invitation.decode(text, nowSeconds = 1_300))
        assertNull(Invitation.decode(text, nowSeconds = 500))
        assertNull(Invitation.decode("https://example.com", nowSeconds = 1_000))
        assertNull(Invitation.decode(text.dropLast(2), nowSeconds = 1_000))
        assertNull(Invitation.decode("pvotfinder:1:!!", nowSeconds = 1_000))
    }

    @Test
    fun `advertisement fits the ten-byte payload and differs per session`() {
        val a = Invitation.create(key, nowSeconds = 0)
        val b = Invitation.create(key, nowSeconds = 0)

        assertEquals(10, PairingProtocol.advertisement(a.sessionId).size)
        assertTrue(
            !PairingProtocol.sessionTag(a.sessionId).contentEquals(
                PairingProtocol.sessionTag(b.sessionId)
            )
        )
    }

    @Test
    fun `peer info round-trips and truncates long names on a character boundary`() {
        val info = PeerInfo(ByteArray(16) { 5 }, "Phone ".repeat(20) + "é", "0.1.0")

        val decoded = PeerInfo.decode(info.encode())

        assertTrue(decoded.name.toByteArray().size <= PeerInfo.MAX_TEXT)
        assertTrue(info.name.startsWith(decoded.name))
        assertEquals(PeerInfo(ByteArray(16) { 5 }, "Pixel", "1"),
            PeerInfo.decode(PeerInfo(ByteArray(16) { 5 }, "Pixel", "1").encode()))
        val emoji = PeerInfo.truncateUtf8("ab😀", 5)
        assertEquals("ab", String(emoji))
    }

    @Test
    fun `malformed peer info is refused`() {
        val bytes = PeerInfo(ByteArray(16), "x", "1").encode()

        assertThrows(PairingException::class.java) { PeerInfo.decode(bytes.copyOf(10)) }
        assertThrows(PairingException::class.java) { PeerInfo.decode(bytes + 0) }
    }

    @Test
    fun `framing splits and reassembles across chunk boundaries`() {
        val first = ByteArray(300) { it.toByte() }
        val second = ByteArray(5) { 7 }
        val chunks = Framing.chunks(first, 20) + Framing.chunks(second, 3)
        val reassembler = Framing.Reassembler()

        val out = chunks.flatMap(reassembler::add)

        assertEquals(2, out.size)
        assertArrayEquals(first, out[0])
        assertArrayEquals(second, out[1])
    }

    @Test
    fun `framing refuses oversize lengths`() {
        assertThrows(PairingException::class.java) {
            Framing.Reassembler(maxMessage = 10).add(byteArrayOf(0, 11))
        }
    }
}
