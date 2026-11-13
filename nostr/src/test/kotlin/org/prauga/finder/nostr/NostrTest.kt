// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.nostr

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrTest {
    private val tags = listOf(listOf("y", "00ff"), listOf("expiration", "1700000000"))

    @Test
    fun `created events are valid and tampering breaks them`() {
        val event = Event.create(3971, tags, "abc", 1_699_000_000)

        assertTrue(event.isValid())
        assertEquals(listOf("00ff"), event.tagValues("y"))
        assertFalse(event.copy(content = "abd").isValid())
        assertFalse(event.copy(tags = listOf(listOf("y", "00fe"))).isValid())
        assertFalse(event.copy(sig = "zz").isValid())
    }

    @Test
    fun `json round trip keeps the event valid`() {
        val event = Event.create(3971, tags, "line\nquote\" back\\slash é", 1_699_000_000)

        val parsed = Event.fromJson(Json.parseToJsonElement(event.toJson().toString()))!!

        assertEquals(event, parsed)
        assertTrue(parsed.isValid())
    }

    @Test
    fun `serialization escapes only what NIP-01 lists`() {
        assertEquals("\"a\\\"b\\\\c\\nd\\te/é\"", Event.quote("a\"b\\c\nd\te/é"))
    }

    private class FakeSocket(private val reply: (String) -> List<String>) : RelaySocket {
        val sent = mutableListOf<String>()
        private val inbox = Channel<String>(Channel.UNLIMITED)

        override suspend fun send(text: String) {
            sent += text
            reply(text).forEach { inbox.send(it) }
        }

        override suspend fun receive(): String = inbox.receive()

        override fun close() {}
    }

    @Test
    fun `publish waits for the relay's OK`() = runTest {
        val event = Event.create(3971, tags, "x", 1)
        val socket = FakeSocket { listOf("""["NOTICE","hi"]""", """["OK","${event.id}",true,""]""") }

        assertTrue(RelayClient(socket).publish(event))
    }

    @Test
    fun `query keeps valid events until EOSE and closes the subscription`() = runTest {
        val good = Event.create(3971, tags, "good", 1)
        val forged = good.copy(content = "forged")
        val socket = FakeSocket { text ->
            val message = Json.parseToJsonElement(text).jsonArray
            if (message[0].jsonPrimitive.content != "REQ") return@FakeSocket emptyList()
            val id = message[1].jsonPrimitive.content
            listOf(
                """["EVENT","$id",${good.toJson()}]""",
                """["EVENT","$id",${forged.toJson()}]""",
                """["EVENT","other",${good.toJson()}]""",
                """["EOSE","$id"]""",
            )
        }

        val events = RelayClient(socket).query(Filter(listOf(3971), mapOf('y' to listOf("00ff"))))

        assertEquals(listOf(good), events)
        assertTrue(socket.sent.last().startsWith("[\"CLOSE\""))
        assertTrue(socket.sent.first().contains("\"#y\":[\"00ff\"]"))
    }
}
