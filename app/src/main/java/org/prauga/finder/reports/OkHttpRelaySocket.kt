// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.reports

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.prauga.finder.nostr.RelaySocket

/** A WebSocket to one notice board. */
class OkHttpRelaySocket private constructor() : RelaySocket {
    private val incoming = Channel<String>(Channel.UNLIMITED)
    private val opened = CompletableDeferred<Unit>()
    private var socket: WebSocket? = null

    private val listener =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                incoming.trySend(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                incoming.close(IOException("Closed: $code"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error = IOException(t.message ?: "Connection failed", t)
                opened.completeExceptionally(error)
                incoming.close(error)
            }
        }

    override suspend fun send(text: String) {
        if (socket?.send(text) != true) throw IOException("Not connected")
    }

    override suspend fun receive(): String = incoming.receive()

    override fun close() {
        socket?.close(1000, null)
        incoming.close()
    }

    companion object {
        suspend fun connect(client: OkHttpClient, url: String): OkHttpRelaySocket {
            val socket = OkHttpRelaySocket()
            socket.socket = client.newWebSocket(Request.Builder().url(url).build(), socket.listener)
            socket.opened.await()
            return socket
        }
    }
}
