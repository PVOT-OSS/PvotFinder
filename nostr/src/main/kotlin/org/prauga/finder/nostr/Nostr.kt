// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.nostr

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put

/** A signed Nostr event (NIP-01). */
data class Event(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("pubkey", pubkey)
        put("created_at", createdAt)
        put("kind", kind)
        put(
            "tags",
            buildJsonArray { tags.forEach { tag -> add(JsonArray(tag.map(::JsonPrimitive))) } },
        )
        put("content", content)
        put("sig", sig)
    }

    /** True when the id is the event's hash and the signature is by [pubkey]. */
    fun isValid(): Boolean =
        runCatching {
                val expected = hash(pubkey, createdAt, kind, tags, content)
                expected.toHex() == id &&
                    Schnorr.verify(pubkey.hexToBytes(), expected, sig.hexToBytes())
            }
            .getOrDefault(false)

    fun tagValues(name: String): List<String> =
        tags.filter { it.size >= 2 && it[0] == name }.map { it[1] }

    companion object {
        /** Signs a new event with a fresh key that is used for nothing else. */
        fun create(kind: Int, tags: List<List<String>>, content: String, createdAt: Long): Event {
            val key = Schnorr.newPrivateKey()
            val pubkey = Schnorr.publicKey(key).toHex()
            val id = hash(pubkey, createdAt, kind, tags, content)
            val sig = Schnorr.sign(key, id).toHex()
            key.fill(0)
            return Event(id.toHex(), pubkey, createdAt, kind, tags, content, sig)
        }

        fun fromJson(element: JsonElement): Event? =
            runCatching {
                    val o = element.jsonObject
                    Event(
                        id = o.getValue("id").jsonPrimitive.content,
                        pubkey = o.getValue("pubkey").jsonPrimitive.content,
                        createdAt = o.getValue("created_at").jsonPrimitive.long,
                        kind = o.getValue("kind").jsonPrimitive.int,
                        tags =
                            o.getValue("tags").jsonArray.map { tag ->
                                tag.jsonArray.map { it.jsonPrimitive.content }
                            },
                        content = o.getValue("content").jsonPrimitive.content,
                        sig = o.getValue("sig").jsonPrimitive.content,
                    )
                }
                .getOrNull()

        /** SHA-256 of the NIP-01 serialization `[0,pubkey,created_at,kind,tags,content]`. */
        internal fun hash(
            pubkey: String,
            createdAt: Long,
            kind: Int,
            tags: List<List<String>>,
            content: String,
        ): ByteArray {
            val text = buildString {
                append("[0,").append(quote(pubkey)).append(',').append(createdAt).append(',')
                append(kind).append(",[")
                tags.forEachIndexed { i, tag ->
                    if (i > 0) append(',')
                    append('[').append(tag.joinToString(",") { quote(it) }).append(']')
                }
                append("],").append(quote(content)).append(']')
            }
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        }

        /** NIP-01 escaping: only these characters are escaped, everything else is written as is. */
        internal fun quote(s: String): String = buildString {
            append('"')
            for (c in s) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}

/** A NIP-01 subscription filter: kinds, single-letter tag values, and a start time. */
data class Filter(
    val kinds: List<Int>,
    val tags: Map<Char, List<String>> = emptyMap(),
    val since: Long? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("kinds", JsonArray(kinds.map(::JsonPrimitive)))
        tags.forEach { (letter, values) -> put("#$letter", JsonArray(values.map(::JsonPrimitive))) }
        since?.let { put("since", it) }
    }
}

/** A text WebSocket to one relay. */
interface RelaySocket {
    suspend fun send(text: String)

    /** The next message; throws when the socket closed. */
    suspend fun receive(): String

    fun close()
}

class RelayException(message: String) : Exception(message)

/** The relay's answer to a published event: stored or not, and its reason (NIP-01 `OK`). */
data class PublishResult(val accepted: Boolean, val message: String)

/** Talks NIP-01 to one relay over [socket], one request at a time. */
class RelayClient(private val socket: RelaySocket) {
    private var next = 0

    /** The relay's answer: whether it stored the event, and why not. */
    suspend fun publish(event: Event): PublishResult {
        socket.send(buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(event.toJson())
        }.toString())
        while (true) {
            val message = parse(socket.receive()) ?: continue
            if (message.firstString() == "OK" && message.getOrNull(1)?.jsonPrimitive?.content == event.id) {
                return PublishResult(
                    accepted = message.getOrNull(2)?.jsonPrimitive?.content == "true",
                    message = message.getOrNull(3)?.jsonPrimitive?.content.orEmpty(),
                )
            }
        }
    }

    /** Stored events matching [filter], each checked for a valid id and signature. */
    suspend fun query(filter: Filter): List<Event> {
        val id = "q${next++}"
        socket.send(buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(id))
            add(filter.toJson())
        }.toString())
        val events = mutableListOf<Event>()
        while (true) {
            val message = parse(socket.receive()) ?: continue
            if (message.getOrNull(1)?.jsonPrimitive?.content != id) continue
            when (message.firstString()) {
                "EVENT" ->
                    message.getOrNull(2)?.let(Event::fromJson)?.takeIf { it.isValid() }?.let {
                        events += it
                    }
                "EOSE" -> break
                "CLOSED" -> throw RelayException("Relay closed the request")
            }
        }
        socket.send(buildJsonArray {
            add(JsonPrimitive("CLOSE"))
            add(JsonPrimitive(id))
        }.toString())
        return events
    }

    fun close() = socket.close()

    private fun parse(text: String): JsonArray? =
        runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull()

    private fun JsonArray.firstString(): String? = getOrNull(0)?.jsonPrimitive?.content
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray =
    ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
