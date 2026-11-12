// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TemporaryIdTest {
    @Test
    fun `random IDs are eight bytes and differ`() {
        val ids = List(100) { TemporaryId.random() }

        ids.forEach { assertEquals(TemporaryId.SIZE, it.toByteArray().size) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `hex is lower case and two digits per byte`() {
        val id = TemporaryId(byteArrayOf(0, 1, 15, 16, 127, -128, -2, -1))

        assertEquals("00010f107f80feff", id.toHex())
    }

    @Test
    fun `equality is by content and the bytes are copied`() {
        val bytes = ByteArray(TemporaryId.SIZE) { it.toByte() }
        val id = TemporaryId(bytes)

        bytes[0] = 42
        id.toByteArray()[1] = 42

        assertEquals(TemporaryId(ByteArray(TemporaryId.SIZE) { it.toByte() }), id)
        assertNotEquals(TemporaryId(bytes), id)
    }

    @Test
    fun `rejects other sizes`() {
        assertThrows(IllegalArgumentException::class.java) { TemporaryId(ByteArray(7)) }
    }
}
