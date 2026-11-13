// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AdvertisementCodecTest {
    private val id = TemporaryId(byteArrayOf(1, 2, 3, 4, 5, 6, 7, -1))

    @Test
    fun `encodes version, flags and the ID in ten bytes`() {
        val bytes = AdvertisementCodec.encode(AdvertisementPayload(id, flags = 0x81))

        assertArrayEquals(byteArrayOf(1, 0x81.toByte(), 1, 2, 3, 4, 5, 6, 7, -1), bytes)
        assertEquals(FinderProtocol.PAYLOAD_SIZE, bytes.size)
    }

    @Test
    fun `decodes what it encodes`() {
        val payload = AdvertisementPayload(id, flags = 0xFF)

        val result = AdvertisementCodec.decode(AdvertisementCodec.encode(payload))

        assertEquals(DecodeResult.Valid(payload), result)
    }

    @Test
    fun `rejects data of the wrong length`() {
        val encoded = AdvertisementCodec.encode(AdvertisementPayload(id))

        for (data in listOf(ByteArray(0), encoded.copyOf(9), encoded + 0)) {
            assertEquals(
                DecodeResult.Invalid(DecodeResult.Reason.WRONG_LENGTH),
                AdvertisementCodec.decode(data),
            )
        }
    }

    @Test
    fun `rejects other versions`() {
        val data = AdvertisementCodec.encode(AdvertisementPayload(id)).also { it[0] = 0 }

        assertEquals(
            DecodeResult.Invalid(DecodeResult.Reason.UNSUPPORTED_VERSION),
            AdvertisementCodec.decode(data),
        )
    }

    @Test
    fun `decoded ID does not share the input array`() {
        val data = AdvertisementCodec.encode(AdvertisementPayload(id))
        val decoded = AdvertisementCodec.decode(data) as DecodeResult.Valid

        data[2] = 99

        assertEquals(id, decoded.payload.temporaryId)
    }

    @Test
    fun `payload rejects values over one byte`() {
        assertThrows(IllegalArgumentException::class.java) {
            AdvertisementPayload(id, version = 256)
        }
        assertThrows(IllegalArgumentException::class.java) { AdvertisementPayload(id, flags = -1) }
    }
}
