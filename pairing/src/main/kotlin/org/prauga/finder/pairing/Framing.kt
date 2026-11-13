// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.pairing

import java.io.ByteArrayOutputStream

/** Length-prefixed messages over a stream of small chunks, such as GATT writes and notifications. */
object Framing {
    const val MAX_MESSAGE = 0xFFFF

    fun frame(message: ByteArray): ByteArray {
        require(message.size <= MAX_MESSAGE) { "Message too large" }
        return byteArrayOf((message.size ushr 8).toByte(), message.size.toByte()) + message
    }

    fun chunks(message: ByteArray, chunkSize: Int): List<ByteArray> {
        require(chunkSize > 0)
        return frame(message).toList().chunked(chunkSize).map { it.toByteArray() }
    }

    /** Collects chunks and returns each message once it is complete. */
    class Reassembler(private val maxMessage: Int = MAX_MESSAGE) {
        private val buffer = ByteArrayOutputStream()

        fun add(chunk: ByteArray): List<ByteArray> {
            buffer.write(chunk)
            val messages = mutableListOf<ByteArray>()
            var data = buffer.toByteArray()
            while (data.size >= 2) {
                val length = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                if (length > maxMessage) throw PairingException("Message too large")
                if (data.size < 2 + length) break
                messages += data.copyOfRange(2, 2 + length)
                data = data.copyOfRange(2 + length, data.size)
            }
            buffer.reset()
            buffer.write(data)
            return messages
        }
    }
}
