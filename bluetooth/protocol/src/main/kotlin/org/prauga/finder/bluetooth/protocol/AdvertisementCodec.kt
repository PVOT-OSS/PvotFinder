// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth.protocol

sealed interface DecodeResult {
    data class Valid(val payload: AdvertisementPayload) : DecodeResult

    data class Invalid(val reason: Reason) : DecodeResult

    enum class Reason {
        WRONG_LENGTH,
        UNSUPPORTED_VERSION,
    }
}

object AdvertisementCodec {
    fun encode(payload: AdvertisementPayload): ByteArray =
        byteArrayOf(payload.version.toByte(), payload.flags.toByte()) +
            payload.temporaryId.toByteArray()

    fun decode(data: ByteArray): DecodeResult {
        if (data.size != FinderProtocol.PAYLOAD_SIZE) {
            return DecodeResult.Invalid(DecodeResult.Reason.WRONG_LENGTH)
        }
        val version = data[0].toInt() and 0xFF
        if (version != FinderProtocol.VERSION) {
            return DecodeResult.Invalid(DecodeResult.Reason.UNSUPPORTED_VERSION)
        }
        return DecodeResult.Valid(
            AdvertisementPayload(
                temporaryId = TemporaryId(data.copyOfRange(2, data.size)),
                version = version,
                flags = data[1].toInt() and 0xFF,
            )
        )
    }
}
