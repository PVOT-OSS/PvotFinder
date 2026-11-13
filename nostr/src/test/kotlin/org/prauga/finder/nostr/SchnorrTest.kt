// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.nostr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every case of the official BIP-340 test vectors. */
class SchnorrTest {
    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private val vectors =
        javaClass.getResource("/bip340-test-vectors.csv")!!.readText().lines().drop(1).filter {
            it.isNotBlank()
        }

    @Test
    fun `matches every BIP-340 vector`() {
        assertEquals(19, vectors.size)
        for (line in vectors) {
            val f = line.split(",")
            val index = f[0]
            val secret = f[1]
            val publicKey = hex(f[2])
            val message = hex(f[4])
            val signature = hex(f[5])
            val expected = f[6] == "TRUE"
            if (secret.isNotEmpty()) {
                assertArrayEquals("vector $index public key", publicKey, Schnorr.publicKey(hex(secret)))
                assertArrayEquals(
                    "vector $index signature",
                    signature,
                    Schnorr.sign(hex(secret), message, hex(f[3])),
                )
            }
            assertEquals("vector $index verify", expected, Schnorr.verify(publicKey, message, signature))
        }
    }

    @Test
    fun `fresh keys sign and verify`() {
        val key = Schnorr.newPrivateKey()
        val message = ByteArray(32) { 5 }
        val signature = Schnorr.sign(key, message)

        assertTrue(Schnorr.verify(Schnorr.publicKey(key), message, signature))
        signature[63] = (signature[63] + 1).toByte()
        assertTrue(!Schnorr.verify(Schnorr.publicKey(key), message, signature))
    }
}
