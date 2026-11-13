// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.reports

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.prauga.finder.bluetooth.DiagnosticEvent
import org.prauga.finder.bluetooth.DiagnosticsLog
import org.prauga.finder.identity.IdentityRepository
import org.prauga.finder.identity.Report
import org.prauga.finder.nostr.RelayClient
import org.prauga.finder.nostr.RelayException
import org.prauga.finder.report.SelfReport

sealed interface FetchState {
    data object Idle : FetchState

    data object Working : FetchState

    data class Done(val boardsReached: Int, val boards: Int, val newReports: Int) : FetchState

    data object NoBoards : FetchState
}

/** Collects the reports paired phones posted about themselves in the last week. */
class ReportFetcher(
    private val scope: CoroutineScope,
    private val settings: ReportSettings,
    private val identity: IdentityRepository,
    private val http: OkHttpClient,
    private val log: DiagnosticsLog,
) {
    private val _state = MutableStateFlow<FetchState>(FetchState.Idle)
    val state: StateFlow<FetchState> = _state.asStateFlow()

    fun fetch() {
        if (_state.value == FetchState.Working) return
        val boards = settings.boards.value
        if (boards.isEmpty()) {
            _state.value = FetchState.NoBoards
            return
        }
        _state.value = FetchState.Working
        scope.launch {
            val now = System.currentTimeMillis() / 1_000
            val since = now - SelfReport.KEEP_SECONDS
            val peers = identity.peerSecrets().map { (id, secret) ->
                Triple(id, secret, SelfReport.tags(secret, since, now))
            }
            var reached = 0
            var added = 0
            for (board in boards) {
                try {
                    withTimeout(TIMEOUT_MILLIS) {
                        val client = RelayClient(OkHttpRelaySocket.connect(http, board))
                        try {
                            for ((id, secret, tags) in peers) {
                                for (batch in tags.keys.chunked(BATCH)) {
                                    for (event in client.query(SelfReport.filter(batch, since))) {
                                        val location = SelfReport.open(secret, tags, event) ?: continue
                                        val new = identity.addReport(
                                            id,
                                            Report(
                                                event.id,
                                                location.latitude,
                                                location.longitude,
                                                location.accuracyMetres,
                                                location.takenAtSeconds,
                                                location.batteryPercent,
                                            ),
                                            now,
                                        )
                                        if (new) added++
                                    }
                                }
                            }
                            reached++
                        } finally {
                            client.close()
                        }
                    }
                } catch (e: IOException) {
                    log.add(DiagnosticEvent.Kind.ERROR, "Board unreachable: ${e.message}")
                } catch (e: RelayException) {
                    log.add(DiagnosticEvent.Kind.ERROR, "Board refused: ${e.message}")
                } catch (e: TimeoutCancellationException) {
                    log.add(DiagnosticEvent.Kind.ERROR, "Board timed out")
                }
            }
            peers.forEach { it.second.fill(0) }
            log.add(DiagnosticEvent.Kind.SCANNER, "Fetched reports from $reached of ${boards.size} boards")
            _state.value = FetchState.Done(reached, boards.size, added)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 60_000L
        const val BATCH = 100
    }
}
