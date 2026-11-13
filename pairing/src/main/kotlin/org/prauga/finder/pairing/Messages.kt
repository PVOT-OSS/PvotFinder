// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.pairing

import java.nio.ByteBuffer
import java.nio.BufferUnderflowException

class PairingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What a phone tells the other about itself during the handshake. Contains no secrets. */
data class PeerInfo(val deviceId: ByteArray, val name: String, val appVersion: String) {
    init {
        require(deviceId.size == DEVICE_ID_SIZE)
    }

    fun encode(): ByteArray {
        val nameBytes = truncateUtf8(name, MAX_TEXT)
        val versionBytes = truncateUtf8(appVersion, MAX_TEXT)
        return ByteBuffer.allocate(1 + DEVICE_ID_SIZE + 2 + nameBytes.size + versionBytes.size)
            .put(VERSION.toByte())
            .put(deviceId)
            .put(nameBytes.size.toByte())
            .put(nameBytes)
            .put(versionBytes.size.toByte())
            .put(versionBytes)
            .array()
    }

    override fun equals(other: Any?): Boolean =
        other is PeerInfo &&
            deviceId.contentEquals(other.deviceId) &&
            name == other.name &&
            appVersion == other.appVersion

    override fun hashCode(): Int = 31 * (31 * deviceId.contentHashCode() + name.hashCode()) +
        appVersion.hashCode()

    companion object {
        const val DEVICE_ID_SIZE = 16
        const val MAX_TEXT = 64
        private const val VERSION = 1

        fun decode(bytes: ByteArray): PeerInfo =
            try {
                val buffer = ByteBuffer.wrap(bytes)
                if (buffer.get().toInt() != VERSION) throw PairingException("Unknown peer info")
                val id = ByteArray(DEVICE_ID_SIZE).also(buffer::get)
                val name = ByteArray(buffer.get().toInt() and 0xFF).also(buffer::get)
                val version = ByteArray(buffer.get().toInt() and 0xFF).also(buffer::get)
                if (buffer.hasRemaining() || name.size > MAX_TEXT || version.size > MAX_TEXT) {
                    throw PairingException("Malformed peer info")
                }
                PeerInfo(id, String(name, Charsets.UTF_8), String(version, Charsets.UTF_8))
            } catch (e: BufferUnderflowException) {
                throw PairingException("Malformed peer info", e)
            }

        internal fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
            val out = StringBuilder()
            var size = 0
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                val chunk = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
                if (size + chunk.size > maxBytes) break
                out.appendCodePoint(codePoint)
                size += chunk.size
                i += Character.charCount(codePoint)
            }
            return out.toString().toByteArray(Charsets.UTF_8)
        }
    }
}

/** Messages sent inside the Noise transport once the handshake is done. */
internal sealed interface TransportMessage {
    data object Confirm : TransportMessage

    class Share(val beaconSecret: ByteArray) : TransportMessage

    data object Abort : TransportMessage

    fun encode(): ByteArray =
        when (this) {
            Confirm -> byteArrayOf(CONFIRM)
            is Share -> byteArrayOf(SHARE) + beaconSecret
            Abort -> byteArrayOf(ABORT)
        }

    companion object {
        private const val CONFIRM: Byte = 1
        private const val SHARE: Byte = 2
        private const val ABORT: Byte = 3
        const val SECRET_SIZE = 32

        fun decode(bytes: ByteArray): TransportMessage =
            when {
                bytes.size == 1 && bytes[0] == CONFIRM -> Confirm
                bytes.size == 1 && bytes[0] == ABORT -> Abort
                bytes.size == 1 + SECRET_SIZE && bytes[0] == SHARE ->
                    Share(bytes.copyOfRange(1, bytes.size))
                else -> throw PairingException("Unknown message")
            }
    }
}
