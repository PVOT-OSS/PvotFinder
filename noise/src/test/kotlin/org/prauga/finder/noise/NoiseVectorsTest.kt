// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.noise

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks the handshake against the cacophony test vectors for every pattern we support. */
class NoiseVectorsTest {
    private val vectors: List<JsonObject> =
        Json.parseToJsonElement(
                javaClass.getResource("/cacophony-subset.json")!!.readText()
            )
            .jsonObject["vectors"]!!
            .jsonArray
            .map { it.jsonObject }

    private fun JsonObject.bytes(key: String): ByteArray? =
        this[key]?.jsonPrimitive?.content?.let(::hex)

    private fun JsonObject.psk(key: String): ByteArray? =
        this[key]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content?.let(::hex)

    private fun fixed(key: ByteArray?): () -> Dh25519 {
        var used = false
        return {
            check(!used) { "Only one ephemeral key per side" }
            used = true
            SoftwareX25519.fromPrivate(requireNotNull(key))
        }
    }

    @Test
    fun `all vectors are present`() {
        assertEquals(13, vectors.size)
    }

    @Test
    fun `handshakes and transport messages match the vectors`() {
        for (vector in vectors) {
            val name = vector["protocol_name"]!!.jsonPrimitive.content
            val pattern = name.removePrefix("Noise_").substringBefore("_")
            val initiator =
                NoiseHandshake(
                    pattern,
                    initiator = true,
                    prologue = vector.bytes("init_prologue")!!,
                    staticKey = vector.bytes("init_static")?.let(SoftwareX25519::fromPrivate),
                    remoteStaticKey = vector.bytes("init_remote_static"),
                    psk = vector.psk("init_psks"),
                    ephemeralKeys = fixed(vector.bytes("init_ephemeral")),
                )
            val responder =
                NoiseHandshake(
                    pattern,
                    initiator = false,
                    prologue = vector.bytes("resp_prologue")!!,
                    staticKey = vector.bytes("resp_static")?.let(SoftwareX25519::fromPrivate),
                    remoteStaticKey = vector.bytes("resp_remote_static"),
                    psk = vector.psk("resp_psks"),
                    ephemeralKeys = fixed(vector.bytes("resp_ephemeral")),
                )
            val messages = vector["messages"]!!.jsonArray.map { it.jsonObject }
            var transports: Pair<NoiseTransport, NoiseTransport>? = null
            messages.forEachIndexed { index, message ->
                val payload = message.bytes("payload")!!
                val expected = message.bytes("ciphertext")!!
                val fromInitiator = index % 2 == 0
                val t = transports
                if (t == null) {
                    val (writer, reader) =
                        if (fromInitiator) initiator to responder else responder to initiator
                    val written = writer.writeMessage(payload)
                    assertArrayEquals("$name message $index", expected, written)
                    assertArrayEquals("$name message $index", payload, reader.readMessage(written))
                    if (initiator.isFinished && responder.isFinished) {
                        assertArrayEquals(
                            "$name handshake hash",
                            vector.bytes("handshake_hash"),
                            initiator.handshakeHash,
                        )
                        assertArrayEquals(initiator.handshakeHash, responder.handshakeHash)
                        transports = initiator.split() to responder.split()
                    }
                } else {
                    val (writer, reader) = if (fromInitiator) t else t.second to t.first
                    val written = writer.encrypt(payload)
                    assertArrayEquals("$name transport $index", expected, written)
                    assertArrayEquals("$name transport $index", payload, reader.decrypt(written))
                }
            }
        }
    }
}
