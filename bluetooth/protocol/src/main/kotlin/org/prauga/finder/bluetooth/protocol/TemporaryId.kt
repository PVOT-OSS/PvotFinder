// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth.protocol

import java.security.SecureRandom

/** An 8-byte advertised identifier: rotating for paired phones, random in tests. */
class TemporaryId(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(bytes.size == SIZE) { "A temporary ID is $SIZE bytes, not ${bytes.size}" }
    }

    fun toByteArray(): ByteArray = value.copyOf()

    fun toHex(): String = value.joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?): Boolean =
        other is TemporaryId && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "TemporaryId(${toHex()})"

    companion object {
        const val SIZE = 8

        fun random(random: SecureRandom = SecureRandom()): TemporaryId =
            TemporaryId(ByteArray(SIZE).also(random::nextBytes))
    }
}
