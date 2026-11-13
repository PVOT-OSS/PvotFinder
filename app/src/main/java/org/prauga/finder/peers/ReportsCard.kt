// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.peers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prauga.pvot.designsystem.components.PvotCard
import com.prauga.pvot.designsystem.components.button.PvotButton
import com.prauga.pvot.designsystem.components.button.PvotButtonStyle
import com.prauga.pvot.designsystem.components.list.PvotListGroup
import com.prauga.pvot.designsystem.components.list.PvotListRow
import com.prauga.pvot.designsystem.theme.PvotSpacing
import org.prauga.finder.R
import org.prauga.finder.identity.IdentityRepository
import org.prauga.finder.identity.Report
import org.prauga.finder.reports.FetchState
import org.prauga.finder.reports.ReportFetcher

/** Locations the paired phone posted about itself on the notice boards. */
@Composable
fun ReportsCard(
    identity: IdentityRepository,
    fetcher: ReportFetcher,
    deviceIdHex: String,
    peerName: String,
) {
    val flow = remember(deviceIdHex) { identity.reports(deviceIdHex) }
    val reports by flow.collectAsStateWithLifecycle(emptyList())
    val state by fetcher.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(deviceIdHex) { fetcher.fetch() }

    Column(verticalArrangement = Arrangement.spacedBy(PvotSpacing.M)) {
        PvotCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(PvotSpacing.L),
                verticalArrangement = Arrangement.spacedBy(PvotSpacing.S),
            ) {
                Text(
                    stringResource(R.string.reports_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    when (val s = state) {
                        FetchState.NoBoards -> stringResource(R.string.reports_no_boards)
                        FetchState.Working -> stringResource(R.string.reports_working)
                        is FetchState.Done ->
                            pluralStringResource(
                                R.plurals.reports_done,
                                s.boards,
                                s.boardsReached,
                                s.boards,
                            )
                        FetchState.Idle -> stringResource(R.string.reports_hint, peerName)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (reports.isEmpty() && state is FetchState.Done) {
                    Text(
                        stringResource(R.string.reports_none, peerName),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                PvotButton(
                    stringResource(R.string.reports_check),
                    fetcher::fetch,
                    Modifier.fillMaxWidth(),
                    style = PvotButtonStyle.Tonal,
                    enabled = state != FetchState.Working,
                )
            }
        }
        if (reports.isNotEmpty()) {
            PvotListGroup {
                reports.forEach { report ->
                    item {
                        PvotListRow(
                            title = time(context, report.takenAtSeconds * 1_000),
                            supportingText = describe(report),
                            onClick = {
                                openMap(context, report.latitude, report.longitude, peerName)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun describe(report: Report): String =
    report.batteryPercent?.let {
        stringResource(R.string.report_line_battery, report.accuracyMetres.coerceAtLeast(1), it)
    } ?: stringResource(R.string.report_line, report.accuracyMetres.coerceAtLeast(1))
