// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.finding

import android.os.SystemClock
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.prauga.finder.bluetooth.DiagnosticEvent
import org.prauga.finder.bluetooth.DiagnosticsLog
import org.prauga.finder.bluetooth.DiscoveryController
import org.prauga.finder.bluetooth.NearbyKey
import org.prauga.finder.bluetooth.gatt.GattMessageClient
import org.prauga.finder.identity.IdentityRepository

sealed interface FindState {
    data object Working : FindState

    data class Verified(val atElapsedMillis: Long) : FindState

    data object Ringing : FindState

    data object RingStopped : FindState

    data class Failed(val reason: Reason) : FindState

    enum class Reason {
        NOT_NEARBY,
        UNREACHABLE,
        NOT_VERIFIED,
        REFUSED,
    }
}

/** Connects to a nearby paired phone to verify it or make it ring. */
class Finder(
    private val scope: CoroutineScope,
    private val discovery: DiscoveryController,
    private val identity: IdentityRepository,
    private val client: GattMessageClient,
    private val log: DiagnosticsLog,
) {
    private val _states = MutableStateFlow<Map<String, FindState>>(emptyMap())
    val states: StateFlow<Map<String, FindState>> = _states.asStateFlow()

    fun verify(deviceIdHex: String) = run(deviceIdHex, Command.Ping)

    fun ring(deviceIdHex: String) = run(deviceIdHex, Command.Ring(RING_SECONDS))

    fun stopRing(deviceIdHex: String) = run(deviceIdHex, Command.StopRing)

    private fun run(deviceIdHex: String, command: Command) {
        if (_states.value[deviceIdHex] == FindState.Working) return
        set(deviceIdHex, FindState.Working)
        scope.launch { set(deviceIdHex, session(deviceIdHex, command)) }
    }

    private suspend fun session(deviceIdHex: String, command: Command): FindState {
        val nearby =
            discovery.nearby.value.firstOrNull { it.key == NearbyKey.Peer(deviceIdHex) }
        val device = nearby?.device ?: return FindState.Failed(FindState.Reason.NOT_NEARBY)
        val peerKey =
            identity.peerStaticKeys()[deviceIdHex]
                ?: return FindState.Failed(FindState.Reason.NOT_NEARBY)
        val link =
            try {
                client.connect(
                    device,
                    FindingProtocol.SERVICE_UUID,
                    FindingProtocol.MESSAGE_CHARACTERISTIC_UUID,
                )
            } catch (e: IOException) {
                log.add(DiagnosticEvent.Kind.ERROR, "Finding connect: ${e.message}")
                return FindState.Failed(FindState.Reason.UNREACHABLE)
            } catch (e: TimeoutCancellationException) {
                return FindState.Failed(FindState.Reason.UNREACHABLE)
            }
        return try {
            val reply =
                withTimeout(ANSWER_TIMEOUT_MILLIS) {
                    FindingClient.open(link, identity.staticKey(), peerKey, nearby.advertisedId)
                        .request(command)
                }
            log.add(DiagnosticEvent.Kind.SCANNER, "Paired phone answered $command with $reply")
            when {
                reply != Reply.OK -> FindState.Failed(FindState.Reason.REFUSED)
                command is Command.Ring -> FindState.Ringing
                command == Command.StopRing -> FindState.RingStopped
                else -> FindState.Verified(SystemClock.elapsedRealtime())
            }
        } catch (e: FindingException) {
            log.add(DiagnosticEvent.Kind.ERROR, "Finding handshake: ${e.message}")
            FindState.Failed(FindState.Reason.NOT_VERIFIED)
        } catch (e: TimeoutCancellationException) {
            log.add(DiagnosticEvent.Kind.ERROR, "Paired phone did not answer")
            FindState.Failed(FindState.Reason.NOT_VERIFIED)
        } catch (e: IOException) {
            log.add(DiagnosticEvent.Kind.ERROR, "Finding link: ${e.message}")
            FindState.Failed(FindState.Reason.UNREACHABLE)
        } finally {
            link.close()
        }
    }

    private fun set(deviceIdHex: String, state: FindState) {
        _states.update { it + (deviceIdHex to state) }
    }

    companion object {
        const val RING_SECONDS = 30

        /** A phone that does not accept us closes without answering; do not wait forever. */
        private const val ANSWER_TIMEOUT_MILLIS = 10_000L
    }
}
