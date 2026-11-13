// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.finding

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.prauga.finder.bluetooth.AdvertiserState
import org.prauga.finder.bluetooth.DiagnosticEvent
import org.prauga.finder.bluetooth.DiagnosticsLog
import org.prauga.finder.bluetooth.DiscoveryStatus
import org.prauga.finder.bluetooth.gatt.GattMessageServer
import org.prauga.finder.bluetooth.gatt.MessageLink
import org.prauga.finder.bluetooth.protocol.RotatingId
import org.prauga.finder.identity.IdentityRepository

/**
 * Serves the finding characteristic while this phone advertises, so paired phones can verify it and
 * make it ring.
 */
class FindingHost(
    private val scope: CoroutineScope,
    private val status: StateFlow<DiscoveryStatus>,
    private val identity: IdentityRepository,
    private val newServer: () -> GattMessageServer,
    private val ringer: Ringer,
    private val log: DiagnosticsLog,
    private val wallSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
) {
    private var server: GattMessageServer? = null
    private var acceptJob: Job? = null

    fun start() {
        scope.launch {
            status
                .map { it.advertiser is AdvertiserState.Advertising }
                .distinctUntilChanged()
                .collectLatest { advertising ->
                    if (advertising) {
                        open()
                    } else {
                        delay(CLOSE_DELAY_MILLIS)
                        close()
                    }
                }
        }
    }

    private suspend fun open() {
        if (server != null) return
        val gattServer = newServer()
        try {
            gattServer.open()
        } catch (e: IOException) {
            log.add(DiagnosticEvent.Kind.ERROR, "Finding server: ${e.message}")
            return
        }
        server = gattServer
        acceptJob =
            scope.launch {
                while (true) {
                    val link =
                        try {
                            gattServer.accept()
                        } catch (e: Exception) {
                            break
                        }
                    launch { serve(link) }
                }
            }
    }

    private fun close() {
        acceptJob?.cancel()
        acceptJob = null
        server?.close()
        server = null
    }

    private suspend fun serve(link: MessageLink) {
        val peers = identity.peerStaticKeys().map { (id, key) -> KnownPeer(id, key) }
        val names = identity.peers.first().associate { it.deviceIdHex to it.name }
        val period = RotatingId.period(wallSeconds())
        val secret = identity.beaconSecret()
        val recentIds = (period - 1..period + 1).map { RotatingId.forPeriod(secret, it) }
        secret.fill(0)
        try {
            FindingServer(identity.staticKey()).serve(link, peers, recentIds) { peer, command ->
                val name = names[peer.deviceIdHex].orEmpty()
                when (command) {
                    Command.Ping -> {
                        log.add(DiagnosticEvent.Kind.ADVERTISER, "Verified by a paired phone")
                        Reply.OK
                    }
                    is Command.Ring -> {
                        log.add(DiagnosticEvent.Kind.ADVERTISER, "Ring asked by a paired phone")
                        if (ringer.ring(name, command.seconds)) Reply.OK else Reply.REFUSED
                    }
                    Command.StopRing -> {
                        ringer.stop()
                        Reply.OK
                    }
                }
            }
        } catch (e: FindingException) {
            log.add(DiagnosticEvent.Kind.REJECTED, "Finding session refused: ${e.message}")
        } catch (e: IOException) {
            log.add(DiagnosticEvent.Kind.ADVERTISER, "Finding session ended")
        } finally {
            link.close()
        }
    }

    private companion object {
        /** Advertising stops for a moment at every period change; keep sessions alive across it. */
        const val CLOSE_DELAY_MILLIS = 5_000L
    }
}
