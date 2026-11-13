// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.pairing

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.prauga.finder.bluetooth.DiagnosticEvent
import org.prauga.finder.bluetooth.DiagnosticsLog
import org.prauga.finder.bluetooth.pairing.PairingClient
import org.prauga.finder.bluetooth.pairing.PairingLink
import org.prauga.finder.bluetooth.pairing.PairingServer
import org.prauga.finder.identity.IdentityRepository

sealed interface PairingState {
    data object Idle : PairingState

    data object Preparing : PairingState

    data class Showing(val qrText: String, val expiresAtSeconds: Long) : PairingState

    data object Connecting : PairingState

    data class Confirming(val peerName: String, val code: String, val waitingForOther: Boolean) :
        PairingState

    data class Paired(val peerName: String) : PairingState

    data class Failed(val reason: Reason) : PairingState

    enum class Reason {
        INVALID_CODE,
        EXPIRED,
        BLUETOOTH,
        HANDSHAKE,
        WRONG_PHONE,
        CANCELLED_HERE,
        CANCELLED_THERE,
        PROTOCOL,
    }
}

/** Runs one pairing at a time, showing or scanning a code, for the pairing screens. */
class PairingController(
    private val scope: CoroutineScope,
    private val identity: IdentityRepository,
    private val newServer: () -> PairingServer,
    private val client: PairingClient,
    private val log: DiagnosticsLog,
    private val appVersion: String,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private var job: Job? = null
    private var decision: CompletableDeferred<Boolean>? = null

    /** Shows a code on this phone and waits for the other phone to scan it. */
    fun show() = start {
        val staticKey = identity.staticKey()
        val invitation = Invitation.create(staticKey.publicKey, nowSeconds())
        val server = newServer()
        try {
            try {
                server.open(invitation.sessionId)
            } catch (e: IOException) {
                log.add(DiagnosticEvent.Kind.ERROR, "Pairing server: ${e.message}")
                return@start fail(PairingState.Reason.BLUETOOTH)
            }
            var attempts = 0
            while (true) {
                _state.value = PairingState.Showing(invitation.encode(), invitation.expiresAtSeconds)
                val remaining = (invitation.expiresAtSeconds - nowSeconds()) * 1_000
                val link =
                    withTimeoutOrNull(remaining.coerceAtLeast(0)) { server.accept() }
                        ?: return@start fail(PairingState.Reason.EXPIRED)
                _state.value = PairingState.Connecting
                val failure =
                    runSession(link) {
                        PairingSession.responder(
                            invitation,
                            staticKey,
                            identity.peerInfo(appVersion),
                            identity.beaconSecret(),
                            link,
                        )
                    }
                if (failure == null) return@start
                attempts++
                if (failure != PairingState.Reason.HANDSHAKE || attempts >= MAX_ATTEMPTS) {
                    return@start fail(failure)
                }
            }
        } finally {
            server.close()
        }
    }

    /** Pairs with the phone whose code [qrText] was scanned. */
    fun scan(qrText: String) = start {
        val invitation =
            Invitation.decode(qrText, nowSeconds())
                ?: return@start fail(
                    if (qrText.startsWith(Invitation.PREFIX)) PairingState.Reason.EXPIRED
                    else PairingState.Reason.INVALID_CODE
                )
        _state.value = PairingState.Connecting
        val link =
            try {
                client.connect(invitation.sessionId)
            } catch (e: IOException) {
                log.add(DiagnosticEvent.Kind.ERROR, "Pairing connect: ${e.message}")
                return@start fail(PairingState.Reason.BLUETOOTH)
            } catch (e: TimeoutCancellationException) {
                return@start fail(PairingState.Reason.BLUETOOTH)
            }
        val failure =
            runSession(link) {
                PairingSession.initiator(
                    invitation,
                    identity.staticKey(),
                    identity.peerInfo(appVersion),
                    identity.beaconSecret(),
                    link,
                )
            }
        if (failure != null) fail(failure)
    }

    fun confirm() {
        decision?.complete(true)
    }

    /** Cancels whatever is running; while confirming, the other phone is told. */
    fun cancel() {
        if (decision?.complete(false) == true) return
        job?.cancel()
        job = null
        _state.value = PairingState.Idle
    }

    fun reset() {
        if (job?.isActive != true) _state.value = PairingState.Idle
    }

    private fun start(block: suspend () -> Unit) {
        job?.cancel()
        _state.value = PairingState.Preparing
        job = scope.launch { block() }
    }

    /** Returns null once paired, or why it failed. */
    private suspend fun runSession(
        link: PairingLink,
        session: suspend () -> PairingSession,
    ): PairingState.Reason? =
        try {
            val peer =
                session().run { check ->
                    val answer = CompletableDeferred<Boolean>().also { decision = it }
                    _state.value = PairingState.Confirming(check.peer.name, check.code, false)
                    answer.await().also { yes ->
                        if (yes) {
                            _state.value =
                                PairingState.Confirming(check.peer.name, check.code, true)
                        }
                    }
                }
            identity.addPeer(peer)
            log.add(DiagnosticEvent.Kind.ADAPTER, "Paired with a phone")
            _state.value = PairingState.Paired(peer.info.name)
            null
        } catch (e: PairingFailedException) {
            log.add(DiagnosticEvent.Kind.ERROR, "Pairing failed: ${e.reason}")
            when (e.reason) {
                PairingFailure.HANDSHAKE -> PairingState.Reason.HANDSHAKE
                PairingFailure.WRONG_PHONE -> PairingState.Reason.WRONG_PHONE
                PairingFailure.CANCELLED_HERE -> PairingState.Reason.CANCELLED_HERE
                PairingFailure.CANCELLED_THERE -> PairingState.Reason.CANCELLED_THERE
                PairingFailure.PROTOCOL -> PairingState.Reason.PROTOCOL
            }
        } catch (e: IOException) {
            log.add(DiagnosticEvent.Kind.ERROR, "Pairing link: ${e.message}")
            PairingState.Reason.BLUETOOTH
        } finally {
            decision = null
            link.close()
        }

    private fun fail(reason: PairingState.Reason) {
        _state.value = PairingState.Failed(reason)
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
